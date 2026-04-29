# 07.01 — Alcance del MVP

## Tesis

> El MVP no es "lo más pequeño que compila". Es **lo más pequeño que puede ser entregado con dignidad** — que un usuario pueda usar todo el día, que no fugue DNS, que se recupere de Wi-Fi inestable, y que no nos avergüence en una review.

## Definición operacional

El MVP de Gravital Share está *listo* cuando:

1. **Funciona end-to-end en Android**, modo servidor + modo cliente, entre dos dispositivos físicos sin root.
2. **Soporta SOCKS5 + HTTP CONNECT** del lado servidor.
3. **No fuga DNS**, verificable con `tcpdump`.
4. **Maneja UDP** vía `UDP ASSOCIATE` con fallback a `udpgw`.
5. **Se recupera automáticamente** de pérdida transitoria de Wi-Fi.
6. **No fuga memoria ni fd** en una corrida de 24 horas.
7. **Tiene una UI mínima** que muestra estado, throughput, y un botón Start/Stop que respeta la state machine.
8. **Tiene logs estructurados** exportables.
9. **Pasa CI completo** sin warnings, con fuzzing de 60 s por target sin crashes.
10. **Está documentado** — este blueprint y los `docs/*` están al día.

Si las 10 condiciones se cumplen, hay MVP. Si una falla, no hay MVP.

## Alcance: dentro

### Plataforma

- **Android** (mínimo API 26, target API 35).
- **Modo servidor** (Dispositivo A, host del Hotspot con VPN primaria).
- **Modo cliente** (Dispositivo B, consume el túnel sin app de VPN propia).

### Protocolos

- **SOCKS5 según RFC 1928**:
  - Métodos de auth: `0x00` (sin auth) en MVP. `0x02` (user/pass) post-MVP.
  - `CONNECT` (TCP) — completo.
  - `UDP ASSOCIATE` — completo, con bind a puerto efímero.
  - `BIND` — fuera del MVP.
- **HTTP CONNECT** para Smart TVs / consolas con configuración manual de proxy.
- **Bridge tun↔socks** vía `smoltcp` en el cliente Android.

### UDP

- `UDP ASSOCIATE` directo cuando el upstream lo soporta.
- Fallback a `udpgw` automático cuando el upstream falla.
- Manejo especial del puerto 53 (DNS): siempre intercepta y procesa el motor.

### DNS

- Configurable en UI (default `1.1.1.1`).
- Sonda canaria post-`Connected` para verificar no-fuga.
- Transición a `Failed` con `LeakDetected` si la sonda falla.

### Estado y recovery

- State machine completa: `Idle → Preparing → Connecting → Connected → Reconnecting → Stopping → Failed`.
- Backoff exponencial en `Reconnecting` (250 ms inicial, factor 2, cap 30 s).
- Cancelación limpia desde UI en cualquier estado.

### UI

- Pantalla principal con `StatusOrb`, métricas live, throughput sparkline, botón Start/Stop.
- Pantalla de configuración: modo, MTU, DNS, allowlist de apps.
- Pantalla de diagnóstico: log estructurado navegable, exportar bundle.
- Modo oscuro nativo. Modo claro como espejo.

### Telemetría local

- JSON structured logs.
- Endpoint MCP-compatible en loopback (opt-in).
- Cero telemetría externa.

### Distribución

- APK firmado para sideload.
- Subida a track interno de Play Store para testing alpha.

### Calidad

- Unit + property + fuzz + integración + soak 24h.
- Coverage mínima por capa (ver [`docs/04-engineering/04-testing.md`](../04-engineering/04-testing.md)).
- CI pasa en cada PR sin warnings.

## Alcance: fuera (explícitamente)

Para que no haya ambigüedad, **estas cosas no son MVP**, y un PR que las introduzca debe ser rechazado salvo aprobación explícita:

