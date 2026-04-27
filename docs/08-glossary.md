# 08 — Glosario

> Términos técnicos y de marca usados a lo largo del blueprint. Orden alfabético dentro de cada sección.

## Marca y producto

**Gravital** — Marca comercial paraguas de Nereira Technology and Business Solutions. Conjunto de divisiones tecnológicas que comparten infraestructura, identidad, y filosofía de producto.

**Gravital Cloud** — División central. Plataforma económica omnipresente, multi-cloud, multi-tenant. Infraestructura que el resto de divisiones consume.

**Gravital ID** — División de identidad digital. Provee la **Visa Económica Digital (VE)** como identidad universal del ecosistema.

**Gravital Pay** — División de pagos y wallet. Cobro y facturación electrónica.

**Gravital Security** — División de ciberseguridad y auditoría.

**Gravital Share** — *Este producto.* Sistema de tethering VPN sin root para Android (extensible a Windows, Linux). Parte de **Gravital Technology**.

**Gravital Studio** — División de diseño y UX. Provee el Gravital Design System.

**Gravital Technology** — División de desarrollo a medida. Hogar de Gravital Share, OpenClaw wrapper, y otros productos técnicos.

**Gravital Tunnel** — *Producto futuro, no MVP.* Túnel VPN ofuscado propio. Donde vive la lógica de DPI evasion. **Gravital Share no es esto.**

**Nereira Technology and Business Solutions** — Entidad legal paraguas. Razón social en Panamá.

**SagoOne** — Plataforma SaaS de facturación electrónica, POS, biometría e inventario, ya en producción con clientes gubernamentales y corporativos en Panamá. Predecesora operacional de Gravital, parte del mismo holding.

**Visa Económica Digital (VE)** — Identidad económica primaria del ecosistema Gravital. Formato `VE-YYYY-XXXXXXX`. Inmutable, encadenada con hash SHA-256, anclada en blockchain pública para integridad. En Gravital Share, opcional para B2B en fase 3.

## Conceptos arquitectónicos del proyecto

**AGENT.md** — Documento en la raíz del repo con reglas vivas para agentes de código. Complementa este blueprint con políticas operativas.

**Bridge tun↔socks** — Componente que reensambla paquetes IP del TUN del cliente, expone "sockets virtuales", y abre conexiones SOCKS5 al servidor. Implementado vía `smoltcp`. Detallado en `docs/03-protocols/03-tun2socks-bridge.md`.

**Capa 7** — Capa de aplicación del modelo OSI. Gravital Share opera en Capa 7 (proxy de stream, no de paquetes IP) en el lado servidor.

**Control plane** — Cara Kotlin del producto. Interfaz de usuario, ciclo de vida del servicio Android, configuración. Sin lógica de red en hot path.

**Data plane** — Núcleo Rust del producto. Procesa paquetes, mantiene state machine, expone FFI. Donde vive el throughput.

**FFI (Foreign Function Interface)** — Frontera entre Rust y Kotlin. Implementada con JNI para Android, vía `cdylib` y `extern "C"`. Cada función envuelta en `catch_unwind`.

**JNI (Java Native Interface)** — Mecanismo estándar para llamar código nativo desde JVM. Ruta de invocación de los símbolos C exportados por el motor Rust.

**MCP (Model Context Protocol)** — Protocolo abierto para que agentes de IA accedan a contexto estructurado. Gravital Share expone un endpoint MCP-compatible en loopback (`127.0.0.1:7423`) cuando el usuario activa Modo Diagnóstico.

**Sesión** — Una activación del túnel desde Start hasta Stop. Tiene un `trace_id` único. Modelada como state machine explícita.

**Sonda canaria** — Verificación activa post-`Connected` que confirma que la resolución DNS sale por el túnel. Si falla, transición a `Failed` con `LeakDetected`.

**State machine de la sesión** — Máquina de estados explícita del lifecycle de una sesión: `Idle`, `Preparing`, `Connecting`, `Connected`, `Reconnecting`, `Stopping`, `Failed`. Detallada en `docs/02-architecture/05-session-state-machine.md`.

**Userspace TCP/IP stack** — Implementación de TCP/IP corriendo en el espacio de usuario, no en el kernel. Necesario para tun↔socks bridge. Aquí: `smoltcp`.

## Términos de red y protocolos

**`addDisallowedApplication`** — Método de `VpnService.Builder` para excluir apps específicas del túnel. Usado para excluir la propia app y prevenir bucles.

