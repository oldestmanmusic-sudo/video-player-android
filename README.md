# Video Player Android

Aplicación Android en Kotlin para reproducir videos y streams autorizados mediante URL, con listas M3U/M3U8, favoritos y una biblioteca de audio para Android Auto.

## Funcionalidades
- Reproducción en el teléfono con Media3/ExoPlayer.
- Importación de listas M3U y reproducción de streams HLS/M3U8.
- Lista de reproducción y favoritos guardados localmente.
- Servicio `MediaLibraryService` con categorías de playlist y favoritos para explorar y controlar audio desde Android Auto.
- En Android Auto se ofrece una experiencia de **audio solamente**; la pantalla del coche no reproduce video.

## Requisitos
- Android Studio y JDK 17.
- Android SDK 34.

## Ejecutar
Abre el proyecto en Android Studio, sincroniza Gradle y ejecútalo en un dispositivo o emulador. Para ver la integración de coche se necesita Android Auto compatible y la app instalada en el teléfono; la visibilidad también depende de las reglas de calidad y distribución de Google Play.

## Android Auto
La biblioteca del coche expone la playlist guardada y los favoritos. Primero carga una lista M3U o guarda favoritos desde la app del teléfono; luego abre Video Player Android desde la sección de medios de Android Auto. Los controles de reproducción se suministran mediante Media3.

La integración no habilita video en la pantalla del coche: Android Auto está pensado para controles de reproducción de audio y navegación segura. Usa contenido del que tengas autorización para reproducir y distribuir.
