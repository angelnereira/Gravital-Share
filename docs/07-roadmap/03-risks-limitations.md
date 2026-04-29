# 07.03 — Riesgos y Limitaciones Conocidas

## Lente

Este documento enumera **lo que sabemos que puede fallar**, lo que **deliberadamente no resolvemos**, y las **mitigaciones aceptadas**. El objetivo no es exhaustividad: es que un nuevo colaborador entienda el campo minado antes de pisarlo.

Cada riesgo tiene: descripción, severidad, probabilidad, y mitigación o aceptación explícita.

## Riesgos técnicos

### R-T1: API restrictions en Android nuevo

**Descripción:** Google ha endurecido progresivamente las APIs de red en Android. Cada release puede romper supuestos del producto:
- Android 10+: restricciones a `BIND_VPN_SERVICE` simultáneo con otros servicios.
- Android 11+: cambios en cómo `prepare()` se invoca.
- Android 13+: Hotspot compartido con condiciones más estrictas.
- Android 15+ (anunciado): posible restricción adicional al `protect()` cuando hay otra VPN activa.

**Severidad:** alta. Puede invalidar el modelo entero.
**Probabilidad:** media. Google avisa con beta, pero a veces sin aviso.

**Mitigación:**
- CI corre tests instrumentados en API 26, 30, 35 y la última beta disponible.
- Suscripción a Google Issue Tracker para `VpnService`.
- Contingencia: documentar un "modo legacy" hasta que se encuentre alternativa.
- Comunicación con la comunidad de WireGuard / OpenVPN para Android (mismas presiones).

### R-T2: Política Play Store sobre apps VpnService

**Descripción:** Google Play tiene política estricta sobre apps que usan `VpnService`. Históricamente:
- Requiere declaración explícita en metadata.
- Requiere política de privacidad navegable.
- Rechaza apps que usen la API para otros propósitos no-VPN.
- Ha removido apps similares retroactivamente.

**Severidad:** alta. Puede sacar el producto del canal principal.
**Probabilidad:** media-baja. Cumpliendo política, baja. Pero la política cambia.

**Mitigación:**
- Distribución dual: Play Store + sideload directo + F-Droid (en evaluación).
- Política de privacidad rigurosa, alineada a la postura "cero telemetría externa".
- Documentación legal lista para responder review extra de Google.
- Plan B: site oficial de Gravital Share como canal directo, con APK firmado y notarizado.

### R-T3: IP del Hotspot variable en OEMs

**Descripción:** algunos OEMs cambian la IP del Hotspot fuera del estándar `192.168.43.1`. Samsung, Xiaomi, Huawei han mostrado variabilidad. Una app que asume IP fija falla silenciosamente.

**Severidad:** media. Funcionalidad rota en subset de dispositivos.
**Probabilidad:** alta. Es la realidad del ecosistema.

**Mitigación:**
- **Detección dinámica obligatoria** vía `ConnectivityManager` y enumeración de `NetworkInterface`. La IP nunca se hardcodea.
- Si no se detecta, la app rehúsa arrancar el modo servidor con error claro.
- Tabla de compatibilidad mantenida en docs internos por modelo testeado.

### R-T4: Limitaciones de fd y memoria sin root

**Descripción:** apps Android sin privilegios tienen `ulimit` impuesto: típicamente 1024 fd. En modo servidor con muchos clientes, este límite se roza.

**Severidad:** media. Limita escalabilidad de un solo Hotspot.
**Probabilidad:** alta cuando hay >5 clientes activos con Keep-Alive.

**Mitigación:**
- Pool de fd con tamaño máximo explícito y rechazo limpio cuando se alcanza.
- Métrica `engine.fd.active` expuesta en UI.
- Idle timeout agresivo (default 90 s) para liberar fd.
- Límite documentado: **MVP soporta hasta 8 clientes simultáneos en modo servidor**.

### R-T5: Performance en gama baja

**Descripción:** el path Rust → JNI → smoltcp → SOCKS5 tiene overhead. En CPUs ARM viejos (Cortex-A53, etc.) puede saturar.

**Severidad:** media. UX degradada en dispositivos económicos comunes en LATAM.
**Probabilidad:** alta para dispositivos pre-2020 económicos.

**Mitigación:**
- Optimizaciones de release Rust (`opt-level = "z"`, LTO, codegen-units=1).
- `smoltcp` con allocations bajas, pool de buffers reutilizables.
- Benchmark obligatorio en dispositivo "low-end" representativo (ej. Moto E familia).
- Comunicación honesta: el README declara dispositivos recomendados.

