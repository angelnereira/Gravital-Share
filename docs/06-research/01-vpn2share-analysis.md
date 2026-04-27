# 06.01 — Análisis del Estado del Arte: VPN2Share

> **Naturaleza de este documento:** este es un **registro de investigación**, no especificación viva del producto. Sintetiza el análisis técnico de VPN2Share (la referencia de mercado existente) para entender qué resuelve, cómo lo resuelve, y dónde sus limitaciones definen el espacio donde Gravital Share aporta valor diferencial.

> No se modifica salvo para corregir errores fácticos. Las decisiones de diseño que dependen de este análisis viven en `docs/02-architecture/*` y `docs/03-protocols/*`.

## 1. El problema base que ambas herramientas resuelven

Android, por diseño del kernel, **separa estrictamente el tráfico de aplicaciones locales del tráfico que llega vía Hotspot**. Cuando un dispositivo establece una conexión VPN, el sistema crea una interfaz virtual `tun0` y modifica la tabla de enrutamiento para que solo el tráfico **local** pase por el túnel. El tráfico que entra desde dispositivos conectados al Hotspot —vía interfaces como `wlan0`, `ap0`, o un puente virtual— evita el túnel y sale directamente por `rmnet0` (datos móviles del operador).

La ruta tradicional para forzar el tethering sobre VPN es **acceso root + `iptables`**: insertar reglas en las cadenas `PREROUTING`/`POSTROUTING` de la tabla NAT para redirigir el tráfico del tethering hacia `tun0`. Esto requiere superusuario, lo que en Android moderno es marginal: pocos usuarios rootean, los OEMs lo desincentivan, las apps bancarias detectan root y se rehúsan a operar.

VPN2Share, desarrollada por New Tools Works (Carlos Martin Grijalva Coxic, William Thomson, Guatemala), resuelve el problema **sin root** usando una jugada arquitectónica clave: **operar en la Capa 7 del modelo OSI en vez de Capa 3**. En vez de modificar el kernel, instala un servidor proxy local en el espacio de usuario y orquesta interfaces virtuales TUN del lado cliente. Esto es exactamente lo que Gravital Share hace, pero rediseñado desde cero con prioridades distintas.

## 2. Perfil técnico de VPN2Share

| Atributo | Valor |
|----------|-------|
| Tamaño APK | 16.49–17.5 MB |
| Descargas | >440 000 |
| Calificación promedio | 4.13 / 5 |
| Min Android | 5.0 (Lollipop) |
| Permisos | ~15 del sistema |
| Modo Servidor | Dispositivo A (anfitrión con VPN primaria) |
| Modo Cliente | Dispositivo B (consume el túnel compartido) |
| Funciones extra | Interfaz web local para transferencia de archivos |

La aplicación complementa un ecosistema más amplio del mismo equipo: **SocksIP Tunnel**, **Tun2TAP**, **LinkLayer VPN**, **RevSSL** — herramientas centradas en establecer la conexión ofuscada inicial. VPN2Share solo cumple el rol de **retransmisión** de un túnel ya cifrado a otros dispositivos en LAN.

## 3. Arquitectura del Modo Servidor (Dispositivo A)

El truco fundamental: **descartar `iptables` y abrir un servidor proxy en espacio de usuario**.

### Bind a la interfaz del Hotspot

Cuando se activa el Hotspot en Android (hasta 8 Oreo y con variaciones en 9+), el dispositivo asume IP estática o altamente predecible en la interfaz LAN inalámbrica: típicamente `192.168.43.1/24`. VPN2Share abre un `ServerSocket` con `bind()` a esa IP (o a `0.0.0.0`) en un puerto del proxy:

| Puerto | Protocolo |
|--------|-----------|
| 1080 | SOCKS5 |
| 8080 | HTTP CONNECT |

### La paradoja del enrutamiento en Capa 7

El cliente B no envía paquetes a la IP de destino real de internet. **Envía a `192.168.43.1:1080` (el proxy local).** Esto es crítico:

