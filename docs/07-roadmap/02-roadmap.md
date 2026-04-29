# 07.02 — Roadmap Post-MVP

## Lente

Este roadmap está organizado en **fases con criterios de entrada**, no en fechas calendario. Cada fase arranca cuando la anterior cumple su definition-of-done. Las fechas son aspiracionales y se ajustan trimestralmente.

> Asunción base: el MVP (fase 0) ha sido entregado y validado con cohorte alpha.

## Fase 1 — Cliente Windows (Q3 2026)

**Criterio de entrada:** MVP estable en Android, cohorte alpha de >100 usuarios reportando uso diario sin issues P0.

### Objetivos

- Cliente Windows funcional usando Wintun.
- Servicio NT corriendo en background con privilegios mínimos.
- Daemon de transcodificación (`gravital-engine` reusable, target `x86_64-pc-windows-msvc`).
- IPC vía named pipe local entre UI y servicio.
- Instalador WiX/MSI firmado con EV Code Signing Cert.

### Entregables

- `windows/` workspace completo.
- Reusabilidad del 70% del crate `gravital-engine` (core common).
- `gravital-windows-svc.exe` y `gravital-windows-ui.exe` como dos binarios.
- Documentación de instalación.
- Soak test 24h sobre Windows 10 + Windows 11.

### No incluye

- Soporte de TAP-Windows. Solo Wintun.
- Kernel driver propio. Solo Wintun standard.
- Soporte de versiones Windows < 10.

> Ver [`docs/02-architecture/06-windows-client.md`](../02-architecture/06-windows-client.md) para diseño técnico.

## Fase 2 — DNS-only Proxy Mode + Smart TVs (Q3-Q4 2026)

**Criterio de entrada:** cliente Windows estable, métricas muestran adopción del modo servidor por cohorte B2C.

### Problema

Smart TVs, consolas, y dispositivos cerrados **no pueden instalar app cliente**. Hoy se conectan poniendo manualmente la IP del Hotspot como DNS, lo cual es un workaround feo. Quiero ofrecer una experiencia limpia.

### Objetivos

- Modo `DNS-only proxy` en el servidor: el dispositivo cliente solo configura DNS = `<IP_Hotspot>`.
- Resolución DNS por el motor + redirección DoH al upstream.
- Documentación de configuración para televisores comunes (Samsung, LG, Sony, Apple TV, Roku, Fire TV).
- Detección automática del cliente cuando se asocia al Hotspot, instrucción visual en la app.

### Limitación honesta

Un proxy DNS-only no cifra todo el tráfico del Smart TV — solo encubre las queries. Es una **degradación** vs. modo cliente completo. Se documenta claramente para que el usuario sepa.

## Fase 3 — Identidad B2B con Gravital ID (Q4 2026)

**Criterio de entrada:** Gravital ID GA disponible, primer cliente B2B identificado.

### Objetivos

- Modo "tier B2B" donde el servidor exige autenticación SOCKS5 user/pass.
- Las credenciales se derivan del **Visa Económica Digital** (VE-YYYY-XXXXXXX) del operador del Hotspot.
- Audit log de qué identidad consumió cuánto tráfico (sin contenido).
- Integración con Gravital Cloud para reporting agregado al admin del tenant.

### Casos de uso B2B

- Empresa con flota de tablets en campo, comparten Hotspot de un supervisor con VPN corporativa.
- Equipos de campo de seguros, logística, periodismo en zonas con conectividad limitada.

### No incluye

- Cobro automatizado (es Gravital Pay, fase posterior).
- Multi-tenant aislamiento. Un operador = un tenant.

## Fase 4 — Linux Desktop Client + eBPF (Q1 2027)

**Criterio de entrada:** cliente Windows en producción, demanda demostrada de Linux desde la cohorte de power users.

### Objetivos

- Cliente Linux para Ubuntu, Fedora, Arch (paquetes nativos `.deb`, `.rpm`, AUR).
- **Path eBPF opcional:** cuando el kernel lo permite y el usuario tiene CAP_BPF, usar XDP/TC para capturar paquetes a nivel del kernel y eliminar el overhead del context switch.
- Path tradicional con TUN cuando eBPF no está disponible.
- CLI completo (sin requerir UI gráfica).

### Reto técnico

