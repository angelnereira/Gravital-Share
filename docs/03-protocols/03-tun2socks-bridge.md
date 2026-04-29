# 03.03 — El Bridge tun↔socks

## El problema en una frase

> El kernel nos da paquetes IP. El proxy quiere streams TCP. Hay que traducir, sin perder nada, sin colgar la red, sin gastar la batería.

## Por qué no podemos hacer "shortcut"

La tentación natural es: "leo IPs del TUN, miro el destino, abro un socket TCP yo mismo, copio bytes". **No funciona**. Razones:

1. Los paquetes IP que vienen del kernel **no son streams**. Son fragmentos, posiblemente desordenados, con números de secuencia, ventanas, banderas.
2. Si abrimos un socket TCP normal, el kernel del cliente arma un nuevo handshake **distinto** del que el TCP del usuario ya inició internamente.
3. El usuario nunca recibe los `ACK`s correctos y se interrumpe la sesión.

La única salida limpia es **reensamblar el TCP en espacio de usuario**: un userspace stack que finja ser el otro extremo de la conversación TCP del usuario, y por dentro abra una conexión SOCKS al proxy real.

## El concepto Tun2Socks

```
[App del usuario]
    │ (TCP normal)
    ▼
[Kernel del cliente]
    │ paquetes IP enrutados a tun0
    ▼
[Lectura del tunFd]
    │ paquete IP crudo
    ▼
[Userspace TCP/IP stack — smoltcp]
    │ reensambla, expone un "socket virtual"
    ▼
[Nuestro código pide payload del socket virtual]
    │ stream de bytes (capa 7)
    ▼
[Cliente SOCKS5 → handshake → CONNECT a destino]
    │
    ▼
[Servidor proxy (anfitrión)]
    │ socket real al destino real
    ▼
[Internet]
```

La respuesta hace el camino inverso, simétricamente.

## Implementación con `smoltcp`

`smoltcp` es una pila TCP/IP escrita en Rust, sin asignación dinámica obligatoria, que expone una API "polled". Adaptamos su API a tokio con un envoltorio.

### Estructura

```rust
// engine/crates/gravital-stack/src/lib.rs

use smoltcp::iface::{Config, Interface, SocketSet};
use smoltcp::socket::tcp;
use smoltcp::wire::{IpAddress, IpCidr};
use smoltcp::time::Instant;

pub struct UserspaceStack {
    iface: Interface,
    sockets: SocketSet<'static>,
    device: TunVirtualDevice,
    pending_connections: Mutex<Vec<TcpHandle>>,
}

impl UserspaceStack {
    pub fn new(mtu: u16) -> Self {
        let mut device = TunVirtualDevice::new(mtu);
        let mut config = Config::new(smoltcp::wire::HardwareAddress::Ip);
        config.random_seed = rand::random();
        
        let mut iface = Interface::new(config, &mut device, Instant::now());
        iface.update_ip_addrs(|addrs| {
            addrs.push(IpCidr::new(IpAddress::v4(10, 42, 0, 2), 32)).unwrap();
        });
        
        UserspaceStack {
            iface,
            sockets: SocketSet::new(vec![]),
            device,
            pending_connections: Mutex::new(Vec::new()),
        }
    }
    
    pub fn poll(&mut self) -> bool {
        self.iface.poll(Instant::now(), &mut self.device, &mut self.sockets)
    }
}
```

### El "device" que usa smoltcp

`smoltcp` espera un `Device` que sepa enviar y recibir frames. Le damos un puente entre canales mpsc y el TUN real:

```rust
pub struct TunVirtualDevice {
    rx_queue: mpsc::Receiver<BytesMut>,  // paquetes que vienen del TUN real
    tx_queue: mpsc::Sender<BytesMut>,    // paquetes que van al TUN real
    mtu: u16,
}
```

El loop principal del cliente alimenta `rx_queue` desde el TUN y consume `tx_queue` para escribir al TUN.

### La interceptación de conexiones

smoltcp nos permite registrar un *listener* virtual que captura intentos de conexión TCP entrantes (es decir, los SYN que el kernel del usuario envía). Por cada SYN:

1. smoltcp completa el handshake (SYN-ACK, ACK) actuando como destino.
2. Nos entrega un `TcpHandle` con el quintuple `(src_ip, src_port, dst_ip, dst_port, proto)`.
3. Nuestro código lanza una tarea: abrir SOCKS al proxy con `dst_ip:dst_port` como destino, y empezar relay bidireccional.

```rust
async fn handle_intercepted_connection(
    handle: SocketHandle,
    target: SocketAddr,
    socks_proxy: Arc<SocksProxyClient>,
) -> Result<()> {
    // Abrir conexión SOCKS al proxy con target como destino
    let upstream = socks_proxy.connect(target).await?;
    
    // Relay
    let (mut up_rd, mut up_wr) = upstream.into_split();
    
    let to_upstream = async {
        loop {
            let data = read_from_smoltcp(handle).await?;
            if data.is_empty() { break; }
            up_wr.write_all(&data).await?;
        }
        up_wr.shutdown().await
    };
    
    let from_upstream = async {
        let mut buf = [0u8; 8192];
        loop {
            let n = up_rd.read(&mut buf).await?;
            if n == 0 { break; }
            write_to_smoltcp(handle, &buf[..n]).await?;
        }
        close_smoltcp(handle).await
    };
    
    tokio::try_join!(to_upstream, from_upstream)?;
    Ok(())
}
```

