# 04.03 — CI/CD y Toolchain

## Tesis

> El día que un colaborador (humano o agente) no pueda compilar el motor en menos de 10 minutos desde un repo recién clonado, el proyecto está muerto. Lo demás es decoración.

La cadena de build de Gravital Share toca **dos lenguajes (Rust + Kotlin)**, **tres targets de Android**, **un target de Windows**, y **JNI** entre ellos. Sin reproducibilidad estricta, esto se convierte en un infierno en seis semanas.

## Toolchain congelado

Versiones explícitas en `rust-toolchain.toml` y `gradle/wrapper/gradle-wrapper.properties`:

| Componente | Versión | Razón |
|------------|---------|-------|
| Rust | 1.84.0 (stable) | Edición 2024 disponible |
| Android NDK | r27c (27.2.12479018) | Soporte estable de targets aarch64/armv7/x86_64 |
| Android SDK | API 35 | targetSdk = 35, minSdk = 26 |
| Kotlin | 2.0.21 | Compose Compiler integrado |
| Gradle | 8.10.2 | AGP 8.7.x |
| AGP | 8.7.3 | |
| JDK | Temurin 21 | LTS |
| `cargo-ndk` | 3.5.4 | Compila Rust → libs Android |
| `cargo-fuzz` | 0.12.x | Fuzzing |
| `cargo-deny` | 0.16.x | License/dep policy |
| `cargo-audit` | 0.21.x | Vulnerability DB |

> **Regla:** ninguna versión flota. Si el toolchain de un colaborador difiere por un parche, el CI bloquea el merge.

## DevContainer

El repo incluye `.devcontainer/devcontainer.json` y `Dockerfile` reproducible. Un colaborador con VS Code o un agente que entiende `devcontainer.json` arranca con cero configuración:

```Dockerfile
# .devcontainer/Dockerfile (resumen)
FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

ARG NDK_VERSION=27.2.12479018
ARG RUST_VERSION=1.84.0

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential pkg-config libssl-dev clang lld cmake ninja-build \
    openjdk-21-jdk unzip wget curl git ca-certificates

# Android cmdline-tools + NDK
RUN ./install_android_sdk.sh ${NDK_VERSION}

# Rust + targets
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- \
    -y --default-toolchain ${RUST_VERSION}
ENV PATH="/root/.cargo/bin:${PATH}"
RUN rustup target add \
    aarch64-linux-android \
    armv7-linux-androideabi \
    x86_64-linux-android \
    i686-linux-android \
    x86_64-pc-windows-msvc

RUN cargo install cargo-ndk@3.5.4 cargo-deny@0.16.4 cargo-audit@0.21.2 cargo-fuzz@0.12.0
```

`devcontainer.json` mapea volúmenes para `~/.gradle` y `target/` cacheados. **Tiempo objetivo de primer build full**: < 12 minutos en hardware moderno.

## Estructura del workspace

```
Gravital-Share/
├── crates/                     # Workspace Rust
│   ├── gravital-engine/        # Crate principal
│   ├── gravital-proto/
│   ├── gravital-stack/
│   ├── gravital-tun/
│   ├── gravital-socks/
│   ├── gravital-http/
│   ├── gravital-udpgw/
│   ├── gravital-dns/
│   ├── gravital-obs/
│   └── gravital-ffi/           # JNI wrappers + cdylib
├── android/                    # App Android
│   ├── app/
│   ├── core/
│   ├── engine-jni/             # Carga libgravital_engine.so
│   ├── ui/
│   └── feature-server/
├── windows/                    # Cliente Windows (fase 2)
├── docs/
├── scripts/
│   ├── build-android.sh
│   ├── build-windows.ps1
│   └── verify-toolchain.sh
├── .devcontainer/
├── .github/workflows/
└── rust-toolchain.toml
```

## Build local: comandos canónicos

```bash
# Verificar toolchain
./scripts/verify-toolchain.sh

# Compilar motor para los 4 targets de Android
cd crates/gravital-engine
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 \
    -o ../../android/engine-jni/src/main/jniLibs build --release

# Compilar APK
cd android
./gradlew :app:assembleRelease

# Tests
cargo test --workspace
cd android && ./gradlew test connectedAndroidTest
```

Estos tres pasos están encapsulados en `scripts/build-android.sh`. Un agente CLI **siempre** invoca el script, nunca los comandos sueltos.

## GitHub Actions

