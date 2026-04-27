# 02.03 — Control Plane en Kotlin (Android)

## Filosofía

La app Android no hace red. Hace:

1. UI y experiencia de usuario.
2. Permisos y consentimiento.
3. Ciclo de vida del servicio en primer plano.
4. Llamadas a `VpnService` / hotspot.
5. Puente con el motor Rust.
6. Telemetría agregada y reporte al usuario.

Cualquier línea de Kotlin que parsee TCP es un bug.

## Targets

- **minSdk**: 26 (Android 8 Oreo).
- **targetSdk / compileSdk**: 35 (Android 15).
- **Lenguaje**: Kotlin 2.0+.
- **JDK**: 17.
- **UI**: Jetpack Compose 1.7+.
- **Build**: Gradle 8.7+, AGP 8.5+.

## Estructura del módulo Android

```
android/
├── settings.gradle.kts
├── gradle/libs.versions.toml         # version catalog
├── app/                              # módulo principal con UI
│   ├── src/main/kotlin/io/gravital/share/
│   │   ├── App.kt                    # Application class
│   │   ├── ui/                       # pantallas Compose
│   │   ├── domain/                   # ViewModels, casos de uso
│   │   ├── data/                     # repositorios, datasources
│   │   ├── service/                  # VpnService, Hotspot service
│   │   ├── ffi/                      # bindings al engine
│   │   └── obs/                      # observabilidad cliente
│   └── src/main/AndroidManifest.xml
├── core-ffi/                         # módulo binding Rust ↔ Kotlin
│   └── src/main/kotlin/io/gravital/share/ffi/EngineBridge.kt
├── core-design/                      # Gravital Design System (compartido)
└── core-telemetry/                   # capa común de eventos
```

## Permisos

```xml
<!-- AndroidManifest.xml — solo lo estrictamente necesario -->
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
```

**Lo que NO pedimos**:
- ❌ `READ_PHONE_STATE` (no lo necesitamos).
- ❌ `ACCESS_FINE_LOCATION` (Wi-Fi state es suficiente para nuestro caso).
- ❌ `WRITE_EXTERNAL_STORAGE` (no escribimos archivos del usuario).
- ❌ `BIND_VPN_SERVICE` aparece en el manifiesto del Service, no como uses-permission.

## Servicios principales

### `GravitalVpnService` (modo cliente)

Hereda de `android.net.VpnService`. Responsable de:

1. Pedir consentimiento al usuario (`VpnService.prepare(...)`).
2. Construir el túnel:
   ```kotlin
   val tun = Builder()
       .setSession("Gravital Share")
       .addAddress("10.42.0.2", 32)
       .addRoute("0.0.0.0", 0)
       .addRoute("::", 0)
       .addDnsServer(config.dnsServer.ifEmpty { "1.1.1.1" })
       .setMtu(config.mtu.coerceIn(1200, 1500))  // default 1280
       .setBlocking(false)
       .establish()
   ```
3. Pasar el `tun.detachFd()` al engine Rust.
4. Recibir callbacks de estado y propagarlos al `SessionManager`.

**Rules**:
- Es `FOREGROUND_SERVICE` con `foregroundServiceType="specialUse"`.
- La notificación es **honesta**: muestra el estado real de la sesión, no marketing.
- El `Builder.addDisallowedApplication(packageName)` se llama para excluir la propia Gravital Share (anti-bucle a nivel de app).
- En el `onCreate`, se carga la lib nativa: `System.loadLibrary("gravital_engine")`.

### `GravitalServerService` (modo servidor)

Es un `ForegroundService` normal (no VpnService). Responsable de:

1. Verificar que el hotspot esté activo (consulta de `WifiManager`).
2. Iniciar el motor en modo servidor con la IP local del hotspot.
3. Mostrar notificación con el endpoint `192.168.43.1:1080` y un QR para que el cliente escanee.
4. Mostrar el contador de clientes conectados.

**Detalles**:
- `foregroundServiceType="dataSync"` o `"specialUse"` según política de Play Store al momento del lanzamiento.

## Capa FFI desde Kotlin

### `EngineBridge.kt`

```kotlin
object EngineBridge {
    init {
        System.loadLibrary("gravital_engine")
    }

    external fun init(configJson: String): Int
    external fun startClient(tunFd: Int, configJson: String): Int
    external fun startServer(configJson: String): Int
    external fun stop(): Int
    external fun shutdown(): Int

    external fun getState(): String      // returns JSON
    external fun getStats(): String      // returns JSON

    external fun setEventCallback(callback: EventCallback)

    fun interface EventCallback {
        fun onEvent(jsonEvent: String)
    }

    const val FFI_VERSION_EXPECTED = 1
    external fun ffiVersion(): Int
}
```

### Reglas de uso del bridge

- Siempre llamar desde `Dispatchers.IO`.
- El callback `onEvent` puede llegar en cualquier hilo nativo. Reemitir a un `SharedFlow<EngineEvent>` con `replay = 0` y `extraBufferCapacity = 64`.
- En `Application.onCreate`, validar `ffiVersion() == FFI_VERSION_EXPECTED`. Si no, mostrar pantalla de error en lugar de crash.

## Arquitectura de la UI

### Patrón

MVVM con `StateFlow<UiState>` por pantalla. UI puramente reactiva.

