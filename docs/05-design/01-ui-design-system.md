# 05.01 — Sistema de Diseño y UI

## Tesis

> Una herramienta de infraestructura **se ve como infraestructura**. Cloudflare, Tailscale, Linear, Vercel. No como un launcher de juegos. No como un wallet con confeti.

La UI de Gravital Share refleja la disciplina del data plane: **alta señal, bajo ruido**. El usuario debería poder mirar la pantalla principal y, en menos de un segundo, saber **si su tráfico está cruzando o no**.

## Principios

| Principio | Traducción |
|-----------|------------|
| **Alta señal** | Solo lo crítico está en pantalla principal. El resto se colapsa. |
| **Bajo ruido** | Cero badges decorativos, cero animaciones gratuitas, cero íconos huecos. |
| **Modo oscuro nativo** | El producto vive en oscuro. Modo claro es accesibilidad, no estética. |
| **Tipografía técnica** | Datos numéricos en monoespaciada. Texto largo en sans humanista. |
| **Feedback consistente con estado** | Cada estado de la sesión tiene un único color y micro-animación asociada. |
| **Sin chrome decorativo** | Cero gradients pop, cero glassmorphism, cero blur estético. |

## Tokens del sistema

### Color (modo oscuro, base)

| Token | Hex | Uso |
|-------|-----|-----|
| `surface.0` | `#0A0A0B` | Fondo de app |
| `surface.1` | `#111114` | Cards, contenedores |
| `surface.2` | `#1A1A1F` | Hover, elevación 1 |
| `border.subtle` | `#22222A` | Bordes finos |
| `border.strong` | `#33333E` | Inputs, divisores |
| `text.primary` | `#F4F4F5` | Texto principal |
| `text.secondary` | `#A1A1AA` | Subtítulos, labels |
| `text.tertiary` | `#52525B` | Hints, placeholders |
| `accent.primary` | `#5E8BFF` | Acción primaria, links |
| `accent.muted` | `#374878` | Hover de accent |

### Color de estado (mapeo a state machine)

Cada estado del túnel tiene **un único color asociado** y se respeta en TODO indicador, no solo en uno:

| Estado | Color | Hex | Forma |
|--------|-------|-----|-------|
| `Idle` | Neutro | `#71717A` | Círculo hueco |
| `Preparing` | Azul tenue | `#5E8BFF` | Círculo con barra deslizante |
| `Connecting` | Ámbar | `#F59E0B` | Círculo con pulso |
| `Connected` | Verde | `#10B981` | Círculo lleno |
| `Reconnecting` | Naranja | `#FB923C` | Círculo con pulso lento |
| `Stopping` | Gris azulado | `#475569` | Círculo desvaneciéndose |
| `Failed` | Rojo | `#EF4444` | Círculo con borde grueso |
| `LeakDetected` | Rojo crítico | `#DC2626` | Círculo con halo |

> **Regla:** rojo solo significa **fallo o riesgo**. Nunca decoración. El día que aparece rojo, algo importa.

### Tipografía

| Familia | Uso | Pesos |
|---------|-----|-------|
| **Inter** | Texto general, UI, headers | 400, 500, 600 |
| **JetBrains Mono** o **IBM Plex Mono** | Datos técnicos, IPs, puertos, contadores, logs | 400, 500 |

Ambas son open source y cargables vía Compose `FontFamily`. Sin Roboto. Sin SF Pro.

### Escala tipográfica

| Token | Tamaño | Line height | Uso |
|-------|--------|-------------|-----|
| `text.display` | 32sp | 40sp | Estado del túnel en pantalla principal |
| `text.title` | 20sp | 28sp | Headers de sección |
| `text.body` | 15sp | 22sp | Texto normal |
| `text.caption` | 13sp | 18sp | Labels, secundarios |
| `text.mono.lg` | 16sp / mono | 22sp | IP, puerto principal |
| `text.mono.sm` | 13sp / mono | 18sp | Métricas, contadores |

### Espaciado

Sistema de 4 px. Tokens: `space.1=4`, `space.2=8`, `space.3=12`, `space.4=16`, `space.6=24`, `space.8=32`, `space.12=48`.

### Esquinas

| Token | Radio | Uso |
|-------|-------|-----|
| `radius.sm` | 6dp | Botones, inputs |
| `radius.md` | 10dp | Cards |
| `radius.lg` | 14dp | Sheets, modales |
| `radius.full` | 999dp | Pills, indicadores circulares |

## Pantalla principal: anatomía