1. El paquete llega al servidor proxy de VPN2Share corriendo en el Dispositivo A.
2. El proxy lo decodifica (Capa 7) y abre **una nueva conexión local** desde el propio Dispositivo A hacia el destino real de internet.
3. Como esta nueva conexión es originada por una **app local del Dispositivo A**, el kernel la clasifica como tráfico local.
4. **Se aplica la regla de enrutamiento estándar del Dispositivo A** — y esa regla dice "tráfico local va por `tun0` (la VPN primaria)".
5. Resultado: el tráfico del cliente B termina cifrado por la VPN del anfitrión, **sin tocar `iptables`**.

Es elegante. Y es exactamente el mismo patrón que Gravital Share replica.

## 4. Arquitectura del Modo Cliente (Dispositivo B)

### Intercepción con `VpnService`

`VpnService` (introducida en Android 4.0 / API 14) permite a una app crear una interfaz virtual TUN sin root. VPN2Share usa `VpnService.Builder` con configuración aproximada:

```
addAddress("10.0.0.2", 32)
addRoute("0.0.0.0", 0)         // captura todo IPv4
addRoute("::", 0)              // captura todo IPv6
addDnsServer("8.8.8.8")
setMtu(1280)                   // crítico
establish()
```

`establish()` devuelve un `ParcelFileDescriptor`. Todo el tráfico de aplicaciones del Dispositivo B se enruta hacia ese FD por el kernel.

### MTU = 1280: el detalle no obvio

La MTU de 1280 bytes es **deliberada**. Las redes celulares frecuentemente imponen Path MTU restringidos por sobrecarga del operador. Si el cliente usa MTU 1500 estándar, los paquetes encapsulados en la VPN del anfitrión (con su overhead de cabeceras) exceden el MTU del operador, se fragmentan, y muchas redes celulares **descartan fragmentos silenciosamente**. Resultado: la red "no carga" sin error visible.

> Gravital Share adopta la misma decisión por la misma razón. Es una constante derivada del entorno físico, no un valor configurable casualmente.

### Prevención del bucle infinito

Sin protección, esto pasa: el cliente intenta conectar al proxy `192.168.43.1:1080`, pero **ese socket también es interceptado por la propia ruta `0.0.0.0/0` que el cliente acaba de instalar**. El paquete vuelve al TUN, que vuelve al cliente, que abre otro socket al proxy... infinito.

La solución de Android es `VpnService.protect(socket)`: marca un socket como **exento de las reglas del túnel VPN**. El socket protegido sale por la interfaz física Wi-Fi al Hotspot directamente.

> Gravital Share: el handler de FFI invoca `protect()` en cada socket que abre el motor Rust hacia el proxy del anfitrión. Sin esto, no hay producto.

## 5. El núcleo: Tun2Socks y el problema de capas

Aquí está el hueso técnico real. La API `VpnService` entrega **paquetes IP crudos de Capa 3** (con headers IP, TCP/UDP, checksums). El servidor proxy del anfitrión espera **streams TCP de Capa 7** (con handshake SOCKS5 negociado, sin headers IP visibles).

Las capas son **incompatibles directamente**. Hay que traducir.

La solución estandarizada es **una pila TCP/IP corriendo en espacio de usuario**: lwIP (Lightweight IP) o equivalente. La pila:

1. Recibe paquetes IP crudos del FD del TUN.
2. Reensambla segmentos TCP, gestiona números de secuencia, ventanas, ACKs.
3. Expone "sockets virtuales" como si fueran sockets normales.
4. La aplicación lee del socket virtual los bytes de payload limpios.
5. Esos bytes se envían vía SOCKS5 al proxy real.
6. La respuesta hace el viaje inverso: el proxy entrega bytes, la pila los segmenta en paquetes IP, los escribe al FD del TUN, el kernel los entrega a la app del usuario.

VPN2Share usa el linaje `tun2socks` derivado del proyecto **badvpn**, comúnmente con versiones de **go-tun2socks** integradas vía `gomobile bind` (genera `.aar` invocable desde Kotlin/Java).

> **Decisión divergente de Gravital Share:** en vez de Go + `gomobile`, usamos **Rust + `smoltcp`** y exponemos `cdylib` consumible vía JNI. Razones: control de memoria y allocations más estricto, footprint binario menor, ergonomía de error handling, y alineamiento con el resto del stack Gravital. Misma idea topológica, distinto material.

