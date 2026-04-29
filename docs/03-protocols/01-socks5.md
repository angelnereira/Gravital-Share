# 03.01 — Implementación de SOCKS5

## Referencias normativas

- **RFC 1928** — SOCKS Protocol Version 5 (especificación principal).
- **RFC 1929** — Username/Password Authentication for SOCKS V5.
- **RFC 1961** — GSS-API Authentication (no implementado, opcional futuro).

Implementamos SOCKS5 al pie del RFC, sin extensiones propietarias. Los clientes de terceros (Chrome con un PAC, configuración de proxy en consolas, apps que ya soportan SOCKS5) deben funcionar sin modificación.

## Anatomía del protocolo

SOCKS5 trabaja sobre TCP. La conexión cliente↔proxy pasa por tres fases:

```
1. Negociación de método
2. Solicitud (CONNECT, BIND, UDP ASSOCIATE)
3. Relay de datos
```

### Fase 1: Negociación de método

**Cliente → Servidor**:
```
+----+----------+----------+
|VER | NMETHODS | METHODS  |
+----+----------+----------+
| 1  |    1     |  1..255  |
+----+----------+----------+
```

- `VER = 0x05`
- `NMETHODS` = número de métodos ofrecidos
- `METHODS` = lista de identificadores de método

Métodos relevantes:

| Valor | Nombre | Soporte |
|---|---|---|
| 0x00 | NO AUTHENTICATION | ✅ Default |
| 0x02 | USERNAME/PASSWORD | ✅ Opcional |
| 0xFF | NO ACCEPTABLE METHODS | (respuesta del servidor) |

**Servidor → Cliente**:
```
+----+--------+
|VER | METHOD |
+----+--------+
| 1  |   1    |
+----+--------+
```

Si el servidor responde `0xFF`, el cliente cierra la conexión.

### Fase 2: Solicitud

**Cliente → Servidor** (después de la auth si aplica):
```
+----+-----+-------+------+----------+----------+
|VER | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT |
+----+-----+-------+------+----------+----------+
| 1  |  1  | X'00' |  1   | Variable |    2     |
+----+-----+-------+------+----------+----------+
```

`CMD`:
- `0x01` = CONNECT (TCP)
- `0x02` = BIND (raramente usado, no implementado en MVP)
- `0x03` = UDP ASSOCIATE

`ATYP`:
- `0x01` = IPv4 (4 bytes)
- `0x03` = nombre de dominio (1 byte longitud + N bytes)
- `0x04` = IPv6 (16 bytes)

`DST.PORT` en network byte order (big-endian).

**Servidor → Cliente** (respuesta a la solicitud):
```
+----+-----+-------+------+----------+----------+
|VER | REP |  RSV  | ATYP | BND.ADDR | BND.PORT |
+----+-----+-------+------+----------+----------+
```

`REP`:
- `0x00` = succeeded
- `0x01` = general SOCKS server failure
- `0x02` = connection not allowed by ruleset
- `0x03` = network unreachable
- `0x04` = host unreachable
- `0x05` = connection refused
- `0x06` = TTL expired
- `0x07` = command not supported
- `0x08` = address type not supported

`BND.ADDR` y `BND.PORT` para CONNECT son la dirección+puerto que el servidor usa al conectarse al destino. Para UDP ASSOCIATE, son la dirección+puerto donde el cliente debe enviar sus datagramas.

## Implementación servidor (en el anfitrión)

