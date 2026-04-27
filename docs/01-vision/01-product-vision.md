# 01 — Visión de Producto

## Problema concreto

Cuando una persona contrata una VPN en su Android y enciende el hotspot móvil, los dispositivos conectados al hotspot **no quedan protegidos**. El kernel de Android, por diseño, enruta el tráfico de tethering directamente a la interfaz celular evadiendo `tun0`. Forzar lo contrario requiere root o pagar por una segunda licencia de VPN para cada dispositivo, opción inviable para hogares de bajos ingresos, oficinas pequeñas y dispositivos cerrados (Smart TVs, consolas, IoT).

VPN2Share resolvió esto con un proxy local + cliente con `VpnService`. Funciona, pero arrastra problemas: bucles de DNS, fugas, fallback de UDP a TCP, agotamiento de batería, condiciones de carrera al servir Windows + Android simultáneamente, y cero observabilidad.

**Gravital Share existe para construir lo mismo, mejor, abierto a auditoría y como pieza del ecosistema Gravital.**

## Propuesta de valor en una frase

*Tu VPN, compartida con cualquier dispositivo de tu casa, sin root, sin fugas, sin segundo plan.*

## Segmentos objetivo

### Primario: hogares con un solo plan VPN
- Personas de Latinoamérica que pagan por una VPN (privacidad, geoblocking, censura) y quieren proteger TV, consola y portátil de la pareja con el mismo plan.
- Volumen: alto. Sensibilidad de precio: alta. Requieren onboarding sin fricción.

### Secundario: viajeros y nómadas digitales
- Cuartos de hotel, coworkings, redes públicas. Comparten conexión protegida con su laptop de trabajo y dispositivos secundarios desde el teléfono personal.
- Volumen: medio. Disposición de pago: media-alta.

### Terciario: oficinas pequeñas y agencias creativas
- 5-15 personas, equipos mixtos. Ven a Gravital Share como una alternativa transitoria a un router VPN dedicado.
- Volumen: bajo en cantidad, alto en valor por cliente. Requieren panel admin y reporting básico.

### Cuaternario: integración OEM con operadores y MVNOs
- Operadores que quieran ofrecer "hotspot privado" como feature premium. Modelo B2B2C, white-label.
- Largo plazo. Estratégico para Gravital Business.

## No-objetivos

Lo que **no** somos:
- No somos un proveedor de VPN. Somos el puente que comparte una VPN ya existente.
- No somos un firewall ni un IDS. No inspeccionamos contenido del usuario.
- No somos una herramienta de ofuscación contra DPI estatal. Esa función la cumple `SocksIP Tunnel` u otros productos del ecosistema.
- No optimizamos para usuarios con root: nuestra fuerza es funcionar sin él.

## Diferenciación

| Eje | VPN2Share / similares | Gravital Share |
|---|---|---|
| Motor de red | C/Go monolítico | Rust modular, auditable |
| Estado de sesión | Banderas implícitas | Máquina de estados explícita |
| Logs | Texto plano disperso | JSON estructurado, MCP-ready |
| Manejo de UDP | Best-effort, fugas | UDP Associate completo + udpgw fallback |
| DNS | Hereda del operador | Resolver propio configurable, sin fugas |
| Cliente Windows | TAP heredado | Wintun nativo, capa 3 pura |
| CI/CD | Build manual | GitHub Actions multiarq + fuzzing |
| Observabilidad | Inexistente | Telemetría diseñada para que un agente IA pueda diagnosticar |
| Modelo | App standalone | Pieza del ecosistema Gravital, pulso al *Network Layer* de Gravital Cloud |

## Métricas de éxito

### Técnicas (MVP)
- Latencia añadida por el bridge: **p50 < 15 ms**, p95 < 40 ms en LAN.
- Throughput sostenido por cliente: **≥ 25 Mbps** en hardware de gama media.
- Tasa de fugas DNS detectadas en pruebas automatizadas: **0**.
- Crashes/MAU del motor: **< 0.05%**.
- Tiempo de reconexión tras pérdida de portadora: **< 5 s** mediana.

### Producto (primer año post-lanzamiento)
- Instalaciones acumuladas: 50,000.
- DAU/MAU: ≥ 25%.
- NPS interno (encuestas en app): ≥ 40.
- Tiempo desde primera apertura hasta primera sesión exitosa: **< 90 s**.

### Estratégicas (alineación con Gravital)
- Telemetría agregada alimentando el Network Layer de Gravital Cloud (privacidad-preservadora): **integración activa antes del lanzamiento público**.
- Tres operadores telefónicos en LATAM en conversación de OEM antes de Q4 del primer año.

## Principios rectores del producto

1. **Cero fricción de configuración.** Un toggle. Si pedimos al usuario que entienda SOCKS, fallamos.
2. **Honestidad técnica.** La pantalla de diagnóstico expone qué está pasando. No ocultamos limitaciones.
3. **Defensa por defecto.** *Kill switch*, sin DNS leak, sin reenvío fuera del túnel — todo activado de fábrica.
4. **Respeto a la batería y al plan de datos.** Optimizamos antes de añadir features.
5. **Auditable.** El motor es Rust open-source con licencia controlada; el resto se publica selectivamente.

## Tono y posicionamiento de marca

Gravital Share es **infraestructura silenciosa**. No es una app de hacker, no es militar-estética, no es startup-friendly de pastel. Es seria, técnica, confiable, casi invisible. Su comunicación se parece más a Cloudflare o Tailscale que a NordVPN.

> *"El tráfico cruza. Eso es todo lo que tienes que saber."*
