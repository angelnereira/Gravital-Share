# 02.04 — Motor de Red (Network Engine)

## Por qué necesitamos una pila TCP/IP en espacio de usuario

El cliente recibe del kernel **paquetes IP crudos** (capa 3) por el FD del TUN. El servidor SOCKS5 al otro lado solo entiende **streams TCP** (capa 7). Hay un abismo que cubrir:

- Reensamblar segmentos TCP a partir de paquetes IP.
- Mantener números de secuencia, ventanas, retransmisiones, control de congestión.
- Manejar el handshake de tres vías y el cierre con FIN/RST.
- Hacer todo esto en miles de conexiones concurrentes con buen rendimiento y batería razonable.

Reescribir esto desde cero es trabajo de años. La industria usa `lwIP` (C) o pilas en Go (`gvisor/netstack`, `xjasonlyu/tun2socks`). En Rust elegimos **`smoltcp`** porque está diseñado para entornos embebidos, es `no_std` opcional, mantenible, y gana auditoría más fácil.

## Topología del flujo de paquetes

### Modo cliente (cuando los datos van del usuario hacia internet)

```
[App del usuario en B] 
       ↓ socket TCP
[Kernel de Android — write(socket, data)]
       ↓ stack TCP/IP del kernel
[Paquetes IP enrutados al TUN porque 0.0.0.0/0 → tun0]
       ↓ tunFd
[gravital-tun lee] → [gravital-stack alimenta a smoltcp]
       ↓ smoltcp ensambla y entrega a un socket virtual
[gravital-engine consume el socket virtual]
       ↓ payload (capa 7)
[gravital-socks abre conexión SOCKS5 al anfitrión, hace CONNECT]
       ↓ TCP real (socket protegido con VpnService.protect())
[wlan0 → hotspot del anfitrión 192.168.43.1:1080]
```

### Modo cliente (respuesta del anfitrión hacia el usuario)

```
[Anfitrión 192.168.43.1:1080 reenvía datos del internet]
       ↓ TCP por wlan0
[Kernel entrega bytes al socket que abrimos]
       ↓ leemos esos bytes en gravital-socks
[gravital-stack escribe bytes en el socket virtual]
       ↓ smoltcp segmenta, calcula checksums, etc.
[gravital-tun escribe paquetes IP en el tunFd]
       ↓ tunFd
[Kernel inyecta los paquetes IP en la pila del usuario]
       ↓ stack TCP del kernel
[App del usuario recibe los datos]
```

### Modo servidor (anfitrión)

```
[Cliente B envía SOCKS5 CONNECT al anfitrión]
       ↓ TCP entrante por wlan0/ap0 (hotspot)
[gravital-socks acepta y completa handshake]
       ↓ abre nuevo socket TCP al destino real (ej. www.example.com)
[ese socket es originado por la app gravital-share]
       ↓ kernel lo enruta por las reglas del usuario → tun0 (VPN existente)
[tráfico ya cifrado por la VPN sale por rmnet0]
       ↓
[internet]
```

**Punto clave**: en el anfitrión, `gravital-stack` y `gravital-tun` **no se usan**. Solo el servidor SOCKS5/HTTP. El kernel hace el resto del trabajo gracias al diseño de Android.

## Diseño interno del motor

### Tareas tokio principales (modo cliente)

```rust
async fn run_client(cfg: ClientConfig, tun_fd: RawFd) -> Result<()> {
    let tun = TunDevice::from_fd(tun_fd)?;
    let stack = UserspaceStack::new(cfg.mtu)?;
    let proxy = SocksProxyClient::connect(cfg.proxy_addr).await?;
    let dns = DnsInterceptor::new(cfg.dns_server);
    let metrics = Metrics::new();

    let (tun_rx, tun_tx) = tun.split();
    let (stack_in, stack_out) = stack.channels();

    tokio::try_join!(
        tun_to_stack(tun_rx, stack_in.clone(), metrics.clone()),
        stack_to_proxy(stack_out, proxy.clone(), dns.clone(), metrics.clone()),
        proxy_to_stack(proxy, stack_in, metrics.clone()),
        stack_to_tun(stack, tun_tx, metrics.clone()),
        telemetry_pump(metrics),
    )?;

    Ok(())
}
```

Cinco tareas. Cada una tiene una responsabilidad clara y se comunican por canales acotados.

### Tareas en modo servidor

```rust
async fn run_server(cfg: ServerConfig) -> Result<()> {
    let listener = TcpListener::bind(cfg.bind_addr).await?;
    let metrics = Metrics::new();

    loop {
        let (sock, peer) = listener.accept().await?;
        let metrics = metrics.clone();
        tokio::spawn(async move {
            handle_proxy_connection(sock, peer, metrics).await
        });
    }
}

async fn handle_proxy_connection(
    sock: TcpStream,
    peer: SocketAddr,
    metrics: Metrics,
) -> Result<()> {
    let kind = peek_protocol(&sock).await?;
    match kind {
        ProtoKind::Socks5 => handle_socks5(sock, peer, metrics).await,
        ProtoKind::HttpConnect => handle_http_connect(sock, peer, metrics).await,
    }
}
```

`peek_protocol` examina el primer byte: `0x05` → SOCKS5, ASCII (`C`, `G`, `P`...) → HTTP.

## Manejo de UDP

UDP es donde la mayoría de implementaciones fracasan. Plan:

1. **Detectar paquetes UDP entrantes en el TUN.** En particular DNS (puerto 53) y QUIC (53/443 según diseño).
2. **DNS** → enviar al `DnsInterceptor` que resuelve por su socket protegido y devuelve la respuesta IP.
3. **UDP general** → intentar `UDP ASSOCIATE` con el servidor SOCKS5. Si el servidor reporta no soporte, caer a `gravital-udpgw` (multiplexar UDP sobre TCP).