```rust
// engine/crates/gravital-socks/src/server.rs

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use crate::error::SocksError;

pub async fn handle_connection(mut sock: TcpStream) -> Result<(), SocksError> {
    // Fase 1: negociación
    let method = negotiate_method(&mut sock).await?;
    
    // Fase 1.5: auth si aplica
    if method == 0x02 {
        authenticate_userpass(&mut sock).await?;
    }
    
    // Fase 2: solicitud
    let req = read_request(&mut sock).await?;
    
    match req.cmd {
        Cmd::Connect => handle_connect(sock, req).await,
        Cmd::UdpAssociate => handle_udp_associate(sock, req).await,
        Cmd::Bind => {
            send_reply(&mut sock, Rep::CommandNotSupported, &SocketAddr::default()).await?;
            Ok(())
        }
    }
}

async fn negotiate_method(sock: &mut TcpStream) -> Result<u8, SocksError> {
    let mut header = [0u8; 2];
    sock.read_exact(&mut header).await?;
    if header[0] != 0x05 {
        return Err(SocksError::InvalidVersion(header[0]));
    }
    let nmethods = header[1] as usize;
    let mut methods = vec![0u8; nmethods];
    sock.read_exact(&mut methods).await?;
    
    let chosen = if methods.contains(&0x00) {
        0x00
    } else if methods.contains(&0x02) && config().auth_enabled {
        0x02
    } else {
        sock.write_all(&[0x05, 0xFF]).await?;
        return Err(SocksError::NoAcceptableMethod);
    };
    
    sock.write_all(&[0x05, chosen]).await?;
    Ok(chosen)
}

async fn handle_connect(mut client: TcpStream, req: Request) -> Result<(), SocksError> {
    let target = match req.addr {
        AddrSpec::Ipv4(ip) => SocketAddr::new(IpAddr::V4(ip), req.port),
        AddrSpec::Ipv6(ip) => SocketAddr::new(IpAddr::V6(ip), req.port),
        AddrSpec::Domain(host) => {
            // resolución DNS
            tokio::net::lookup_host((host.as_str(), req.port))
                .await?
                .next()
                .ok_or(SocksError::HostUnreachable)?
        }
    };
    
    let upstream = match TcpStream::connect(target).await {
        Ok(s) => s,
        Err(e) => {
            let rep = match e.kind() {
                io::ErrorKind::ConnectionRefused => Rep::ConnectionRefused,
                io::ErrorKind::TimedOut => Rep::TtlExpired,
                _ => Rep::HostUnreachable,
            };
            send_reply(&mut client, rep, &SocketAddr::from(([0,0,0,0], 0))).await?;
            return Err(SocksError::Upstream(e));
        }
    };
    
    let local = upstream.local_addr()?;
    send_reply(&mut client, Rep::Succeeded, &local).await?;
    
    // Fase 3: relay
    relay_bidirectional(client, upstream).await
}

async fn relay_bidirectional(a: TcpStream, b: TcpStream) -> Result<(), SocksError> {
    let (mut a_read, mut a_write) = a.into_split();
    let (mut b_read, mut b_write) = b.into_split();
    
    let to_b = tokio::spawn(async move {
        tokio::io::copy(&mut a_read, &mut b_write).await
    });
    let to_a = tokio::spawn(async move {
        tokio::io::copy(&mut b_read, &mut a_write).await
    });
    
    let _ = tokio::try_join!(to_b, to_a);
    Ok(())
}
```

## Implementación de UDP ASSOCIATE

Cuando el cliente solicita `CMD=0x03 (UDP ASSOCIATE)`:

1. El servidor reserva un socket UDP local en un puerto aleatorio.
2. Responde al cliente con `BND.ADDR:BND.PORT` apuntando a ese socket.
3. El cliente envía datagramas UDP a ese endpoint, **encapsulados** así:

```
+----+------+------+----------+----------+----------+
|RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
+----+------+------+----------+----------+----------+
| 2  |  1   |  1   | Variable |    2     | Variable |
+----+------+------+----------+----------+----------+
```

- `RSV` = `0x0000`
- `FRAG` = fragment number, `0x00` = standalone (no fragmentación)

4. El servidor desencapsula y reenvía el datagrama al destino real.
5. Las respuestas se encapsulan de la misma forma y se envían de vuelta al cliente.

**Implementación**:

