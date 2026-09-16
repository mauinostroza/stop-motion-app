# StopMotion

Aplicación Android nativa (Kotlin + Jetpack Compose) para crear videos
stop motion de forma simple: toma fotos desde la cámara o elige imágenes
de la galería, reordénalas, previsualiza la animación y expórtalas como
un video MP4.

## Características

- **Captura con CameraX** con preview en tiempo real.
- **Onion skinning**: el último frame capturado se superpone
  semitransparente sobre el preview para facilitar la animación cuadro
  a cuadro.
- **Cuadrícula (grid)** de 3x3 conmutable para mejor composición.
- **Captura automática (intervalómetro)**: dispara fotos sin intervención
  cada N segundos (1–60 s, configurable con un stepper +/-, por defecto
  3 s). Muestra un anillo de cuenta regresiva alrededor del botón de
  disparo y el texto "Próxima foto en Xs". Se detiene automáticamente
  ante un error de cámara o pérdida de permiso, y se pausa/reanuda con
  el ciclo de vida de la pantalla. El último intervalo elegido se
  recuerda entre sesiones (el modo en sí siempre arranca apagado).
- **Selector desde galería** moderno con Photo Picker (multi-selección).
- **Reordenar / eliminar** frames antes de exportar.
- **Preview animado** con slider de FPS en vivo (1–30).
- **Exportación a MP4** vía `MediaCodec` + `MediaMuxer` (H.264), con
  timestamps deterministas para conservar la duración correcta.
- **Resolución configurable**: 720p, 1080p, cuadrado 1:1 y vertical 9:16.
- **Compartir** el MP4 final vía `Intent.ACTION_SEND` y/o guardar en la
  galería del sistema (MediaStore `Movies/StopMotion`).
- **Material Design 3** con esquema dinámico (Material You) en Android 12+.

## Requisitos

- Android Studio Hedgehog (2023.1.1) o superior.
- Android SDK con `compileSdk = 34`.
- `minSdk = 26` (Android 8.0).
- JDK 17.

## Estructura del proyecto

```
StopMotionApp/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml          # Catálogo de versiones (version catalog)
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── kotlin/com/stopmotion/app/
        │   ├── StopMotionApp.kt              # Application class
        │   ├── MainActivity.kt               # Single activity + NavHost
        │   ├── data/
        │   │   ├── Frame.kt
        │   │   ├── Project.kt
        │   │   ├── ExportResolution.kt      # Presets 720p/1080p/1:1/9:16
        │   │   ├── ProjectRepository.kt      # Proyecto persistido localmente
        │   │   └── ServiceLocator.kt
        │   ├── encoder/
        │   │   └── BitmapToVideoEncoder.kt  # MediaCodec + MediaMuxer
        │   ├── ui/
        │   │   ├── capture/                  # Captura + onion + grid
        │   │   │   ├── CaptureViewModel.kt
        │   │   │   └── CaptureScreen.kt
        │   │   ├── frames/                   # Gestión de fotos
        │   │   │   ├── FramesViewModel.kt
        │   │   │   └── FramesScreen.kt
        │   │   ├── preview/                  # Preview animado
        │   │   │   └── PreviewScreen.kt
        │   │   ├── export/                   # Export MP4 + compartir
        │   │   │   ├── ExportViewModel.kt
        │   │   │   └── ExportScreen.kt
        │   │   └── theme/                    # Material 3 theme
        │   │       ├── Color.kt
        │   │       ├── Theme.kt
        │   │       ├── Type.kt
        │   │       └── Shape.kt
        │   └── util/
        │       └── BitmapLoader.kt          # Downsampled decode helper
        └── res/
            ├── drawable/ic_launcher_foreground.xml
            ├── mipmap-anydpi-v26/ic_launcher.xml
            ├── values/{strings,colors,themes}.xml
            ├── values-night/themes.xml
            └── xml/file_paths.xml            # FileProvider paths
```

## Cómo abrir y compilar

1. Copia la carpeta `StopMotionApp/` a tu equipo de desarrollo.
2. Android Studio → `File` → `Open` → selecciona la carpeta `StopMotionApp/`.
3. Espera a que Gradle sincronice. Si Android Studio solicita instalar
   SDK 34 o el toolchain de Kotlin 2.0, acepta.