## 6. UDP: el dolor crónico

SOCKS5 incluye `UDP ASSOCIATE` para encapsular UDP, pero **muchos servidores proxy no lo implementan correctamente**. Cuando el proxy upstream falla en UDP:

- Videollamadas degradan a TCP (o no funcionan).
- VoIP pierde paquetes.
- Juegos online: lag inaceptable.
- **DNS (UDP/53) se rompe silenciosamente.**

El último punto es el más grave. Sin DNS funcional, el cliente "no carga internet" pero los pings funcionan.

La solución del ecosistema badvpn es **`badvpn-udpgw`**: un demonio que multiplexa datagramas UDP sobre una conexión TCP confiable hacia el servidor, y el servidor desmultiplexa al destino UDP real. Es un workaround pero funciona.

Alternativa: encapsular paquetes IP completos dentro de un túnel UDP dedicado (lo que hacen WireGuard/OpenVPN). Pero requiere servidor cooperante, lo cual rompe la compatibilidad con SOCKS5 puro.

> **Gravital Share: ambos caminos.** Si el upstream soporta `UDP ASSOCIATE` correcto, lo usa. Si no, fallback automático a un canal `udpgw`-compatible. Esto se documenta en [`docs/03-protocols/01-socks5.md`](../03-protocols/01-socks5.md) y [`docs/02-architecture/04-network-engine.md`](../02-architecture/04-network-engine.md).

## 7. Cliente Windows: TAP-Windows vs Wintun

VPN2Share permite que Windows se conecte como cliente. Windows no tiene `VpnService`, así que la app instala un **adaptador de red virtual** vía driver de modo kernel.

### TAP-Windows (legacy, de OpenVPN)

- Opera en **Capa 2** del modelo OSI.
- Emula tarjeta Ethernet completa: ARP, MAC, frames Ethernet.
- 14 bytes de overhead por paquete (Ethernet header).
- Transporta broadcasts inútilmente.
- Compatible con bridging.
- **Throughput limitado**, latencia añadida significativa.

### Wintun (moderno, de WireGuard LLC)

- Opera en **Capa 3** estricta.
- Solo paquetes IP. Sin MAC, sin ARP, sin frames Ethernet.
- Overhead mínimo.
- Sin bridging, pero sin broadcasts.
- Driver firmado por Microsoft a través de WireGuard LLC.
- **Throughput drásticamente mayor**, menor uso de CPU del kernel.

> **Gravital Share: Wintun, sin discusión.** Para un túnel punto-a-punto a internet, las "limitaciones" de Wintun son irrelevantes. El throughput y la estabilidad son lo que importa. Detalle en [`docs/02-architecture/06-windows-client.md`](../02-architecture/06-windows-client.md).

## 8. SOCKS5 según RFC 1928 — anatomía de los paquetes

VPN2Share implementa SOCKS5 según la spec exacta. Resumen del intercambio:

### Fase 1: Negociación

Cliente → servidor:
```
0x05 [N] [METHOD_1] [METHOD_2] ... [METHOD_N]
```

Servidor → cliente:
```
0x05 [METHOD_SELECTED]    // 0x00 = sin auth, 0xFF = ningún método aceptable
```

### Fase 2: Solicitud de conexión

Cliente → servidor:
```
0x05 [CMD] 0x00 [ATYP] [DST_ADDR] [DST_PORT_BE]
```

| Campo | Valores |
|-------|---------|
| `CMD` | `0x01` CONNECT, `0x02` BIND, `0x03` UDP ASSOCIATE |
| `ATYP` | `0x01` IPv4 (4 bytes), `0x03` FQDN (1 byte len + N), `0x04` IPv6 (16 bytes) |
| `DST_PORT` | 2 bytes big-endian |

Servidor → cliente:
```
0x05 [REP] 0x00 [ATYP] [BND_ADDR] [BND_PORT_BE]
```

`REP=0x00` significa éxito. Otros códigos: `0x01` general failure, `0x03` network unreachable, `0x04` host unreachable, `0x05` connection refused, etc.

