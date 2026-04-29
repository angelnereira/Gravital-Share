# 03.02 — Túnel HTTP CONNECT

## Por qué lo soportamos

Muchos clientes terceros — consolas (PlayStation, Xbox), Smart TVs, routers, configuración de proxy nativa de Windows/macOS — soportan **proxy HTTP**, no SOCKS. Soportar HTTP CONNECT amplía la compatibilidad sin coste técnico significativo.

## Especificación

El método `CONNECT` está definido en **RFC 7231 §4.3.6** (y heredado de RFC 2817).

### Solicitud del cliente

```
CONNECT www.ejemplo.com:443 HTTP/1.1
Host: www.ejemplo.com:443
Proxy-Authorization: Basic <base64(usuario:contraseña)>
User-Agent: <opcional>

```

(Una línea en blanco final para terminar los headers.)

### Respuesta del servidor (éxito)

```
HTTP/1.1 200 Connection established

```

A partir de ese momento, la conexión TCP es transparente: bytes en bruto en ambas direcciones.

### Respuesta del servidor (error)

```
HTTP/1.1 502 Bad Gateway
Content-Length: 0
Connection: close

```

Códigos relevantes:

| Código | Significado |
|---|---|
| 200 | Conexión establecida |
| 400 | Petición malformada |
| 401 | Autenticación requerida |
| 403 | Prohibido por política |
| 502 | El servidor proxy no pudo alcanzar el destino |
| 504 | Timeout al conectar al destino |

## Implementación servidor

```rust
// engine/crates/gravital-http/src/server.rs

use httparse::{Request, EMPTY_HEADER, Status};

pub async fn handle_http_proxy(mut sock: TcpStream) -> Result<(), HttpProxyError> {
    let mut buf = [0u8; 8192];
    let mut filled = 0;
    
    // leer hasta encontrar "\r\n\r\n"
    let req_len = loop {
        let n = sock.read(&mut buf[filled..]).await?;
        if n == 0 { return Err(HttpProxyError::PrematureEof); }
        filled += n;
        let mut headers = [EMPTY_HEADER; 32];
        let mut req = Request::new(&mut headers);
        match req.parse(&buf[..filled])? {
            Status::Complete(len) => break len,
            Status::Partial => {
                if filled >= buf.len() {
                    return Err(HttpProxyError::HeadersTooLarge);
                }
            }
        }
    };
    
    // Parsear de nuevo para acceso a fields
    let mut headers = [EMPTY_HEADER; 32];
    let mut req = Request::new(&mut headers);
    req.parse(&buf[..filled])?;
    
    if req.method != Some("CONNECT") {
        send_error(&mut sock, 405, "Method Not Allowed").await?;
        return Err(HttpProxyError::UnsupportedMethod);
    }
    
    let target = req.path.ok_or(HttpProxyError::MissingTarget)?;
    let (host, port) = parse_host_port(target)?;
    
    // Auth si aplica
    if config().http_auth_enabled {
        verify_proxy_auth(&headers)?;
    }
    
    // Conectar al destino real
    let upstream = match TcpStream::connect((host.as_str(), port)).await {
        Ok(s) => s,
        Err(_) => {
            send_error(&mut sock, 502, "Bad Gateway").await?;
            return Err(HttpProxyError::UpstreamFailed);
        }
    };
    
    sock.write_all(b"HTTP/1.1 200 Connection established\r\n\r\n").await?;
    
    // Si quedó payload tras los headers (raro pero válido), reenviar
    if filled > req_len {
        upstream_write_all(&upstream, &buf[req_len..filled]).await?;
    }
    
    relay_bidirectional(sock, upstream).await
}
```

**Importante**: si el cliente envía datos antes de recibir `200`, esos datos vienen pegados al final del header. Hay que reenviarlos al destino.

## Implementación cliente

Para clientes terceros (configuración manual de proxy en SmartTV o PS5), nosotros somos el servidor. Nuestro cliente Android/Windows habla preferentemente SOCKS5.

Sin embargo, en escenarios donde el anfitrión está detrás de otro proxy HTTP corporativo, podemos hacer un *proxy chaining* (no en MVP).

## Headers que el servidor ignora

Conscientemente:
- `Proxy-Connection`, `Connection` — el túnel siempre es persistente.
- `Keep-Alive` — irrelevante.
- `User-Agent` — registramos en logs (con flag de privacidad), no afecta routing.

## Headers que el servidor procesa

- `Host` — debe coincidir con el target del CONNECT (validación opcional).
- `Proxy-Authorization` — si auth habilitada.
- `X-Gravital-Trace-Id` — propio, opcional, propagamos en telemetría.

## Anti-patrones que NO implementamos

Algunos clientes "VPN sobre HTTP" usan **payloads inyectados** para evadir DPI o aprovechar exenciones de cobro de operadores (zero-rating). Ejemplos:

```
CONNECT bing.com:443 HTTP/1.1
X-Online-Host: target.real.com
Host: bing.com
```

Esto es una técnica de los productos hermanos de New Tools Works (`SocksIP Tunnel`) para evadir censura. **Gravital Share no la implementa**. No es nuestro mercado, no es nuestra promesa. Hacerlo nos volvería un objetivo regulatorio para el cual no estamos diseñados.

Si en algún momento se necesita ofuscación contra DPI, esa funcionalidad iría en un producto separado del ecosistema (`Gravital Tunnel`), no aquí.

## Soporte UDP

HTTP CONNECT es **TCP-only**. Los clientes que se conecten vía HTTP CONNECT no obtendrán UDP relay. Esto es una limitación documentada del modo HTTP.

Para UDP, los clientes deben hablar SOCKS5 contra nosotros.

## Tests

- Cliente `curl`:
  ```bash
  curl -x http://192.168.43.1:8080 https://example.com
  ```
  Debe funcionar tras `CONNECT`.

- Cliente Chrome con proxy HTTP configurado.
- Smart TV en banco de pruebas (cualquier modelo Samsung/LG con proxy nativo).

## Métricas dedicadas

- `http_connect.requests_total`
- `http_connect.success_total`
- `http_connect.errors_<code>_total`
- `http_connect.handshake_p50_ms` / `p95_ms`