```kotlin
data class HomeUiState(
    val mode: SessionMode,             // CLIENT / SERVER / IDLE
    val sessionState: SessionState,    // ver state machine
    val throughputBps: Long = 0,
    val connectedClients: Int = 0,
    val errorBanner: ErrorBanner? = null,
)

class HomeViewModel(
    private val sessionManager: SessionManager,
) : ViewModel() {
    val uiState: StateFlow<HomeUiState> = combine(
        sessionManager.mode,
        sessionManager.state,
        sessionManager.throughput,
        sessionManager.clientCount,
    ) { mode, state, tp, clients ->
        HomeUiState(mode, state, tp, clients)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState(SessionMode.IDLE, SessionState.Idle))

    fun toggle() = sessionManager.toggle()
}
```

### Pantallas del MVP

1. **Home / Toggle** — el botón principal, muestra estado, ofrece "Compartir" o "Conectarme".
2. **Selector de modo** — primera vez: pregunta si este teléfono va a compartir o a recibir.
3. **Conexión** — pantalla activa durante la sesión, con throughput, IP, latencia, botón parar.
4. **Diagnóstico** — colapsable: detalles técnicos (estado del túnel, DNS, fugas, dispositivos conectados).
5. **Ajustes** — DNS preferido, MTU, modo de fallback, telemetría opt-in/out.
6. **Sobre** — versión, licencia, link a docs públicas.

## Ciclo de vida y reactividad

### Por qué un `SessionManager` único

- Es un singleton vinculado al `Application`.
- Mantiene un `MutableStateFlow<SessionState>` que la UI observa.
- Recibe eventos del engine vía `EngineBridge.EventCallback` y los traduce a transiciones de la state machine (ver `05-session-state-machine.md`).
- Sobrevive a destrucciones de Activity, no a destrucción del proceso.

```kotlin
@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val engineBridge: EngineBridge,
    private val telemetry: TelemetrySink,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()
    
    private val _mode = MutableStateFlow(SessionMode.IDLE)
    val mode: StateFlow<SessionMode> = _mode.asStateFlow()
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    init {
        engineBridge.setEventCallback { jsonEvent ->
            scope.launch { handleEvent(jsonEvent) }
        }
    }
    
    fun startClient(tunFd: Int, config: ClientConfig) { /* ... */ }
    fun startServer(config: ServerConfig) { /* ... */ }
    fun stop() { /* ... */ }
    fun toggle() { /* ... */ }
    
    private suspend fun handleEvent(json: String) { /* parse, transition, emit */ }
}
```

## Inyección de dependencias

**Hilt** estándar.

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    @Provides @Singleton
    fun provideEngineBridge(): EngineBridge = EngineBridge
}
```

## Pruebas

- **Unit tests** de ViewModels con `kotlinx-coroutines-test`.
- **Tests instrumentados** (`androidx.test`) para el flow `VpnService` con FD simulado.
- **Robolectric** para servicios cuando no necesitamos un emulador.
- **Compose UI tests** con `createComposeRule()` para flows críticos (toggle, error banners).

## Localización

- **Idiomas día uno**: español (LATAM neutral), inglés.
- **Día 90**: portugués (Brasil), francés.
- Strings en `strings.xml` con prefijo por pantalla: `home_*`, `diag_*`, `err_*`.
- Ningún string concatenado a mano. Todo vía `stringResource(R.string.x, args)`.

## Manejo de errores en UI

Política de tres canales:

1. **Banner persistente** (`ErrorBanner`) — error que afecta la sesión actual y no se resuelve solo. Aparece arriba de Home, persiste hasta que el usuario lo descarta o se resuelve.
2. **Toast / Snackbar** — feedback transitorio (acción copiada, ajuste guardado).
3. **Pantalla de error completa** — solo en arranque catastrófico (FFI version mismatch, lib nativa no carga).

**Texto de error**: siempre tres componentes — qué pasó, por qué, qué hacer ahora. Nunca códigos de error sueltos al usuario sin contexto.

## Anti-bucle de cifrado

Un detalle crítico que muchos clientes VPN olvidan:

```kotlin
// En GravitalVpnService.Builder
builder.addDisallowedApplication(packageName)
// para no enviar nuestro propio tráfico al túnel y crear un bucle
```

Y del lado del engine, todo socket que el cliente abre hacia el proxy del anfitrión va por:

```kotlin
val socket = Socket()
vpnService.protect(socket)  // marca el socket para que ignore las rutas de la VPN
socket.connect(InetSocketAddress("192.168.43.1", 1080))
```

Esto se hace desde Kotlin **antes** de pasarle el `Socket.fileDescriptor` al engine Rust si el approach es ese, o desde JNI invocando la VM-method `protect()`. La decisión actual es **abrir todos los sockets externos del cliente desde Kotlin, protegerlos, y pasarlos como `RawFd` al engine**. Justificación: simplifica el lado Rust, mantiene la lógica de `protect()` en su lugar natural.

## Notificaciones

- Una notificación foreground por servicio activo.
- Categoría `service`, prioridad `LOW`, suena nunca.
- Texto dinámico que refleja el estado real (ej. *"Reconectando…"*).
- Sin emojis. Sin signos de exclamación. Tono institucional.

## Pruebas en dispositivos reales

Mínimo en CI manual antes de cada release:
- **Samsung Galaxy A14** (gama media reciente).
- **Xiaomi Redmi Note 11**.
- **Pixel 6 / 8**.
- Operadores en LATAM probados manualmente: Tigo, Claro, Movistar, Digicel.