Después de este intercambio, **el servidor es un puente ciego de bytes**. Ya no inspecciona, ya no modifica.

> Implementación rigurosa de Gravital Share: [`docs/03-protocols/01-socks5.md`](../03-protocols/01-socks5.md).

## 9. HTTP CONNECT y los payloads "creativos"

Paralelo a SOCKS, VPN2Share soporta HTTP CONNECT. Estructura básica:

```
CONNECT host:port HTTP/1.1
Host: host:port
Connection: Keep-Alive

```

El ecosistema de la comunidad de "internet libre" frecuentemente añade encabezados creativos: `X-Online-Host`, `X-Forwarded-For`, upgrade a WebSocket, domain fronting con SNI manipulado, etc. Esto se hace **principalmente para evadir DPI de operadores y aprovechar zero-rating** (engañar al sistema de facturación del ISP haciéndole creer que el tráfico va a un destino "gratis").

> **Posición de Gravital Share:** este producto **no inyecta payloads ofuscados ni hace SNI spoofing**. La VPN primaria del usuario ya cifra el tráfico y resuelve la evasión de DPI si la requiere. Gravital Share es relé, no túnel ofuscado. La ofuscación es responsabilidad del producto hermano (Gravital Tunnel, futuro), si llega a existir. Detalles en [`docs/03-protocols/02-http-connect.md`](../03-protocols/02-http-connect.md).

## 10. Limitaciones reportadas de VPN2Share

Estas son las debilidades documentadas en reseñas, foros, y testing empírico. Cada una es una oportunidad de Gravital Share.

### 10.1 Race conditions y bloqueo asimétrico cliente-Windows-vs-Android

Reportes de usuarios indican que si un cliente Windows se conecta primero y abre múltiples conexiones TCP en estado Keep-Alive, **clientes Android subsiguientes son rechazados o sufren timeouts severos**. Esto sugiere:

- Pool de hilos del `ServerSocket` insuficiente.
- Posible alcance de límites `ulimit` de fd que Android impone a apps no-root.
- Falta de fairness en el scheduler del proxy.

**Gravital Share:** modelo `tokio` con runtime explícito y pool dimensionado, fairness garantizada por scheduler de Rust, métricas de fd activos expuestas a la UI.

### 10.2 Fugas de DNS en condiciones de fallo de UDP

Si UDP no fluye correctamente por el proxy, el cliente Android **a veces resuelve DNS por la interfaz física directamente**, exponiendo el historial de dominios al ISP del anfitrión.

**Gravital Share:** la sonda canaria post-`Connected` verifica explícitamente que la resolución DNS sale por el túnel. Si no, la sesión transiciona a `Failed` con `LeakDetected`. Detalle en [`docs/02-architecture/04-network-engine.md`](../02-architecture/04-network-engine.md).

### 10.3 Overhead de batería intenso

El context switching entre kernel space y user space (TUN → lwIP en user space → SOCKS5 → kernel → red) ocurre **muchas veces por paquete**. En soak tests se reporta drenaje de batería significativo en ambos extremos.

**Gravital Share:** la arquitectura no elimina el costo (sin root, no se puede), pero lo minimiza con:
- `smoltcp` con allocations bajas, pool de buffers reutilizable.
- Procesamiento en batch cuando hay backpressure, no per-packet.
- Métricas expuestas para que el usuario vea el costo y decida.
- Diseño abierto a un futuro path eBPF en Linux/Desktop donde el kernel sí se puede tocar.

### 10.4 Configuración manual en Smart TVs y consolas

Para dispositivos sin soporte de proxy nativo, los usuarios tienen que **poner la IP del Hotspot como DNS manualmente** y rezar. Es un workaround feo.

**Gravital Share:** el roadmap incluye un modo "DNS-only proxy mode" en el servidor donde el túnel se enchufa al consumidor sin que tenga app cliente. Detalle en [`docs/07-roadmap/02-roadmap.md`](../07-roadmap/02-roadmap.md).

### 10.5 IP del Hotspot variable en Android 9+

En versiones recientes de Android, la IP del Hotspot **no siempre es `192.168.43.1`**. Algunos OEMs cambian la subred. Una app que asume la IP fija falla silenciosamente.

