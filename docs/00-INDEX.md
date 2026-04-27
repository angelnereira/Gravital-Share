# 00 — Índice Maestro

> Punto de entrada para humanos y agentes. Cada documento es autocontenido, pero el orden de lectura recomendado es de arriba hacia abajo.

## Cómo leer este blueprint

El proyecto está descrito en siete capas. Cada capa responde a una pregunta distinta:

| # | Capa | Pregunta que responde |
|---|---|---|
| 01 | Visión | ¿Por qué existimos y para quién? |
| 02 | Arquitectura | ¿Cómo está construido el sistema? |
| 03 | Protocolos | ¿Cómo hablan las piezas entre sí? |
| 04 | Ingeniería | ¿Cómo construimos, probamos y operamos? |
| 05 | Diseño | ¿Cómo se ve y se siente el producto? |
| 06 | Investigación | ¿Qué sabe la industria que debemos saber? |
| 07 | Roadmap | ¿Qué hacemos primero y por qué? |

## Tabla completa

### 01 — Visión
- [`01-vision/01-product-vision.md`](./01-vision/01-product-vision.md) — Visión de producto, segmentos, propuesta de valor.
- [`01-vision/02-positioning-gravital.md`](./01-vision/02-positioning-gravital.md) — Posicionamiento dentro del ecosistema Gravital.

### 02 — Arquitectura
- [`02-architecture/01-overview.md`](./02-architecture/01-overview.md) — Vista de 10,000 pies. Diagrama de capas.
- [`02-architecture/02-data-plane-rust.md`](./02-architecture/02-data-plane-rust.md) — El motor en Rust. Estructura de crates, FFI, performance.
- [`02-architecture/03-control-plane-kotlin.md`](./02-architecture/03-control-plane-kotlin.md) — La app Android. `VpnService`, ciclo de vida, ViewModel.
- [`02-architecture/04-network-engine.md`](./02-architecture/04-network-engine.md) — Pila TCP/IP en espacio de usuario, parsers, manejo de UDP.
- [`02-architecture/05-session-state-machine.md`](./02-architecture/05-session-state-machine.md) — La sesión como autómata explícito.
- [`02-architecture/06-windows-client.md`](./02-architecture/06-windows-client.md) — Cliente Windows con Wintun.

### 03 — Protocolos
- [`03-protocols/01-socks5.md`](./03-protocols/01-socks5.md) — Implementación de SOCKS5 (RFC 1928), incluyendo UDP ASSOCIATE.
- [`03-protocols/02-http-connect.md`](./03-protocols/02-http-connect.md) — Túnel HTTP CONNECT como fallback de compatibilidad.
- [`03-protocols/03-tun2socks-bridge.md`](./03-protocols/03-tun2socks-bridge.md) — El puente tun↔socks: por qué existe y cómo lo construimos en Rust.

### 04 — Ingeniería
- [`04-engineering/01-observability.md`](./04-engineering/01-observability.md) — Logs JSON, traces, integración MCP, dashboards.
- [`04-engineering/02-security.md`](./04-engineering/02-security.md) — Postura ofensiva, fuzzing, auditoría de dependencias, modelo de amenazas.
- [`04-engineering/03-cicd.md`](./04-engineering/03-cicd.md) — Pipelines GitHub Actions, builds multiarquitectura, releases.
- [`04-engineering/04-testing.md`](./04-engineering/04-testing.md) — Estrategia de pruebas: unit, fuzzing, integración, E2E.
- [`04-engineering/05-agent-guidelines.md`](./04-engineering/05-agent-guidelines.md) — Cómo deben comportarse los agentes de código en este repo.

### 05 — Diseño
- [`05-design/01-ui-design-system.md`](./05-design/01-ui-design-system.md) — Lenguaje visual, paleta, tipografía, componentes, microinteracciones.

### 06 — Investigación
- [`06-research/01-vpn2share-analysis.md`](./06-research/01-vpn2share-analysis.md) — Estado del arte: cómo funciona VPN2Share, sus límites, qué hacemos distinto.

### 07 — Roadmap
- [`07-roadmap/01-mvp-scope.md`](./07-roadmap/01-mvp-scope.md) — Qué entra y qué no entra en el MVP.
- [`07-roadmap/02-roadmap.md`](./07-roadmap/02-roadmap.md) — Hoja de ruta por trimestres.
- [`07-roadmap/03-risks-limitations.md`](./07-roadmap/03-risks-limitations.md) — Riesgos técnicos y legales conocidos, mitigaciones.

### Referencia
- [`08-glossary.md`](./08-glossary.md) — Glosario de términos.

## Cambios al blueprint

Los blueprints son contrato. Para modificarlos:

1. Abrir un PR etiquetado `blueprint`.
2. Justificar el cambio: nueva información técnica, contradicción interna, decisión estratégica.
3. Revisión humana obligatoria de Angel.
4. Si el cambio afecta a tareas en curso, actualizar `07-roadmap/02-roadmap.md` en el mismo PR.

## Versionado

Este blueprint es **v1.0 — abril 2026**. Cualquier PR aceptado que modifique cualquier archivo en `docs/` incrementa la versión menor (`v1.1`, `v1.2`...). Cambios de arquitectura mayor (rediseño de capa) incrementan la versión mayor.