### Algoritmo de fallback de UDP

```
on_udp_packet(pkt):
    if pkt.dst_port == 53:
        dns_interceptor.handle(pkt)
        return

    assoc = udp_associations.get(pkt.src_addr_port)
    if assoc is None:
        if proxy.supports_udp_associate():
            assoc = proxy.create_udp_assoc(pkt.src_addr_port)
        else:
            assoc = udpgw.create_assoc(pkt.src_addr_port)
        udp_associations.insert(pkt.src_addr_port, assoc)

    assoc.send(pkt)
```

Las asociaciones tienen un TTL (default 5 minutos sin actividad). Se limpian periódicamente para no fugar memoria.

## Manejo de DNS sin fugas

Esta es una de nuestras promesas técnicas más fuertes. Diseño:

1. La interfaz TUN del cliente declara como DNS server el resolver configurado (default `1.1.1.1`).
2. El kernel envía las consultas DNS hacia ese IP, con destino UDP/53.
3. Como `0.0.0.0/0` está enrutado al TUN, esos paquetes llegan a nosotros.
4. `DnsInterceptor` los reconoce, los desencapsula, hace la resolución vía socket protegido con `VpnService.protect()`, y devuelve la respuesta IP de vuelta al cliente.
5. **Si la resolución falla**, **NO** se cae al resolver del operador. Se devuelve `SERVFAIL` al cliente local y se registra una métrica `dns.resolve.failed`.

**Test de no-fuga**:
- En CI: levantar emulador, configurar Gravital Share, capturar tráfico saliente con `tcpdump`, verificar que **ningún paquete UDP/53 sale por la interfaz celular**.
- Falso positivo: si el sistema operativo hace resoluciones por su cuenta para conectividad (`connectivitycheck.gstatic.com`), esas son tolerables siempre que vayan por nuestro túnel. La regla es: cero UDP/53 saliente fuera del túnel.

## Concurrencia

- Cada conexión TCP del usuario se maneja como una `tokio::task` ligera con state propio.
- Una sola tarea por dirección de canal (TUN→Stack, Stack→Proxy, Proxy→Stack, Stack→TUN).
- Comunicación con canales `mpsc` con `bounded(256)`. Si se llenan, se aplica backpressure subiendo por el grafo.

## Backpressure

La pieza más sutil del diseño: cuando el upstream (proxy) está saturado, no podemos seguir bombeando paquetes desde el TUN porque inundamos la memoria.

**Política**:
- El canal `tun_to_stack` tiene capacidad 256.
- Si se llena, `tun_reader` *no lee más del FD* hasta que haya espacio.
- El kernel verá su buffer interno llenarse y empezará a dropear paquetes IP, lo cual el TCP del usuario interpreta como pérdida de paquete y reduce su ventana.
- **Esto es exactamente el comportamiento que queremos**. Le devolvemos la presión a la fuente.

## Métricas internas (en hot path)

Contadores actualizados con `Relaxed`. Nunca bloquean.

```rust
struct Metrics {
    pkts_in_total: AtomicU64,
    pkts_out_total: AtomicU64,
    bytes_in_total: AtomicU64,
    bytes_out_total: AtomicU64,
    drops_buffer_full: AtomicU64,
    drops_parse_error: AtomicU64,
    drops_unsupported_proto: AtomicU64,
    udp_assocs_active: AtomicUsize,
    tcp_conns_active: AtomicUsize,
    dns_queries_total: AtomicU64,
    dns_failures_total: AtomicU64,
    socks_handshake_failures: AtomicU64,
    reconnections_total: AtomicU64,
}
```

Una tarea `telemetry_pump` toma snapshots cada 5s y los emite como evento JSON.

## Manejo del tiempo

- Usar `tokio::time::Instant` y `Duration`. Nunca `SystemTime` en hot path (puede saltar hacia atrás).
- Timestamps en logs: `chrono::Utc::now().to_rfc3339_opts(SecondsFormat::Millis, true)`.

## Cierre limpio

```rust
async fn shutdown(&self, timeout: Duration) -> Result<()> {
    // 1. Señal a todas las tareas
    self.shutdown_tx.send(()).ok();

    // 2. Cierre amable de conexiones SOCKS (FIN)
    self.proxy.close_all().await;

    // 3. Espera con timeout
    tokio::time::timeout(timeout, self.tasks_done.notified()).await?;

    // 4. Liberar el TUN (no cerrar si nos lo prestaron)
    drop(self.tun);

    Ok(())
}
```

## Consideraciones futuras

### eBPF en Linux/Desktop
Para la futura variante Linux, el data plane podría compilarse a eBPF y operar a nivel kernel, eliminando context-switches. La separación que ya tenemos hace esto factible: `gravital-proto` está suficientemente desacoplado como para portar sus parsers. Es trabajo de varios meses y queda fuera del MVP.

### QUIC
HTTP/3 sobre QUIC va a ser mayoría de tráfico web pronto. QUIC va sobre UDP. Si nuestro UDP funciona bien, QUIC funciona. Pero QUIC tiene 0-RTT y migración de conexión que pueden romperse al pasar por proxies. Vigilar y testear.

### IPv6
Soporte desde el día uno como ruta declarada (`::/0`), pero el motor procesa IPv6 como pasarela genérica. Si una conexión es IPv6 y el destino tiene IPv4 alternativa, preferimos IPv4 mientras los proxies tradicionales sigan en IPv4.