### Workflow 1: `ci-pr.yml` — corre en cada PR

| Job | Pasos | Tiempo objetivo |
|-----|-------|-----------------|
| `lint-rust` | `cargo fmt --check`, `cargo clippy -- -D warnings` | 2 min |
| `lint-kotlin` | `ktlint` + `detekt` | 1.5 min |
| `test-rust` | `cargo test --workspace --all-features` | 3 min |
| `audit` | `cargo audit`, `cargo deny check`, OWASP DC en Gradle | 2 min |
| `fuzz-smoke` | `cargo fuzz run` 60s por target | 5 min |
| `build-android` | `cargo ndk` para los 4 ABIs + `assembleDebug` | 8 min |
| `test-android-unit` | `./gradlew test` | 3 min |

Concurrent. Total wall-clock: **~10 min** con buen caching.

### Workflow 2: `ci-main.yml` — corre al merge a main

Todo lo anterior, más:
- `build-android-release` con firma debug
- `instrumentation-tests` en emulador (API 26, 30, 35)
- `e2e-soak` corto (5 min)
- Subida del APK a release artifacts

### Workflow 3: `nightly-fuzz.yml`

Cron 02:00 UTC. Corre fuzzing intensivo (2 h por target) y publica corpus + crashes en un Issue automatizado.

### Workflow 4: `release.yml` — manual / tag

Disparado por tag `vX.Y.Z`:
1. Build release multi-ABI.
2. Firma del APK con clave en HSM (acceso vía OIDC).
3. Genera `.aab` para Play Store.
4. Crea GitHub Release con changelog generado.
5. Sube a track interno de Play Console.

## Cache strategy

| Cache | Key |
|-------|-----|
| `~/.cargo/registry`, `~/.cargo/git` | `Cargo.lock` hash |
| `target/` | `Cargo.lock` + rustc version |
| `~/.gradle/caches` | `gradle-wrapper.properties` + `build.gradle.kts` hash |
| Android SDK | sdk version |
| NDK toolchain | NDK version |

Sin caching de `target/`, el build pasa de 8 min a 30 min. Es la optimización que más rinde.

## Reproducibilidad de releases

| Garantía | Cómo |
|----------|------|
| Mismos bytes en mismo commit | `Cargo.lock` y `gradle.lockfile` commiteados |
| Sin timestamps embebidos | `SOURCE_DATE_EPOCH` exportado en CI |
| Sin paths absolutos | `--remap-path-prefix` en `RUSTFLAGS` |
| Misma versión de NDK | imagen Docker fija para releases |

## Code signing

```yaml
# .github/workflows/release.yml — fragmento
- name: Sign APK
  uses: r0adkll/sign-android-release@v1
  with:
    releaseDirectory: android/app/build/outputs/apk/release
    signingKeyBase64: ${{ secrets.RELEASE_KEYSTORE_B64 }}
    alias: ${{ secrets.RELEASE_KEY_ALIAS }}
    keyStorePassword: ${{ secrets.RELEASE_KEYSTORE_PASS }}
    keyPassword: ${{ secrets.RELEASE_KEY_PASS }}
```

Las claves de firma viven en GitHub Secrets cifrados con OIDC y expiran. La clave de release **se rota anualmente** y la fingerprint vieja se mantiene en el manifest para upgrades.

## Verificación local del build de CI

```bash
# Reproduce localmente lo que hace el CI de PR
./scripts/ci-local.sh
```

El script monta los mismos pasos en orden. Si pasa local, pasa en CI (modulo flakiness de tests instrumentados).

## Tests

- Smoke test: `./scripts/build-android.sh` produce un APK que arranca en emulador y muestra la pantalla principal.
- Test del CI: cualquier cambio en `.github/workflows/*.yml` requiere que un dry-run con `act` pase.
- Test de reproducibilidad: el SHA-256 del APK firmado en dos máquinas distintas con el mismo NDK debe coincidir.

## Cross-references

- Hardening de seguridad: [`docs/04-engineering/02-security.md`](02-security.md)
- Estrategia de tests: [`docs/04-engineering/04-testing.md`](04-testing.md)
- Stack Rust completo: [`docs/02-architecture/02-data-plane-rust.md`](../02-architecture/02-data-plane-rust.md)
- Reglas para agentes que ejecutan builds: [`docs/04-engineering/05-agent-guidelines.md`](05-agent-guidelines.md)
