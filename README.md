# BTVideoStream

Aplicación Android nativa que permite a un dispositivo **sin conexión a Internet** reproducir videos de YouTube y TikTok a través de un segundo dispositivo **con acceso a Internet**, usando **Bluetooth clásico (RFCOMM/SPP)** como canal de comunicación.

Desarrollada en **Kotlin** con **Jetpack Compose** y arquitectura **MVVM**, para el Examen Extraordinario de Desarrollo de Aplicaciones Móviles Nativas — ESCOM, IPN.

---

## Arquitectura general

```
┌─────────────────────────────┐        Bluetooth RFCOMM        ┌────────────────────────────────┐
│     Dispositivo A           │◄──────────────────────────────►│     Dispositivo B              │
│     (Servidor)              │                                 │     (Cliente)                  │
│                             │                                 │                                │
│  • Tiene Internet (Wi-Fi)   │   SearchRequest / VideoRequest  │  • Sin Internet (Wi-Fi/datos   │
│  • Busca en YouTube, TikTok │ ──────────────────────────────► │    desactivados)               │
│    y Google vía scraping    │                                 │  • Barra de búsqueda           │
│  • Descarga el video        │   SearchResults / VideoChunks   │  • Lista de resultados         │
│  • Parte el video en chunks │ ◄────────────────────────────── │  • Reproductor con ExoPlayer   │
│  • Caché local de videos    │                                 │  • Historial de reproducción   │
└─────────────────────────────┘                                 └────────────────────────────────┘
```

---

## Características implementadas

### Servidor (Dispositivo A)
- Búsqueda de videos en **YouTube**, **TikTok** y **Google/DuckDuckGo** (sin APIs de pago)
- Extracción de stream directamente desde la página de YouTube (`ytInitialPlayerResponse`)
- Sistema de **caché local** — los videos descargados previamente se sirven sin re-descargar
- Selección de calidad adaptativa: baja (360p), alta (720p), solo audio
- Fallbacks automáticos: Cobalt → Invidious (con proxy local) si el stream principal falla
- Log de actividad en tiempo real visible en pantalla

### Cliente (Dispositivo B)
- Búsqueda por título o palabras clave para YouTube y TikTok
- Lista de resultados con miniatura (URL), título, canal y duración
- Reproductor con **ExoPlayer**: reproducir/pausar, seek, barra de progreso
- Ensamblado de video en memoria a partir de chunks recibidos por Bluetooth
- **Historial** de videos reproducidos (persistido localmente)

### Comunicación Bluetooth
- Protocolo binario propio sobre RFCOMM (SPP UUID)
- Framing: `[type: 1 byte][length: 4 bytes BE][payload: N bytes]`
- Transferencia en chunks de 2048 bytes con índice de orden
- Reconexión automática con reintentos (hasta 5 intentos, backoff progresivo)
- Mecanismo de keepalive (STATUS cada 2 segundos) para prevenir timeout del enlace
- Indicador visual animado de estado de conexión (conectado / conectando / error)
- Notificaciones push al establecer o perder la conexión Bluetooth

### Interfaz de usuario
- **Dos temas** personalizables con Material 3:
  - **Guinda** — color representativo del IPN
  - **Azul** — color representativo de la ESCOM
- Cada tema soporta **modo claro y oscuro** — responde automáticamente al sistema
- Toggle manual para cambiar modo (claro / oscuro / sistema)
- Pantallas diferenciadas para Servidor y Cliente
- Selector de rol al iniciar la app

### Seguridad y privacidad
- Solicitud explícita de permisos Bluetooth adaptada a cada versión de Android (API 24–36)
- **Modo de privacidad**: reproducción privada que no registra el video en el historial
- Advertencia visual cuando el contenido proviene de una fuente no verificada (Invidious/TikWM)

---

## Stack tecnológico

| Componente | Tecnología |
|---|---|
| Lenguaje | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Arquitectura | MVVM (ViewModel + StateFlow) |
| Reproductor | AndroidX Media3 / ExoPlayer |
| Bluetooth | Android Bluetooth API (RFCOMM/SPP clásico) |
| Concurrencia | Kotlin Coroutines + Channel + SharedFlow |
| Persistencia | SharedPreferences (tema), archivos en cacheDir (video) |
| Red | HttpURLConnection nativo (sin librerías de terceros) |

> El proyecto **no utiliza ningún framework de terceros**. Toda la lógica de red, scraping, parseo JSON y comunicación Bluetooth es implementación propia sobre las APIs del SDK de Android.

---

## Estructura del proyecto