### Los detalles que matan

La implementación correcta requiere atender:

1. **Window scaling**: smoltcp soporta ventanas TCP. Configurar buffer suficiente (`64 KiB` por dirección como mínimo) o el throughput sufrirá.
2. **Nagle's algorithm**: deshabilitar (`set_nagle_enabled(false)`) en sockets virtuales para baja latencia interactiva.
3. **MTU**: el MTU de smoltcp DEBE coincidir con el MTU del TUN. Si difieren, los paquetes se fragmentan o se pierden.
4. **Keepalives**: el kernel del usuario enviará TCP keepalives después de minutos de inactividad. smoltcp debe responderlos.
5. **RST handling**: si el upstream cierra abruptamente, propagar RST al socket virtual para que el kernel del usuario sepa.

## Manejo de UDP

UDP es más simple porque no hay reensamblado. Pero requiere mantener "asociaciones":

```rust
struct UdpAssociation {
    client_endpoint: SocketAddr,    // IP:puerto del usuario
    proxy_assoc: ProxyUdpAssoc,     // asociación SOCKS5 UDP o canal udpgw
    last_activity: Instant,
}

// Tabla
type UdpTable = Mutex<HashMap<SocketAddr, UdpAssociation>>;
```

Flujo:

1. Llega un paquete UDP del TUN. Parseamos `(src_ip, src_port, dst_ip, dst_port)`.
2. Buscamos asociación en la tabla con clave `(src_ip, src_port)`.
3. Si no existe, creamos una con el proxy. Soporte de `UDP ASSOCIATE` (SOCKS5) primero; si no está disponible, `udpgw`.
4. Reenviamos el datagrama al proxy.
5. Las respuestas del proxy se inyectan al TUN como paquetes UDP IP, con `src` y `dst` invertidos.

**Limpieza**: una tarea periódica recorre la tabla y elimina asociaciones inactivas por más de 5 minutos.

## Detección de bucle

Si por error algún paquete dirigido al proxy del anfitrión vuelve a entrar al TUN, tenemos un bucle. Causas posibles:

- Falla en `VpnService.protect()` del socket de salida.
- IP del proxy dentro del rango ruteado (raro pero posible si alguien configura mal).

**Detección**: si un paquete IP entrante tiene como destino la IP del proxy del anfitrión, lo marcamos como bucle, dropeamos, incrementamos métrica `loop_packets_dropped` y emitimos evento WARN.

## Performance: el costo de los context switches

El paquete cruza:
1. Hardware Wi-Fi → kernel.
2. Kernel → TUN (copia).
3. TUN → user space (lectura).
4. User space → smoltcp (procesamiento).
5. smoltcp → user space (callback).
6. User space → socket SOCKS (escritura).
7. Socket → kernel (escritura).
8. Kernel → hardware Wi-Fi.

Ocho cruces de capa por paquete saliente. Para tráfico de 25 Mbps con MTU 1280 son ~2400 paquetes/segundo. Cada cruce cuesta microsegundos. El total impacta latencia y batería.

**Mitigaciones aplicadas**:
- Reactor tokio multi-thread para no serializar.
- Batch de paquetes cuando es posible (leer N paquetes del TUN en cada wakeup).
- `AsyncFd` para integrar el TUN sin polling activo.
- Buffers preasignados (zero-alloc en hot path).

**Mitigación futura no MVP**: eBPF en Linux desktop. Saltarse pasos 4-6 dejando el procesamiento en el kernel.

## Tabla de errores comunes y su síntoma

| Error técnico | Síntoma para el usuario |
|---|---|
| MTU mal configurado | Páginas web cargan a medias, descargas truncadas |
| Bucle no detectado | App parece "trabajando" pero nada avanza, batería sube |
| smoltcp sin timer adecuado | Conexiones nuevas tardan ~1s extra (timeouts mal calculados) |
| `protect()` no aplicado | Loop infinito, app muere por OOM |
| DNS no interceptado | "No internet" pese a que conexión está activa |
| Ventana TCP demasiado pequeña | Throughput limitado a unas decenas de KB/s |
| UDP sin fallback | Llamadas WhatsApp/Zoom no conectan |

## Dependencias

```toml
[dependencies]
smoltcp = { version = "0.11", default-features = false, features = [
  "alloc",
  "medium-ip",
  "proto-ipv4", "proto-ipv6",
  "socket-tcp", "socket-udp",
  "async",
] }
tokio = { workspace = true }
bytes = { workspace = true }
parking_lot = { workspace = true }
tracing = { workspace = true }
```

## Tests

- **iperf3** entre dos teléfonos en LAN, vía Gravital Share. Throughput ≥ 25 Mbps en hardware mid-range.
- **Latencia**: ping ICMP a través del túnel (vía conversión a UDP especial). RTT en LAN < 25 ms.
- **Stress**: 200 conexiones concurrentes, ninguna se cae en 30 minutos.
- **Soak test**: 24 horas con tráfico mixto, sin leaks de memoria, sin crashes.