Mantener el mismo `gravital-engine` core con dos paths de captura:
- **Path A**: leer/escribir TUN como en Android (Linux también lo soporta).
- **Path B**: programa eBPF que entrega paquetes a user space vía ring buffer compartido.

El crate `gravital-tun` se generaliza a `gravital-capture` con backends.

### No incluye

- Distribución como flatpak/snap (evaluación).
- GUI con GTK/Qt en MVP de Linux. CLI primero.

## Fase 5 — Modo Mesh y Coordinación entre Anfitriones (2027+)

**Criterio de entrada:** especulativo. Sólo se aborda si hay demanda demostrada y ancho de banda en el equipo.

### Idea

Múltiples anfitriones en la misma LAN se anuncian unos a otros (mDNS) y un cliente puede balancear o failovear entre ellos. El "mesh" es un grupo de Hotspots compartiendo cobertura para una flota de clientes.

### Por qué es especulativo

Es un cambio arquitectónico importante: protocolo de control entre nodos, descubrimiento, salud, fairness entre múltiples upstreams. Es un producto distinto disfrazado de feature.

## Fase 6 — Integración Gravital Pay (cobro a B2B) (2027+)

**Criterio de entrada:** Gravital Pay GA, modelo de pricing definido, demanda B2B sostenida.

### Objetivos

- El admin del tenant ve consumo agregado por VE en su tablero.
- Pricing por GB transferido o por seat-mes (modelo a definir).
- Cobro mediante Gravital Pay con factura electrónica DGI.

### Notas

- Esto convierte a Gravital Share en un producto generador de revenue de la división **Gravital Cloud**, no un standalone gratis.
- El producto B2C sigue siendo **gratis y sin cobro**, sin telemetría externa, sin downgrade.

## Fase 7 — iOS (2027+, si se justifica)

**Criterio de entrada:** demanda demostrada y modelo de costo de Apple Network Extension validado.

### Realidad

iOS no permite VPN-like apps gratuitamente. Requiere una Network Extension de pago (~$99/año) y revisión estricta de Apple. La política de App Store es agresiva con apps que tunelizan tráfico.

### Decisión condicional

Solo se aborda si: hay un cliente B2B que paga la entrada, el modelo de revenue justifica los costos de mantenimiento (App Store reviews son lentas y volátiles), y el equipo tiene bandwidth para soportar dos plataformas móviles.

## Vista resumen del roadmap

| Fase | Hito | Criterio de entrada |
|------|------|---------------------|
| 0 | MVP Android | (estamos aquí) |
| 1 | Cliente Windows | MVP estable + alpha 100+ users |
| 2 | DNS-only proxy mode | Cliente Windows estable |
| 3 | Identidad B2B (Gravital ID) | Gravital ID GA + primer cliente B2B |
| 4 | Linux Desktop + eBPF | Windows en prod + demanda Linux |
| 5 | Mesh entre anfitriones | Demanda especulativa |
| 6 | Cobro B2B (Gravital Pay) | Gravital Pay GA + B2B sostenido |
| 7 | iOS | B2B paga la entrada |

## Compromisos firmes

- **El MVP B2C de Android es gratis para siempre.** Independiente del éxito de las fases B2B.
- **Cero telemetría externa, en cualquier fase.** No es negociable.
- **El motor Rust se mantiene como un solo workspace.** Los targets crecen, el core no se fragmenta.
- **Cada fase pasa por la misma puerta de calidad** que el MVP: fuzz, audit, soak, no-leak DNS, soak 24h.

## Compromisos blandos (sujetos a aprendizaje)

- Estimaciones de fechas calendario.
- Orden exacto de fases 5/6/7.
- Modelo de pricing de B2B.
- Stack del cliente Windows (Tauri vs WinUI 3) — decisión cuando llegue la fase 1.

## Cross-references

- Alcance MVP: [`docs/07-roadmap/01-mvp-scope.md`](01-mvp-scope.md)
- Riesgos por fase: [`docs/07-roadmap/03-risks-limitations.md`](03-risks-limitations.md)
- Posicionamiento en Gravital: [`docs/01-vision/02-positioning-gravital.md`](../01-vision/02-positioning-gravital.md)
- Cliente Windows: [`docs/02-architecture/06-windows-client.md`](../02-architecture/06-windows-client.md)
