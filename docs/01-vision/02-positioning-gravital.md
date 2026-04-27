# 01.02 — Posicionamiento dentro del ecosistema Gravital

## El ecosistema en una imagen mental

Gravital es un sistema de divisiones especializadas que comparten infraestructura, identidad y telemetría. Las divisiones relevantes para Share:

- **Gravital Cloud** — la plataforma central. Aloja la *Visa Económica Digital*, el sistema de identidad, la cola de eventos, el ledger.
- **Gravital ID** — identidad universal. La cuenta del usuario es la misma en Share, en SagoOne, en Cloud.
- **Gravital Technology** — desarrollo a medida. Share vive aquí desde el punto de vista organizacional.
- **Gravital Security** — postura de ciberseguridad transversal. Define los estándares que Share adopta.
- **Gravital IA** — capacidad de análisis y agentes. Consume telemetría de Share para producir diagnósticos automáticos.

## Dónde encaja Share

Gravital Share es la primera **app de consumo masivo** que sale del ecosistema sin ser un producto B2B. Tiene cuatro funciones estratégicas más allá del producto en sí:

### 1. Vitrina de calidad de ingeniería

Share demuestra públicamente que Gravital construye software al nivel de Cloudflare/Tailscale. Es propaganda de capacidad.

### 2. Captador de identidades Gravital

El onboarding de Share **es** el onboarding de Gravital ID. Cada usuario que instala Share crea su Visa Económica Digital. Esto convierte un app utilitaria en una puerta de entrada al resto del ecosistema sin que el usuario sienta presión.

### 3. Generador de telemetría agregada para Gravital Cloud

Share ve patrones de tráfico que ningún otro componente puede ver: latencias por operador, calidad de conexión por región, comportamiento de DNS público. Esta telemetría — anonimizada, agregada, con consentimiento explícito — alimenta el *Network Layer* de Cloud y permite a Gravital ofrecer eventualmente un **mapa de calidad de internet en LATAM** que ningún operador tiene.

### 4. Producto de entrada para venta cruzada

Un usuario satisfecho de Share es candidato natural para Gravital Pay, Gravital ID Premium (KYC nivel 2+), o para que su empleador adopte SagoOne. La estrategia comercial de upsell vive aquí.

## Cómo Share consume al ecosistema

| Servicio Gravital | Uso que hace Share |
|---|---|
| Gravital ID | Login único, perfil del usuario, KYC opcional |
| Gravital Cloud (events) | Append-only de eventos de sesión (anonimizados) |
| Gravital Pay | Suscripción premium (sin anuncios, soporte priority, telemetría avanzada) |
| Gravital Security | Política de respuesta a incidentes, gestión de vulnerabilidades |
| Gravital IA | Asistente integrado de diagnóstico ("¿por qué se cae mi conexión?") |
| Gravital Studio | Sistema de diseño compartido (Gravital Design System) |

## Cómo el ecosistema consume a Share

| Necesidad del ecosistema | Aporte de Share |
|---|---|
| Datos reales de calidad de red en LATAM | Telemetría agregada (con consentimiento) |
| Adquisición masiva de cuentas Gravital ID | Onboarding como gateway |
| Validación pública del stack técnico | Performance, estabilidad y seguridad demostrables |
| Aprendizaje de UX para apps de consumo | Patrones probados que se reutilizan en Gravital Pay |

## Diferenciadores estratégicos a largo plazo

### a) Modo Mesh: tu hotspot pasa por mi VPN

A futuro, dos dispositivos Gravital Share pueden formar un *mesh* donde el cliente A actúa de relay del cliente B. Esto rompe la dependencia de un único anfitrión y abre escenarios como **redes familiares federadas** (papá viaja, hijos en casa siguen protegidos a través de su conexión).

### b) Integración con DEX/Pay: facturación de uso

En el horizonte de Gravital Pay, podemos cobrar tethering por minuto entre cuentas en mesh, con asentamiento on-chain en el ledger Gravital. Esto convierte a Share en un primitivo económico, no solo técnico.

### c) Modo Empresa: gateway para equipos pequeños

Una pyme que ya usa SagoOne puede activar "Gravital Share Business" en un teléfono de la oficina, y todos sus dispositivos quedan protegidos detrás del mismo plan. Telemetría visible al admin desde el panel SagoOne.

### d) Modo Vehículo

Conductores de carga, taxis, deliveries. Su teléfono actúa como hotspot protegido para tablets y dispositivos de flota. Caso de uso concreto en LATAM donde los IoT empresariales suelen no tener VPN.

## Restricciones derivadas del ecosistema

1. **Identidad consistente.** El esquema de la Visa Económica Digital y los formatos de evento de Cloud son contrato. Share los respeta sin desviación.
2. **Compliance Ley 81/2019 (Panamá) y futura LATAM.** La telemetría agregada cumple la regulación de protección de datos del país. *No hay excepción* para "modo testing".
3. **Estándares Gravital Security.** ISO 27001 / SOC 2 son objetivo del ecosistema. Share contribuye desde el día uno con prácticas que escalan a esa certificación.
4. **Disciplina de marca.** El logo, el sistema de color, los prefijos de identificadores (`VE-YYYY-XXXXXXX`) y el lenguaje de error son los del Gravital Design System. No reinventamos lo que existe.

## Posicionamiento competitivo

| Categoría | Competidor relevante | Cómo nos comparamos |
|---|---|---|
| Tethering VPN sin root | VPN2Share, Every Proxy | Mejor calidad técnica, pertenece a un ecosistema mayor |
| Tethering con router | GL.iNet, FlashRouters | Nosotros no requerimos hardware adicional |
| VPN con multi-device | NordVPN family, Surfshark | Ellos cobran por dispositivo, nosotros compartimos la VPN existente |
| Mesh privada | Tailscale, ZeroTier | Diferente nicho; ellos crean overlays, nosotros compartimos un túnel saliente |

## Mensaje en dos oraciones (para cualquier audiencia)

> *Gravital Share comparte tu VPN con todos los dispositivos de tu casa, sin root, sin un segundo plan, sin fugas. Es la primera pieza del ecosistema Gravital diseñada para ti, no para tu empresa.*