**`addRoute`** — Método de `VpnService.Builder` para enrutar destinos al TUN. Ruta `0.0.0.0/0` captura todo IPv4.

**ARP (Address Resolution Protocol)** — Protocolo de capa 2 para mapear IPs a MACs. Relevante en TAP-Windows, irrelevante en Wintun (Capa 3).

**Backpressure** — Mecanismo para evitar que un productor sature a un consumidor. En el motor: cuando el ring buffer del TUN se llena, el motor procesa paquetes en batch hasta drenar.

**badvpn / badvpn-udpgw** — Conjunto de herramientas open source para túneles VPN. `udpgw` es un demonio que multiplexa UDP sobre TCP. Usado como fallback cuando UDP ASSOCIATE no funciona.

**BIND** — Comando SOCKS5 (`0x02`) para escuchar conexiones entrantes. **No soportado en Gravital Share MVP.**

**CIDR** — Notación de rango IP, ej. `192.168.43.0/24`.

**CONNECT (HTTP)** — Método HTTP que pide al proxy abrir un túnel TCP a destino:puerto. Después del `200 OK`, el proxy es un puente ciego.

**CONNECT (SOCKS5)** — Comando SOCKS5 (`0x01`) equivalente. Establece conexión TCP a destino.

**cdylib** — Tipo de crate Rust que produce librería compartida (`.so` en Linux/Android, `.dll` en Windows, `.dylib` en macOS). El target del FFI.

**DPI (Deep Packet Inspection)** — Inspección profunda de paquetes por parte de operadores/firewalls. Técnica que las apps "ofuscadas" intentan evadir. **Gravital Share no evade DPI.**

**FQDN (Fully Qualified Domain Name)** — Nombre de dominio completo. Tipo de dirección SOCKS5 `ATYP=0x03`.

**Hotspot** — Funcionalidad de Android para compartir conexión móvil vía Wi-Fi. La interfaz típica es `wlan0`/`ap0`, IP histórica `192.168.43.1`.

**`iptables`** — Herramienta clásica de Linux para reglas de filtrado/NAT. Requiere root. **Gravital Share no la usa**, esa es la innovación clave.

**Loopback** — Interfaz `127.0.0.1`. El endpoint MCP solo se expone aquí.

**lwIP (Lightweight IP)** — Stack TCP/IP en C, ampliamente usado en sistemas embebidos. La opción "tradicional" de tun2socks. **Gravital Share usa `smoltcp` (Rust) en su lugar.**

**MTU (Maximum Transmission Unit)** — Tamaño máximo de paquete en una red. Default Gravital Share: **1280 bytes**, para evitar fragmentación en redes celulares.

**Path MTU** — MTU mínimo a lo largo del path completo. Si excede, los paquetes se fragmentan o se descartan silenciosamente.

**`protect()`** — Método de `VpnService` que marca un socket como exento del túnel. Sin esto, el socket del motor al proxy quedaría atrapado en su propia ruta. **Crítico para evitar bucles.**

**Ring buffer** — Estructura de datos circular para colas. Usada para buffers de paquetes con allocations bajas.

**RFC 1928** — Especificación oficial de SOCKS Protocol Version 5. Documento autoritativo del handshake y request format.

**`smoltcp`** — Stack TCP/IP en Rust, sin dependencias del sistema operativo, diseñado para sistemas embebidos. Usado por Gravital Share para el reensamblado en espacio de usuario.

**SOCKS5** — Protocolo de proxy de Capa 5 (sesión). Define handshake de auth + request de conexión + relay transparente. Soporta CONNECT (TCP), BIND (servidor), UDP ASSOCIATE.

**TAP-Windows** — Driver legacy de adaptador virtual de OpenVPN. Opera en Capa 2 (Ethernet). **No usado por Gravital Share.**

**`tcpdump`** — Herramienta de captura de paquetes. Usada para verificar no-fuga de DNS.

**TUN (Tunnel)** — Tipo de interfaz virtual de red que opera en Capa 3 (IP), entregando paquetes IP crudos a una app de espacio de usuario. Lo que `VpnService.establish()` provee.

**tun2socks** — Patrón general: leer paquetes de un TUN, reensamblar TCP en user space, abrir conexiones SOCKS5 al destino. Implementación específica varía (go-tun2socks, lwIP-tun2socks). Gravital Share es nuestra implementación con `smoltcp`.

**UDP ASSOCIATE** — Comando SOCKS5 (`0x03`) para encapsular UDP. Servidores muchas veces no lo implementan correctamente, de ahí el fallback a `udpgw`.