```rust
async fn handle_udp_associate(mut client_tcp: TcpStream, _req: Request) -> Result<(), SocksError> {
    let udp_sock = tokio::net::UdpSocket::bind("0.0.0.0:0").await?;
    let local = udp_sock.local_addr()?;
    
    send_reply(&mut client_tcp, Rep::Succeeded, &local).await?;
    
    // Mantener vivo mientras la conexión TCP de control esté viva.
    // Cuando el cliente cierra el TCP, cerramos el UDP.
    let udp = Arc::new(udp_sock);
    let stop = Arc::new(AtomicBool::new(false));
    let stop2 = stop.clone();
    let udp2 = udp.clone();
    
    let relay_task = tokio::spawn(async move {
        relay_udp(udp2, stop2).await
    });
    
    // Bloqueamos esperando a que el cliente cierre el TCP de control
    let mut buf = [0u8; 1];
    let _ = client_tcp.read(&mut buf).await; // se desbloquea al EOF/RST
    stop.store(true, Ordering::SeqCst);
    let _ = relay_task.await;
    
    Ok(())
}

async fn relay_udp(udp: Arc<UdpSocket>, stop: Arc<AtomicBool>) -> Result<(), SocksError> {
    let mut buf = [0u8; 65535];
    let mut associations: HashMap<SocketAddr, SocketAddr> = HashMap::new();
    
    while !stop.load(Ordering::SeqCst) {
        let (n, peer) = udp.recv_from(&mut buf).await?;
        let (target, payload) = parse_udp_request(&buf[..n])?;
        
        let target_sock = SocketAddr::from((target.ip, target.port));
        associations.insert(target_sock, peer);
        
        let upstream = UdpSocket::bind("0.0.0.0:0").await?;
        upstream.connect(target_sock).await?;
        upstream.send(payload).await?;
        
        // ... lógica para esperar respuesta y reenviarla al peer encapsulada
    }
    
    Ok(())
}
```

(En la implementación real, las asociaciones se gestionan con tareas independientes y un `JoinSet` para limpieza.)

## Implementación cliente (en el dispositivo cliente)

El cliente Gravital Share habla SOCKS5 contra el anfitrión. La lógica es simétrica:

```rust
pub async fn connect_via_socks(
    proxy: SocketAddr,
    target: TargetAddr,
) -> Result<TcpStream, SocksError> {
    let mut sock = TcpStream::connect(proxy).await?;
    
    // Fase 1
    sock.write_all(&[0x05, 0x01, 0x00]).await?;  // ofrecemos solo NO AUTH
    let mut resp = [0u8; 2];
    sock.read_exact(&mut resp).await?;
    if resp[1] != 0x00 {
        return Err(SocksError::AuthRejected);
    }
    
    // Fase 2
    let mut req = vec![0x05, 0x01, 0x00];
    encode_address(&target, &mut req);
    sock.write_all(&req).await?;
    
    let mut hdr = [0u8; 4];
    sock.read_exact(&mut hdr).await?;
    if hdr[1] != 0x00 {
        return Err(SocksError::from_rep(hdr[1]));
    }
    
    // saltar BND.ADDR + BND.PORT
    skip_bound_address(&mut sock, hdr[3]).await?;
    
    Ok(sock)
}
```

## Casos de error críticos

### El servidor no soporta UDP ASSOCIATE

Algunos proxies SOCKS5 simples solo implementan CONNECT. Si el cliente recibe `Rep::CommandNotSupported` para UDP ASSOCIATE:

1. Marcar al servidor como "TCP-only" en `ServerCapabilities`.
2. Caer al modo `udpgw` (multiplexor UDP-sobre-TCP).

### Auth rechazada

Mostrar al usuario un error claro y específico ("credenciales del proxy incorrectas"), no un código.

### Timeout durante CONNECT

Si el servidor no responde en 30s al `CONNECT`, abortar y reintentar (con backoff). Es probable que la VPN del anfitrión esté caída; el usuario lo verá como `Reconnecting`.

## Tests

- **Vectores conocidos del RFC**: handshake completo paso a paso.
- **Servidor de eco** simple para integración: `bash` + `socat -u TCP-LISTEN:..., FORK SOCKS4A:...`.
- **Cliente vs nuestro servidor**: cliente Chrome configurado contra `localhost:1080`.
- **Stress**: 100 conexiones simultáneas, medición de latencia.
- **Fuzzing del parser de Request**: `cargo-fuzz` corpus.

## Lo que no implementamos en MVP

- ❌ GSS-API (auth empresarial).
- ❌ BIND (relevante para FTP activo, irrelevante hoy).
- ❌ SOCKS4/4a (legacy; sí soportamos HTTP CONNECT como fallback simple).