```
┌─────────────────────────────────────┐
│ Gravital Share              [≡]     │  ← header mínimo
├─────────────────────────────────────┤
│                                     │
│           ●  CONNECTED              │  ← estado, color del state
│           Tunnel up — 4m 12s        │  ← duración, secundario
│                                     │
│   ↓ 12.4 MB    ↑ 3.1 MB             │  ← throughput total
│   42 ms RTT     18 conns            │  ← métricas live (mono)
│                                     │
│  ┌────────────────────────────────┐ │
│  │ Throughput (60s)               │ │  ← gráfica minimalista
│  │     ▁▂▄▆█▆▄▆█▇▅▄▆█▇▅▃▂        │ │
│  └────────────────────────────────┘ │
│                                     │
│  ▸ Diagnostics                      │  ← colapsado por defecto
│  ▸ Active connections (18)          │  ← colapsado por defecto
│                                     │
│              ┌─────────┐            │
│              │  STOP   │            │  ← acción primaria
│              └─────────┘            │
└─────────────────────────────────────┘
```

Nada más. Sin banners, sin upgrade prompts, sin "te invitamos a Premium".

## Componentes núcleo

| Componente | Estado |
|------------|--------|
| `StatusOrb` | Círculo de estado con animación según `SessionState` |
| `MetricBlock` | Par label + valor (mono) |
| `ThroughputSparkline` | Gráfica simple de 60s, sin ejes |
| `CollapsibleSection` | Sección colapsable con caret |
| `PrimaryButton` | Acción única destacada |
| `GhostButton` | Acción secundaria |
| `MonoText` | Texto con familia mono y kerning ajustado |
| `LeakBanner` | Banner rojo crítico cuando se detecta fuga |

Cada uno está implementado como `@Composable` puro (sin side effects), parametrizado por su estado. Cero `LaunchedEffect` dentro de componentes de presentación.

## Animaciones

Reglas:

| Tipo | Duración | Curva |
|------|----------|-------|
| Transición de estado del orb | 250 ms | `EaseInOutQuad` |
| Pulso en `Connecting` | 1200 ms loop | `EaseInOutSine` |
| Pulso en `Reconnecting` | 1800 ms loop | `EaseInOutSine` |
| Aparición de banner crítico | 180 ms | `EaseOutQuad` |
| Apertura de sección colapsable | 200 ms | `EaseOutQuad` |
| Hover/press feedback | 80 ms | `EaseOutQuad` |

**Nada más se anima.** No hay animaciones decorativas en cards, ni "respirando", ni partículas.

## Accesibilidad

| Aspecto | Compromiso |
|---------|------------|
| Contraste AA mínimo | Todo texto sobre fondos del sistema |
| Contraste AAA donde se pueda | Texto `primary` y datos críticos |
| Tamaño táctil mínimo | 44×44 dp |
| Anuncios para lectores | Cada cambio de estado emite `LiveRegion` accesible |
| No depende solo de color | Estado también está en texto y forma |
| Modo claro disponible | Tokens espejo, mismas relaciones de contraste |

## Modo claro (espejo, no producto principal)

Solo se cambia el set de surfaces y texts. Los colores de estado se desaturan ligeramente para mantener contraste. La estructura, espaciado, y tipografía no cambian.

## Iconografía

- **Lucide Icons** vía port a Compose, o `phosphor-android`. Nunca Material default.
- Stroke 1.5 px.
- Solo usar iconos para reforzar texto, nunca como única señal de acción.

## Pantallas secundarias

| Pantalla | Contenido |
|----------|-----------|
| **Configuration** | Modo (server/client), MTU, DNS, allowlist de apps |
| **Diagnostics** | Logs estructurados navegables, exportar bundle |
| **Active Connections** | Lista en tiempo real de conexiones (sin URLs ni hosts) |
| **About** | Versión, commit hash, link al doc |

Todas siguen la misma cuadrícula y los mismos tokens. Nada inventado por pantalla.

## Alineamiento con Gravital Design System

Cuando exista el Gravital Design System global, Gravital Share importa sus tokens. Mientras tanto, esta especificación **es la fuente de verdad** y se diseñó para ser un subset compatible: mismos nombres, mismas escalas, misma filosofía.

## Criterios de aceptación visual

- ¿Un usuario nuevo, en menos de 1 segundo, sabe si su tráfico está cruzando? **Sí o no.**
- ¿Hay alguna animación corriendo en `Idle` o `Connected` estable? **Solo el cursor del gráfico.**
- ¿Hay rojo en pantalla cuando todo está bien? **No.**
- ¿Hay más de tres niveles tipográficos visibles a la vez? **No.**
- ¿La pantalla cabe en 6.7" sin scroll en estado normal? **Sí.**

Si alguna respuesta no coincide, la UI está sobrediseñada.

## Cross-references

- Estados consumidos: [`docs/02-architecture/05-session-state-machine.md`](../02-architecture/05-session-state-machine.md)
- Métricas alimentadoras: [`docs/04-engineering/01-observability.md`](../04-engineering/01-observability.md)
- Posicionamiento de marca: [`docs/01-vision/02-positioning-gravital.md`](../01-vision/02-positioning-gravital.md)
