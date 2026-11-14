# Control TV

Control remoto propio para el Chromecast con Google TV. Habla directo con la tele por Wi‑Fi
(protocolo Android TV Remote v2, puertos 6467 y 6466), sin servidor.

## Compilar e instalar

1. Abrí la carpeta `control-tv` en Android Studio (está en el menú Inicio).
2. Conectá el celu con la depuración USB activada y apretá Run (▶).

Sin Android Studio, desde una terminal:

```
gradlew assembleDebug        → app/build/outputs/apk/debug/app-debug.apk
gradlew testDebugUnitTest    → pruebas + capturas de pantalla
```

## Probar sin la tele

`gradlew testDebugUnitTest` corre 33 pruebas:

- **ProtocolTest**: bytes exactos de cada mensaje, el secreto de vinculación y el certificado.
- **FakeTvTest**: una tele de mentira con TLS de verdad (`FakeTv.kt`). Prueba la vinculación con el
  código, el código mal tipeado, la sesión de control (teclas, texto, apps, ping, volumen),
  el celu desconocido, la huella cambiada y la reconexión.
- **ScreenshotTest**: genera las capturas de `docs/diseno/` (quedan en `app/build/outputs/roborazzi/`).

## Diseño

Oscuro, porque se usa de noche frente a la tele: fondo grafito y un solo color de acento, ámbar.

| | |
|---|---|
| Fondo / superficies | `#0D0F13` · `#15181E` · `#1D2129` |
| Acento (OK, enviar, selección) | `#FF9F45` |
| Conectado / error | `#3DDC97` · `#FF5E5E` |

- **Control**: línea de estado arriba (punto de conexión, app en pantalla, volumen, Ajustes).
  En el medio, el pad circular con el OK ámbar como protagonista; abajo, Atrás · Inicio ·
  Encendido y, más abajo, volumen · silencio · play/pausa. La barra de texto queda cerca del
  pulgar y del teclado. Si el teclado ocupa la pantalla, se esconden las filas y queda el pad.
- **Apps**: grilla de tarjetas con inicial de color. La app abierta en la tele se marca en ámbar,
  y arriba aparece "En pantalla" con el botón para agregarla.
- **Vinculación**: dos pasos (IP y código), con el código en 6 casillas grandes.

![Control](docs/diseno/01_control.png)

Las 13 pantallas están en [docs/diseno](docs/diseno).

## Cómo se comportan los botones

- Las flechas, el volumen y borrar se repiten al mantenerlos apretados (manda toques seguidos).
- OK, Atrás e Inicio hacen pulsación larga en la tele (`START_LONG` → `END_LONG`): por ejemplo, el menú contextual.
- Encendido y play/pausa no tienen pulsación larga, para no apagar la tele sin querer.

## Diferencias con el plan

- **Protobuf a mano** (el plan B del plan): `tv/Proto.kt` (el codificador, ~130 líneas) y `tv/Messages.kt` (los mensajes).
  Así no se depende del plugin de protobuf con AGP 9, que es nuevo, y hay una dependencia menos.
  Los `.proto` siguen en `app/src/main/proto` como referencia de los números de campo.
- **Flechas que se repiten** mandando toques cortos seguidos, en vez de `START_LONG`/`END_LONG`:
  así el desplazamiento por listas anda seguro.
- **"Hay que vincular de nuevo"**: si la huella de la tele no coincide, se pide enseguida. Si la
  tele corta el TLS, se espera a 3 cortes seguidos: según el momento, el rechazo llega como alerta
  TLS o como "conexión abortada", y así un corte casual no manda a vincular sin motivo.
- El volumen se ve solo en la línea de estado (el balancín no lo repite).

## Lo que falta probar con la tele real

- Que la tele acepte el certificado y el secreto (contra la tele de mentira anda).
- El texto en apps reales y el modo "en vivo" (experimental).
- Qué links de la lista de apps abren bien en tu Chromecast.
- El permiso de red local en un celu con Android 17.

Si algo falla, Ajustes → Log de conexión muestra cada mensaje que va y viene, y se puede copiar.