**`udpgw`** — Ver "badvpn-udpgw" arriba.

**`VpnService`** — API de Android (introducida en API 14) que permite crear interfaces TUN sin root. Devuelve un `ParcelFileDescriptor`. La base del cliente.

**Wintun** — Driver de adaptador virtual moderno por WireGuard LLC. Opera en Capa 3. Driver firmado por Microsoft. Elección para el cliente Windows.

## Términos del proceso

**`cargo audit`** — Verifica `Cargo.lock` contra DB de vulnerabilidades. Bloqueo de merge si Critical/High.

**`cargo deny`** — Política de licencias y bans de crates. Bloqueo si licencia no whitelisted.

**`cargo fuzz`** — Framework de fuzzing libfuzzer-style para Rust.

**`cargo ndk`** — Plugin de cargo que compila para targets de Android. Maneja sysroot y linker del NDK.

**`catch_unwind`** — Función de Rust que captura panics dentro de un closure. Usada en cada `extern "C"` para evitar UB cruzando el FFI.

**`Cargo.lock`** — Archivo de lockfile con hashes exactos de cada dependencia. Commiteado al repo.

**Definition of Done** — Criterios objetivos que una feature debe cumplir antes de considerarse completa.

**DevContainer** — Configuración estándar de VS Code para contenerizar el entorno de desarrollo. El repo incluye `.devcontainer/`.

**Detekt** — Linter estático para Kotlin. Run en CI.

**Espalda viva del producto** — Frase de Angel describiendo la VE como tejido económico siempre activo. Aplicable también a este blueprint: documentación que se mantiene viva con el código.

**Fuzzing** — Generación de inputs aleatorios/mutados para descubrir crashes y bugs lógicos.

**Ktlint** — Formateador y linter para Kotlin. Run en CI.

**Lockfile** — Archivo con versiones exactas resueltas (`Cargo.lock`, `gradle.lockfile`).

**Property-based testing** — Testing donde se especifican invariantes y la herramienta genera inputs aleatorios para verificarlos. Usado vía `proptest`.

**Soak test** — Test de ejecución prolongada (24h+) bajo carga sostenida para detectar leaks y degradación.

**`tracing` (crate)** — Sistema de logging estructurado para Rust. Base de la observabilidad.

**`tokio`** — Runtime async de Rust. Base del modelo de concurrencia del motor.

## Errores y códigos

**`PanicAcrossFfi`** — Código de error devuelto cuando el motor entra en pánico durante una llamada FFI. Recuperable: el control plane lo registra y reinicia.

**`LeakDetected`** — Estado de la sesión cuando la sonda canaria detecta fuga de DNS. Transición a `Failed`.

**`UnreachableProxy`** — Cliente no logra alcanzar el servidor proxy. Causa: IP del Hotspot mal detectada, `protect()` no aplicado correctamente, etc.

**`UpstreamUdpRefused`** — Servidor SOCKS5 upstream rechaza UDP ASSOCIATE. Triggers fallback a `udpgw`.

## Convenciones del repo

**`crates/`** — Directorio root de crates Rust del workspace.

**`android/`** — Directorio root del proyecto Android (Gradle).

**`docs/`** — Este blueprint. Numeración estable, paths canónicos, referencias relativas.

**`scripts/`** — Scripts de build y verificación. **Un agente siempre invoca el script, no el comando suelto.**

**`AGENT.md`** — Reglas de la casa para agentes de código. Complemento ejecutable de este blueprint.

**`README.md`** — Punto de entrada del repo. Stack table, status checklist, links a docs principales.

## Tagline oficial

> **El tráfico cruza. Eso es todo lo que tienes que saber.**

Reglas del tagline:
- No se traduce.
- No se rompe en dos líneas en collateral marketing.
- Vive en el `About` de la app, en el README, y en la página de producto.
- No se cambia sin aprobación de Angel.

## Cross-references

- Visión y posicionamiento: [`docs/01-vision/01-product-vision.md`](01-vision/01-product-vision.md), [`docs/01-vision/02-positioning-gravital.md`](01-vision/02-positioning-gravital.md)
- Arquitectura completa: [`docs/02-architecture/01-overview.md`](02-architecture/01-overview.md)
- Reglas para agentes: [`docs/04-engineering/05-agent-guidelines.md`](04-engineering/05-agent-guidelines.md), [`AGENT.md`](../AGENT.md)
