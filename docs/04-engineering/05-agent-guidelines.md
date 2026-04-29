# 04.05 — Reglas de Operación para Agentes de Código

## Para quién es este documento

Este documento es la **regla de la casa** para cualquier agente de código (LLM, copiloto, asistente CLI) que trabaje en Gravital Share. No es decorativo. Si un agente lo viola, sus PRs se rechazan en automático.

Es el complemento ejecutable del [`AGENT.md`](../../AGENT.md) en la raíz del repo.

## Principios irreductibles

1. **No inventes APIs.** Si no estás seguro de que un símbolo existe, búscalo. No "deduzcas" funciones de Rust o Kotlin que parecen razonables.
2. **No introduzcas dependencias sin justificar.** Cada nueva dependencia se documenta en el PR con: por qué, alternativa descartada, costo en binary size, postura de seguridad.
3. **No "limpies" código que funciona.** No reformatees, renombres ni "modernices" cosas no relacionadas a tu task.
4. **No silencies warnings.** Si `clippy` se queja, lo arreglas. No agregas `#[allow]` salvo que el comentario explique por qué.
5. **No commitees secrets.** Ni de prueba, ni "temporales". Nunca.
6. **No bypasses tests.** Si un test falla, el bug es real hasta probar lo contrario.

## Antes de tocar código

| Paso | Cómo |
|------|------|
| 1. Lee la sección de docs relevante | El `docs/00-INDEX.md` mapea cada feature a su doc |
| 2. Corre `./scripts/verify-toolchain.sh` | Garantiza que tu entorno es válido |
| 3. Corre la suite de tests baseline | `cargo test --workspace` y `./gradlew test` |
| 4. Lee el último log de CI del branch | Para no chocar con cambios en flight |

Si un agente abre un archivo sin haber leído su doc correspondiente, está fuera de proceso.

## Estilo de código

### Rust

- `cargo fmt` con `rustfmt.toml` del repo. Sin excepciones.
- `cargo clippy --all-targets --all-features -- -D warnings`.
- Nombres en `snake_case`, módulos en `snake_case`, types en `CamelCase`.
- Documentación en cada función pública con `///`. Ejemplo en cada función no trivial.
- `#[must_use]` en cualquier función que devuelva `Result` cuya ignorancia sería un bug.
- Ordenamiento de imports: estándar, externos, internos. Separados por línea en blanco.

### Kotlin

- `ktlint` con config del repo.
- `detekt` con baseline congelada.
- Nombres en `camelCase`, classes en `PascalCase`, constants en `UPPER_SNAKE_CASE`.
- `@Composable` siempre devuelve `Unit`, nunca tiene side effects fuera de `LaunchedEffect`/`SideEffect`.
- ViewModels exponen `StateFlow`, nunca `LiveData` ni `MutableStateFlow` público.
- Sin `runBlocking` en código de producción. Nunca.

## Convenciones de commits

```
<scope>: <imperative summary>

<body explaining why, not what>

Refs: <docs/path/to/spec.md>
```

| Scope | Cuándo |
|-------|--------|
| `engine` | Cambios en crates Rust del data plane |
| `ffi` | Cambios en la frontera Rust↔Kotlin |
| `android` | Cambios en módulos Android |
| `windows` | Cliente Windows |
| `docs` | Solo documentación |
| `ci` | Workflows, scripts, tooling |
| `chore` | Bumps de deps, refactor sin cambio funcional |

Ejemplo:

```
engine: handle 0xff RFC1928 method response

Server now correctly returns 0xff when no client method matches.
Previously closed the socket which fooled some clients into retry loops.

Refs: docs/03-protocols/01-socks5.md
```

## Pull requests

Plantilla obligatoria:

```markdown
## Qué cambia
<una o dos frases>

## Por qué
<el problema concreto que resuelve>

## Cómo lo verifico
- [ ] cargo test --workspace
- [ ] ./gradlew test
- [ ] Probado en dispositivo físico (modelo, Android version)
- [ ] Sin regresión en bench X (si aplica)

## Riesgos
<qué puede romperse, plan de rollback>

## Refs
- docs/...
- Issue #...
```

PRs sin esta plantilla rellenada no se revisan.

## Workflow del agente

```
1. Pull main
2. Lee el doc relevante
3. Crea branch feature/<scope>-<short-name>
4. Hace cambios mínimos al alcance
5. Corre lint + fmt + tests local
6. Commit con scope correcto
7. Push y abre PR
8. CI debe pasar antes de pedir review
9. Si falla CI: arregla, no `--force-push` para "limpiar"
```

## Cuándo parar y preguntar

Un agente **debe detenerse y pedir guía humana** en estos casos:

- El task implica modificar el FFI (cambia firma de `extern "C"`).
- El task implica cambiar la state machine de la sesión (transiciones, estados).
- El task implica nueva dependencia con licencia no en allowlist.
- El task implica subir privilegios o capturar `unsafe` adicional.
- El task implica modificar la política DNS (interceptación, fallback).
- El task implica cambios en `AndroidManifest.xml` que añadan permisos.
- El task implica modificar workflows de CI con secretos.

En esos casos, el agente abre un Issue antes de tocar código.

## Cosas que el agente nunca hace sin pedir

- `git push --force` a main o a branches compartidos.
- Borrar tags o releases.
- Modificar `.github/CODEOWNERS`.
- Modificar `LICENSE`, `NOTICE`, atribuciones.
- Cambiar `applicationId`, `package_name`, identidades de firma.
- Subir versiones mayores de Rust, Kotlin, NDK.
- Tocar `docs/06-research/*` (es record histórico, no código vivo).

## Logs estructurados

Todo log nuevo respeta el esquema en [`docs/04-engineering/01-observability.md`](01-observability.md). En Rust:

```rust
tracing::info!(
    kind = "session.transition",
    from = ?prev,
    to = ?next,
    ms = duration_ms,
    "state transition"
);
```

En Kotlin:

```kotlin
GravitalLog.event(
    kind = "ui.session.button.pressed",
    traceId = currentTraceId,
    payload = mapOf("action" to "start")
)
```

`println!`, `eprintln!`, `Log.d`, `Log.i` están **prohibidos** en código de producción.

## Cómo el agente lee y escribe docs

- Cada doc tiene su path canónico fijo. No se renombra sin razón estructural.
- Cross-references siempre relativas: `[`...`](../02-architecture/05-session-state-machine.md)`.
- Cuando se actualiza la API o el comportamiento documentado, **el doc se actualiza en el mismo PR**, no después.
- Si hay desalineación entre código y doc, el doc gana hasta que se discuta. Nunca asumir que el código es ground truth si contradice el blueprint.

## Mensaje cerrado al agente

> Este proyecto es infraestructura. La gente confía su tráfico a este código. Si algo no te queda claro, **te detienes y preguntas** antes de inferir. La velocidad sin precisión es ruido.

> **El tráfico cruza. Eso es todo lo que tienes que saber.** Y para que crucen bien, el código tiene que ser preciso, no ingenioso.

## Cross-references

- AGENT.md raíz: [`AGENT.md`](../../AGENT.md)
- Toolchain: [`docs/04-engineering/03-cicd.md`](03-cicd.md)
- Tests baseline: [`docs/04-engineering/04-testing.md`](04-testing.md)
- Postura de seguridad: [`docs/04-engineering/02-security.md`](02-security.md)