| Excluido | Por qué |
|----------|---------|
| **Cliente Windows** | Fase 2. Wintun + driver, instalador WiX, complejidad significativa. |
| **Cliente Linux/Desktop** | Fase 3. eBPF en exploración. |
| **iOS** | Apple no permite `VpnService`-equivalente sin tipo Network Extension de pago. Evaluación posterior. |
| **Modo mesh / multi-hop** | Idea futura. No tiene espacio en MVP. |
| **Integración con Gravital Pay (cobro)** | Producto B2B futuro. MVP es B2C gratuito. |
| **Integración con Gravital ID (Visa Económica Digital)** | Opt-in en post-MVP para tier B2B. MVP no requiere identidad. |
| **Ofuscación / SNI spoofing / payload injection** | Es el dominio del producto hermano (Gravital Tunnel, futuro). MVP es relé limpio. |
| **Auth user/pass en SOCKS5** | Post-MVP. Por ahora: sólo `no-auth` en LAN del Hotspot, bind restringido a la subred. |
| **Compresión de tráfico** | Premature optimization. |
| **Múltiples upstream proxies con load balancing** | Sobrediseño. |
| **Modo "DNS-only proxy" para TVs sin app cliente** | Roadmap fase 2. |
| **Soporte de proxies upstream encadenados** | Fuera. El upstream del MVP es la VPN primaria del anfitrión, no otro proxy. |
| **Plugins / extensiones** | No hay sistema de plugins en MVP. |
| **Crash reporting externo** | Política de producto: cero. |

## Criterios "definition of done" por feature

| Feature | Done cuando |
|---------|-------------|
| Servidor SOCKS5 | Pasa los vectores RFC 1928, fuzzing limpio, soak 24h sin leaks |
| Servidor HTTP CONNECT | Smart TV real conecta, MTU funciona, sin payload injection |
| Cliente VPN | TUN se establece, `protect()` evita loops, MTU 1280 verificado |
| Bridge tun↔socks | Throughput ≥ 70% baseline, RTT añadido ≤ 25 ms |
| UDP ASSOCIATE | Videollamada de 30 min sin caída |
| Fallback udpgw | Cuando upstream falla UDP, cliente sigue funcionando |
| State machine | Test exhaustivo de transiciones legales/ilegales |
| Reconnect | Apagar Wi-Fi 60 s → vuelve a `Connected` automáticamente |
| DNS no-fuga | `tcpdump` confirma cero queries fuera del túnel |
| UI estado | Cada estado tiene su animación y color, todo accesible |
| Logs estructurados | JSON valida contra schema, exportables, sin PII |
| Endpoint MCP | Responde 4 rutas, requiere token, sólo loopback |
| Soak 24h | Cero crashes, RAM estable, fd estable, throughput estable |

## Anti-MVP: errores que reconocemos como tentación

- **"Vamos a meter ML para detectar congestión."** No.
- **"Vamos a hacer un dashboard remoto."** No.
- **"Vamos a darle un modo light para que arranque rápido."** No, primero arrancamos completo y luego optimizamos.
- **"Vamos a soportar todos los Android desde 5.0."** No, API 26 mínimo. Mantener 5.0 es costo escondido enorme.
- **"Vamos a hacer la UI más bonita con animaciones."** Solo las definidas en el design system. El resto es ruido.

## Cronograma (estimado)

| Fase | Duración | Entregable |
|------|----------|------------|
| **Foundation** (toolchain, workspace, FFI vacío, CI) | 2 sem | Build verde multiarch, app placeholder corre |
| **Engine core** (SOCKS5 server + parsers + tests) | 3 sem | Servidor SOCKS5 standalone funcional |
| **Network engine** (smoltcp + tun↔socks bridge) | 4 sem | Cliente puede pasar tráfico TCP a través del servidor |
| **UDP + DNS** (UDP ASSOCIATE + udpgw + sonda DNS) | 3 sem | Videollamada y resolución DNS funcional, no-fuga verificada |
| **HTTP CONNECT + Windows config** (servidor HTTP CONNECT) | 1 sem | Smart TV con config manual conecta |
| **Control plane + UI** (Compose, state machine, screens) | 3 sem | App utilizable end-to-end |
| **Hardening** (fuzz, audit, soak, leak tests) | 2 sem | CI completa, soak 24h verde |
| **Internal alpha** (track Play, feedback) | 2 sem | Bundle alpha distribuido, bugs P0 corregidos |

**Total estimado: ~20 semanas.** Con un agente de código eficiente y revisión humana semanal.

## Después del MVP

Ver [`docs/07-roadmap/02-roadmap.md`](02-roadmap.md) para el roadmap post-MVP.

## Cross-references

- Roadmap completo: [`docs/07-roadmap/02-roadmap.md`](02-roadmap.md)
- Riesgos: [`docs/07-roadmap/03-risks-limitations.md`](03-risks-limitations.md)
- Tests: [`docs/04-engineering/04-testing.md`](../04-engineering/04-testing.md)
- Posicionamiento Gravital: [`docs/01-vision/02-positioning-gravital.md`](../01-vision/02-positioning-gravital.md)