4. Conecta un dispositivo físico (recomendado para probar la cámara) o
   inicia un emulador con API ≥ 26.
5. Botón **Run** ▶ o `Shift+F10`.

El repositorio incluye `gradlew`, `gradlew.bat` y el wrapper de Gradle 8.7.
En macOS/Linux ejecuta `./gradlew assembleDebug`; en Windows,
`gradlew.bat assembleDebug`.

## Cómo funciona el encoder de video

El corazón del proyecto es la clase `BitmapToVideoEncoder`. Utiliza entrada
YUV420 por `ByteBuffer` (en vez de dibujar a una `Surface`) para controlar el
timestamp de cada imagen:

1. **Configura el codificador**: crea un `MediaCodec` para `video/avc`
   (H.264), selecciona un formato YUV420 soportado, bitrate y FPS.
2. **Para cada frame**, escala la imagen con letterboxing negro, la convierte
   a YUV420 y la encola en un buffer de entrada.
3. **Asigna el PTS explícitamente**: el frame N recibe un timestamp de
   `N × 1.000.000 / FPS` microsegundos (equivalente a la duración configurada).
4. **Drena la salida** hacia `MediaMuxer` y, al finalizar, envía EOS antes de
   cerrar el MP4.

El callback `onProgress(Float)` permite que el UI muestre una barra de
progreso (`LinearProgressIndicator`) actualizada conforme avanza la
codificación.

## Permisos

| Permiso | Razón | Cuándo se solicita |
|---|---|---|
| `android.permission.CAMERA` | Tomar fotos con CameraX | En `CaptureScreen` al entrar |
| `WRITE_EXTERNAL_STORAGE` (max SDK 28) | Escribir MP4 en almacenamiento compartido en Android < 10 | Solo aplica a API 26-28 |
| `READ_EXTERNAL_STORAGE` (max SDK 32) | Leer imágenes seleccionadas del picker en Android < 13 | Solo aplica a API 26-32 |
| Photo Picker nativo (Android 13+) | No requiere permiso explícito | API 33+ |

Las exportaciones a la galería se realizan vía `MediaStore.Video` con la
carpeta relativa `Movies/StopMotion`, sin necesidad de permisos
`WRITE_EXTERNAL_STORAGE` en Android 10+.

## Flujo de uso

1. **Captura** (`CaptureScreen`): botón flotante verde grande para
   tomar foto; mientras tanto se muestra el último frame en
   semi-transparente (onion skin) para alinear el movimiento. Botón de
   cuadrícula (3x3) y de flip de cámara en la parte superior.
2. **Gestión de frames** (`FramesScreen`): lista vertical con thumbnails;
   arrastrar con el handle para reordenar (botones up/down), tap en el
   icono de basura para eliminar. Botón "Preview" si hay 2+ frames.
3. **Preview** (`PreviewScreen`): reproduce la animación en bucle, con
   un slider para ajustar FPS en tiempo real y botón "Exportar".
4. **Exportar** (`ExportScreen`): elegir resolución con chips, ajustar
   FPS, botón "Crear video". Al terminar: "Compartir" o
   "Guardar en galería".

## Extensiones futuras sugeridas

- Añadir audio / música de fondo al MP4 (mux track de audio AAC).
- Migrar la persistencia local actual a Room al incorporar múltiples proyectos.
- Soporte para orientación landscape de la cámara.
- Filtros LUT / Look-Up Table para estilo cinematográfico.
- Modo "loop" (boomerang) en la exportación.

## Licencia

Apache License 2.0. El proyecto no incluye publicidad, rastreo ni servicios
propietarios, por lo que puede prepararse para distribución mediante F-Droid.

## Obtener un APK

- Local: abre el proyecto en Android Studio y usa **Build > Build APK(s)**.
- Automático: sube el proyecto a GitHub y ejecuta el workflow **Android APK**;
  el artefacto `StopMotion-debug-apk` contendrá el APK instalable.

El APK debug sirve para instalación directa y pruebas. Para publicar en
F-Droid se necesita además un repositorio Git público, un identificador de
aplicación definitivo, metadata Fastlane y una versión etiquetada.
