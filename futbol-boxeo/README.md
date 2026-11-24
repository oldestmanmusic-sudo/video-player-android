# Futboxeo ⚽🥊

Juego para Android de **fútbol y boxeo a la vez**: dos equipos de 4 jugadores en un campo
rodeado de cuerdas de ring. Se gana metiendo más goles, pero puedes pegar a los rivales:
si le pegas al que lleva el balón, lo suelta; si a alguien se le acaba la vida, cae
**K.O.** durante 3 segundos y su equipo juega con uno menos.

## Controles (pantalla horizontal)
| Zona | Acción |
|---|---|
| Mitad izquierda | Joystick: pon el dedo en cualquier sitio y deslízalo |
| **PUÑO** | Golpe al rival que tengas delante (alterna puño izquierdo y derecho) |
| **ESQUIVA** | Impulso rápido; mientras dura, los golpes no te tocan |
| **PASE** | Con balón: pase al compañero mejor situado. Sin balón: **CAMBIO** de jugador |
| **TIRO** | Chut a portería; inclina el joystick arriba/abajo para apuntar |

Los partidos duran 3 minutos. La vida se recupera poco a poco y, tras un K.O., el jugador
vuelve con la vida llena. La pantalla de inicio muestra un partido de demostración CPU contra CPU.

## Compilar
- **Automático:** cada `push` que cambie `futbol-boxeo/` lanza el workflow
  *Build Futboxeo APK*, que publica el APK como artefacto y como versión
  `futboxeo-N` en *Releases* (sin marcarla como «latest», para no interferir con
  el botón Actualizar del reproductor de vídeo).
- **Android Studio:** abre la carpeta `futbol-boxeo/` como proyecto (JDK 17, SDK 34) y ejecútalo.

Sin dependencias externas: todo está dibujado con `Canvas` en un `SurfaceView`.
- `Game.kt` — reglas, física, golpes e IA (Kotlin puro, sin Android).
- `GameView.kt` — dibujo, joystick y botones táctiles, vibración y sonidos.
- `MainActivity.kt` — pantalla completa horizontal.