```
app/src/main/java/com/example/btvideostream/
│
├── MainActivity.kt                     # Entry point, navegación por roles, permisos
│
├── bluetooth/
│   ├── BluetoothManager.kt             # RFCOMM server/client, reconexión, chunking
│   ├── ConnectionState.kt              # Estado de conexión (sealed class)
│   └── protocol/
│       ├── Message.kt                  # Tipos de mensaje (SearchRequest, VideoChunk, etc.)
│       └── MessageSerializer.kt        # Serialización/deserialización binaria del protocolo
│
├── core/permissions/
│   └── BluetoothPermissions.kt         # Permisos BT por versión de Android
│
├── notifications/
│   └── BtNotificationManager.kt        # Notificaciones push de estado BT
│
├── server/
│   ├── Models.kt                       # VideoResult, StreamInfo, WebResult
│   ├── YouTubeService.kt               # Búsqueda y extracción de stream de YouTube
│   ├── TikTokService.kt                # Búsqueda y extracción de video de TikTok
│   ├── GoogleService.kt                # Búsqueda web vía DuckDuckGo
│   └── VideoRepository.kt             # Orquestador: caché, descarga, envío BT
│
└── ui/
    ├── screens/
    │   ├── RoleSelectionScreen.kt      # Pantalla inicial (elegir rol)
    │   ├── ServerScreen.kt             # UI del servidor (estado + log)
    │   └── ClientScreen.kt             # UI del cliente (búsqueda, lista, reproductor)
    ├── client/
    │   └── ClientViewModel.kt          # Lógica del cliente (BT, ensamblado de video)
    ├── server/
    │   └── ServerViewModel.kt          # Lógica del servidor (BT, búsquedas, descarga)
    ├── components/
    │   ├── ConnectionIndicator.kt      # Indicador animado de estado BT
    │   └── ThemeSwitcher.kt            # Selector de tema/modo oscuro
    └── theme/
        ├── Color.kt                    # Paletas Guinda (IPN) y Azul (ESCOM)
        ├── Theme.kt                    # ColorSchemes Material 3, AppTheme enum
        ├── ThemeController.kt          # Persistencia y cambio de tema
        └── Type.kt                     # Tipografía Material 3
```

---

## Protocolo de comunicación Bluetooth

El protocolo define un framing binario simple sobre el socket RFCOMM:

```
┌────────┬──────────────────┬─────────────────────────────────┐
│ type   │ length           │ payload                         │
│ 1 byte │ 4 bytes (Big-End)│ N bytes                         │
└────────┴──────────────────┴─────────────────────────────────┘
```

| Tipo | Valor | Payload |
|---|---|---|
| `PING` | 0x01 | vacío |
| `PONG` | 0x02 | vacío |
| `DISCONNECT` | 0x03 | vacío |
| `SEARCH_REQUEST` | 0x10 | `UTF-8: "source\|query"` |
| `SEARCH_RESULTS` | 0x11 | `UTF-8: JSON array de resultados` |
| `VIDEO_REQUEST` | 0x20 | `[quality: 1 byte][videoId: UTF-8]` |
| `VIDEO_CHUNK` | 0x21 | `[index: 4 bytes BE][bytes del chunk]` |
| `VIDEO_END` | 0x22 | vacío |
| `VIDEO_ERROR` | 0x23 | `UTF-8: mensaje de error` |
| `STATUS` | 0x30 | `UTF-8: mensaje de estado` |

Límite de seguridad: payloads mayores a **20 MB** son rechazados.

---

## Fuentes de video

| Fuente | Búsqueda | Stream |
|---|---|---|
| **YouTube** | Scraping de `ytInitialData` desde `youtube.com/results` | Extracción de `ytInitialPlayerResponse` desde la página de watch |
| **TikTok** | POST a `tikwm.com/api/feed/search` | GET a `tikwm.com/api/?url=...` |
| **Google** | Scraping de resultados DuckDuckGo | N/A (links externos) |

**Fallbacks para YouTube** (en orden):
1. Extracción directa desde la página `/watch` (primario)
2. Cobalt API (`api.cobalt.tools`)
3. Invidious con proxy local (`local=true`) — `iv.datura.network`, `invidious.privacyredirect.com`, `yewtu.be`

---

## Requisitos de hardware

| Dispositivo | Requisito |
|---|---|
| Servidor (A) | Android 7.0+ (API 24), Bluetooth 4.1+, conexión a Internet (Wi-Fi recomendado) |
| Cliente (B) | Android 7.0+ (API 24), Bluetooth 4.1+, **sin** acceso a Internet |