**Gravital Share:** detección dinámica de la IP de la interfaz del Hotspot vía `ConnectivityManager` y `NetworkInterface`. Si no se detecta, la app se rehúsa a arrancar el modo servidor con error claro, no falla silenciosamente.

## 11. Lo que VPN2Share hace bien (y Gravital Share preserva)

No todo es crítica. VPN2Share resolvió un problema duro y su existencia valida el espacio. Lo que se preserva:

- La idea fundamental: **proxy en Capa 7 + TUN cliente con `protect()`**. Es la jugada correcta.
- MTU 1280 como default.
- Soporte simultáneo de SOCKS5 y HTTP CONNECT.
- `UDP ASSOCIATE` con fallback a `udpgw`.
- Cliente Windows con adaptador virtual moderno (Wintun).
- Cero requerimiento de root.

## 12. Lo que Gravital Share hace distinto

| Eje | VPN2Share | Gravital Share |
|-----|-----------|----------------|
| Lenguaje del data plane | Go (vía `gomobile`) o C/C++ | **Rust** |
| Stack TCP en user space | lwIP / go-tun2socks | **`smoltcp`** |
| Modelo de concurrencia servidor | Hilos por cliente | **`tokio` con fair scheduling** |
| Protección DNS | Implícita | **Sonda canaria activa post-Connected** |
| Detección de fugas | Manual / por usuario | **Estructural, transición a `Failed`** |
| Estado de la sesión | Implícito | **State machine explícita y observable** |
| Telemetría | Logs ad-hoc | **JSON estructurado + endpoint MCP local** |
| FFI | JNI a Go bindings | **JNI a Rust `cdylib` con `catch_unwind`** |
| Postura ante panics | Crash del proceso | **Recovery explícito vía catch_unwind** |
| Postura de código | Pragmática | **`unsafe_op_in_unsafe_fn` denegado, sin `unwrap`** |
| UI | Funcional, visualmente cargada | **Minimalista, alineada a infraestructura SaaS de gama alta** |
| Identidad del usuario | App-local | **Visa Económica Digital de Gravital ID (opcional, B2B)** |
| Distribución | Play Store + APK directo | **Play Store + sideload firmado, F-Droid en evaluación** |
| Telemetría externa | Posible (compartida con terceros declarado) | **Cero. Ni un byte. Postura de producto.** |

## 13. Conclusiones operativas para el blueprint

1. **El patrón fundamental funciona.** Servidor proxy en espacio de usuario + cliente TUN con `protect()` resuelve el tethering sin root. Esa decisión está validada por mercado.
2. **La calidad está en los detalles que VPN2Share trata de manera frágil:** UDP, DNS, race conditions, IP del Hotspot variable, recovery, telemetría. Allí es donde Gravital Share gana.
3. **Wintun es la elección correcta para Windows.** No hay debate.
4. **MTU 1280 es ley física, no preferencia.**
5. **Sin root no se puede evitar el costo de context switching en Android.** Pero se puede minimizar y exponer al usuario para que decida con datos.
6. **El usuario final juzga por dos cosas:** ¿se conecta?, ¿se mantiene? Throughput es secundario hasta que cae por debajo del 50% del baseline. La estabilidad gana.

## Cross-references

- Decisiones arquitectónicas que derivan de este análisis: [`docs/02-architecture/01-overview.md`](../02-architecture/01-overview.md)
- Implementación SOCKS5: [`docs/03-protocols/01-socks5.md`](../03-protocols/01-socks5.md)
- Bridge tun↔socks: [`docs/03-protocols/03-tun2socks-bridge.md`](../03-protocols/03-tun2socks-bridge.md)
- Cliente Windows con Wintun: [`docs/02-architecture/06-windows-client.md`](../02-architecture/06-windows-client.md)
- Estrategia DNS y antifuga: [`docs/02-architecture/04-network-engine.md`](../02-architecture/04-network-engine.md)
- Riesgos y limitaciones reconocidas: [`docs/07-roadmap/03-risks-limitations.md`](../07-roadmap/03-risks-limitations.md)