### R-T6: Drenaje de batería percibido

**Descripción:** el costo de context switch es real. La batería se drena más rápido. Aunque sea inherente a la arquitectura sin-root, el usuario lo percibe como "bug".

**Severidad:** media. Reseñas de 1-2 estrellas predecibles.
**Probabilidad:** alta.

**Mitigación:**
- UI muestra el costo: indicador de "consumo elevado mientras está activo".
- Modo "stand-by" cuando no hay tráfico: motor entra en sleep agresivo.
- Documentación honesta en página de soporte explicando por qué.

### R-T7: TCP-over-TCP meltdown

**Descripción:** cuando el cliente envía TCP a través de un proxy SOCKS5 que también usa TCP, los algoritmos de control de congestión se pisan: ambas capas retransmiten al mismo tiempo, latencia explota, throughput colapsa. Es un fenómeno bien documentado en VPN-over-TCP.

**Severidad:** media. Afecta UX cuando la conexión upstream es lossy.
**Probabilidad:** condicional a calidad de red.

**Mitigación:**
- Fomentar uso de UDP cuando sea posible (UDP ASSOCIATE + udpgw).
- Documentar el fenómeno en sección "Why is it slow today?" del manual de soporte.
- En el roadmap a largo plazo: evaluar túnel UDP-encapsulado propio (post-MVP, fase 4+).

### R-T8: Vulnerabilidad del parser SOCKS5/HTTP

**Descripción:** parsers en Rust son seguros memory-wise, pero **bugs lógicos** pueden permitir DoS (consumo de fd, allocations infinitas) o panic. Históricamente, parsers de protocolos textuales han sido vector de ataque.

**Severidad:** media-alta si se explota.
**Probabilidad:** baja con disciplina, alta sin.

**Mitigación:**
- Fuzzing obligatorio (60 s/PR, 2 h/noche).
- Límites estrictos en tamaño de input (2048 bytes max para handshake/request).
- Timeouts agresivos en cada fase.
- `cargo audit` en CI.

### R-T9: Bugs de smoltcp en condiciones adversas

**Descripción:** `smoltcp` es buena pero no inmune. Bug en reensamblado, en cálculo de ventanas, en checksums, podría manifestarse solo bajo ciertas condiciones (alta pérdida, fragmentación, paquetes inusuales).

**Severidad:** media.
**Probabilidad:** baja-media. El crate está maduro y mantenido.

**Mitigación:**
- Versión congelada en `Cargo.lock`.
- Suite de tests cubriendo casos límite.
- Plan B: capa de abstracción `gravital-stack` permite cambiar a otro stack si surge bug crítico (ej. fork interno).

### R-T10: Deprecación de Wintun

**Descripción:** Wintun depende de WireGuard LLC. Si dejan de mantenerlo, el cliente Windows se queda sin driver moderno.

**Severidad:** alta para fase 1 (Windows).
**Probabilidad:** baja. Wintun es estratégico para WireGuard, no se va a abandonar pronto.

**Mitigación:**
- Mantener distancia limitada de la API de Wintun, encapsulada en `gravital-tun-windows`.
- Plan B: TUN propio firmado por Gravital, costo significativo pero posible.

## Riesgos de producto

### R-P1: Confusión con producto de evasión

**Descripción:** el espacio de "comparte tu VPN" intersecta con apps que evaden DPI, hacen domain fronting, abusan zero-rating de operadores. Gravital Share **no hace eso**, pero el público lo asocia.

**Severidad:** media. Confusión de marca, posibles problemas legales.
**Probabilidad:** alta sin comunicación cuidadosa.

**Mitigación:**
- Posicionamiento explícito en docs y marketing: "Gravital Share es relé, no túnel ofuscado."
- README, About in-app, política — todos lo dicen.
- No implementamos `X-Online-Host` ni payloads creativos en HTTP CONNECT.

### R-P2: Adopción dependiente de la VPN primaria del usuario

**Descripción:** Gravital Share solo es útil si el usuario YA tiene una VPN funcional en su Android. Sin VPN primaria, no hay nada que "compartir". El TAM se restringe a usuarios sofisticados.

**Severidad:** media. Limita el TAM.
**Probabilidad:** alta. Es estructural.

**Mitigación:**
- En el roadmap, evaluar si Gravital Cloud puede ofrecer una VPN primaria propia (Gravital Tunnel, futuro).
- Mientras tanto: target explícito es el usuario sofisticado y B2B.
- Documentación de "VPN primarias compatibles" como onboarding.