---

## Instalación y uso

### Prerrequisitos
- Android Studio Narwhal (2025.1.1) o superior
- Android SDK API 36
- Dos dispositivos Android físicos con Bluetooth habilitado (los emuladores no soportan BT)

### Compilar el proyecto
```bash
git clone https://github.com/DiegoFM02/BTVideoStream-Extra.git
cd BTVideoStream-Extra
./gradlew assembleDebug
```

El APK generado estará en:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Instalar en dispositivos
```bash
# Instalar en el servidor
adb -s <DEVICE_ID_SERVIDOR> install -r app/build/outputs/apk/debug/app-debug.apk

# Instalar en el cliente
adb -s <DEVICE_ID_CLIENTE> install -r app/build/outputs/apk/debug/app-debug.apk
```

### Uso
1. Abrir la app en ambos dispositivos
2. En el **Servidor**: seleccionar "Soy Servidor" → tocar "Esperar conexión"
3. En el **Cliente**: seleccionar "Soy Cliente" → elegir el servidor de la lista de dispositivos emparejados
4. Una vez conectados, buscar un video en el cliente (YouTube o TikTok)
5. Seleccionar un resultado — el servidor descarga el video y lo transfiere por Bluetooth
6. El video se reproduce automáticamente en el cliente

> **Nota sobre YouTube:** La reproducción de YouTube está sujeta a las restricciones actuales de los CDN de Google. Si el video no carga, se recomienda probar con TikTok, que funciona de forma estable.

---

## Limitaciones conocidas

- **YouTube CDN (HTTP 403):** Los URLs de stream de YouTube incluyen un parámetro `ip=` firmado criptográficamente que vincula la URL a la IP del dispositivo que la generó. Las extensiones de privacidad de IPv6 en Android pueden causar que la descarga se realice desde una dirección IP diferente, resultando en un error 403. Esta es una limitación de la arquitectura de seguridad de YouTube, no un bug de la aplicación.
- **Parámetro `n` de YouTube:** Requiere ejecución de JavaScript para transformarse; sin un motor JS, se elimina de la URL (YouTube puede limitar la velocidad pero sirve el contenido).
- **Velocidad Bluetooth:** La transferencia por RFCOMM es limitada (~2–3 Mbps teóricos, menos en la práctica). Videos de alta resolución pueden tardar varios segundos en transferirse.
- **Streams adaptativos de YouTube:** Los formatos con video y audio separados (DASH) no son compatibles; solo se usan formatos muxed (video+audio en un solo archivo).

---

## Permisos utilizados

| Permiso | Motivo |
|---|---|
| `BLUETOOTH_CONNECT` | Conectar/desconectar dispositivos (API 31+) |
| `BLUETOOTH_SCAN` | Descubrir dispositivos cercanos (API 31+) |
| `BLUETOOTH_ADVERTISE` | Anunciar el servidor (API 31+) |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | Bluetooth en API ≤ 30 |
| `ACCESS_FINE_LOCATION` | Requerido para BT discovery en API ≤ 30 |
| `POST_NOTIFICATIONS` | Notificaciones de estado de conexión (API 33+) |
| `INTERNET` | Solo el Servidor accede a Internet |
| `ACCESS_NETWORK_STATE` | Verificar disponibilidad de red |

---

## Información académica

| Campo | Valor |
|---|---|
| Institución | Instituto Politécnico Nacional |
| Escuela | Escuela Superior de Cómputo (ESCOM) |
| Unidad de aprendizaje | Desarrollo de Aplicaciones Móviles Nativas |
| Plan | Ingeniería en Sistemas Computacionales 2020 |
| Tipo | Examen Extraordinario |
| Fecha de entrega | 01 de julio de 2026 |

## Video demostración
Solo accesible por dominio de correo electronico del IPN:
https://correoipn-my.sharepoint.com/:v:/g/personal/dfloresm1300_alumno_ipn_mx/IQC7RxhW2f6fTLZ7PHFAkSM5AREiENaLq6KXLooHTZqhV0o?nav=eyJyZWZlcnJhbEluZm8iOnsicmVmZXJyYWxBcHAiOiJPbmVEcml2ZUZvckJ1c2luZXNzIiwicmVmZXJyYWxBcHBQbGF0Zm9ybSI6IldlYiIsInJlZmVycmFsTW9kZSI6InZpZXciLCJyZWZlcnJhbFZpZXciOiJNeUZpbGVzTGlua0NvcHkifX0&e=J5Vklj