### R-P3: Competencia con tethering nativo de Android

**Descripción:** Algunos OEMs y operadores empiezan a soportar VPN-over-tethering nativamente. Si Google lo unifica en Android estándar, el producto pierde razón de existir.

**Severidad:** alta a largo plazo.
**Probabilidad:** baja-media en horizonte 2-3 años. Google es lento.

**Mitigación:**
- Evolucionar el producto hacia el ángulo B2B + identidad + audit (que el OS no va a ofrecer).
- Mantener la diferenciación en calidad de UX, no solo en feature técnica.

### R-P4: Reputación por bug en producción

**Descripción:** una sola fuga de DNS publicada por algún researcher destruye la confianza. La gente que pone su tráfico en una herramienta confía con su privacidad.

**Severidad:** crítica. Producto roto, marca dañada.
**Probabilidad:** baja con disciplina, real sin.

**Mitigación:**
- Sonda canaria estructural, no opcional.
- Testing dedicado a fuga.
- Bug bounty en consideración para post-MVP.
- Postmortem público en caso de incidente. Honestidad como póliza.

## Riesgos legales / regulatorios

### R-L1: Regulación de VPN en algunos países

**Descripción:** ciertos países restringen o prohíben VPNs (Rusia, China, Irán, EAU, Turkmenistán). Una app que comparte VPN cae en la misma categoría legal.

**Severidad:** alta en esos territorios.
**Probabilidad:** alta. Es realidad legal vigente.

**Mitigación:**
- Geo-restricción en Play Store para países donde es ilegal distribuirla.
- Política clara: el producto está dirigido a mercados con uso legal.
- No es nuestro trabajo "ayudar a evadir leyes" — es relé de tráfico, fin.

### R-L2: TOS de operadores celulares y tethering

**Descripción:** algunos operadores prohíben o restringen tethering en planes baratos. Compartir VPN sobre Hotspot puede violar el TOS del operador.

**Severidad:** baja. El operador puede limitar al usuario, no al desarrollador del software.
**Probabilidad:** alta para usuarios en planes "limitados".

**Mitigación:**
- Documentado en FAQ.
- Producto no oculta que es tethering. La responsabilidad TOS es del usuario.

### R-L3: Política de protección de datos (Ley 81/2019 Panamá, GDPR, CCPA)

**Descripción:** aún sin telemetría externa, manejamos metadatos del usuario localmente. Una mala configuración o un bug que envíe datos sin querer expone la app a sanciones.

**Severidad:** media-alta.
**Probabilidad:** baja con cero-telemetría como decisión arquitectónica.

**Mitigación:**
- Cero-telemetría es estructural, no opcional. El código no tiene endpoints para enviar datos.
- Política de privacidad publicada, alineada a Ley 81/2019 desde la primera release.
- Auditoría legal anual.
- Política `app:allowBackup="false"` para no exponer datos en backups.

## Limitaciones explícitamente aceptadas

Estas no son riesgos por mitigar. Son **decisiones de diseño** que aceptamos con sus consecuencias.

| Limitación | Aceptamos porque |
|------------|------------------|
| Throughput menor a una VPN nativa por kernel | El requisito sin-root lo impone físicamente |
| Drenaje de batería mayor a una VPN kernel | Mismo motivo |
| Sin soporte iOS en MVP | Costo de App Store y Network Extension |
| Sin soporte Android < API 26 | Mantener APIs antiguas es costo escondido enorme |
| Sin compresión, sin multipath, sin features fancy | El MVP debe ser confiable antes que vistoso |
| Sin telemetría externa, ni opt-in | Postura de producto, no negociable |
| Sin payload injection / DPI evasion | Producto distinto. Esto es relé, no túnel ofuscado |
| Cliente Windows en fase 2, no MVP | Foco. Android primero, Windows después |

## Cross-references

- MVP scope (qué incluye): [`docs/07-roadmap/01-mvp-scope.md`](01-mvp-scope.md)
- Roadmap (cuándo se aborda qué): [`docs/07-roadmap/02-roadmap.md`](02-roadmap.md)
- Postura de seguridad: [`docs/04-engineering/02-security.md`](../04-engineering/02-security.md)
- Estado del arte y comparación con VPN2Share: [`docs/06-research/01-vpn2share-analysis.md`](../06-research/01-vpn2share-analysis.md)
- Posicionamiento Gravital: [`docs/01-vision/02-positioning-gravital.md`](../01-vision/02-positioning-gravital.md)
