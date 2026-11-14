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

`gradlew testDebugUnitTest` corre 35 pruebas:

- **ProtocolTest**: bytes exactos de cada mensaje, el secreto de vinculación y el certificado.
- **FakeTvTest**: una tele de mentira con TLS de verdad (`FakeTv.kt`). Prueba la vinculación con el
  código, el código mal tipeado, la sesión de control (teclas, texto, apps, ping, volumen),
  el celu desconocido, la huella cambiada, la reconexión, que muchos reinicios seguidos no
  dejen sesiones abiertas de más y que mandar comandos sin parar no corte la sesión.
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
  pulgar: el campo y el micrófono (borrar y mandar se hacen con el teclado del celu). Si el
  teclado ocupa la pantalla, se esconden las filas y queda el pad.
- **Apps**: grilla de tarjetas con el ícono de cada app. El ícono oficial se baja una vez de
  Google Play (por el nombre de paquete) y queda guardado; al editar una app se puede elegir una
  imagen propia. Sin ícono se ve una inicial de color, que es lo que muestran las capturas (así
  el repo no lleva logos de marcas). La app abierta en la tele se marca en ámbar, y arriba
  aparece "En pantalla" con el botón para agregarla a la lista.
- **Vinculación**: dos pasos (IP y código), con el código en 6 casillas grandes.

![Control](docs/diseno/01_control.png)

Las 13 pantallas están en [docs/diseno](docs/diseno).

## Cómo se comportan los botones

- El texto se manda con la tecla de acción del teclado. Con "Texto en vivo" cada letra y cada
  borrado van al toque, y esa tecla da Enter en la tele.
- El micrófono abre el dictado de Google (es-AR) y manda lo que dijiste.
- Si la tele no tiene un buscador abierto, al recibir texto lo abre sola, pero descarta ese
  primer texto. La app espera hasta 2,5 s a que aparezca el campo, manda el texto de nuevo y
  después el Enter. Si no aparece (por ejemplo, dentro de Netflix), avisa.
- Las flechas y el volumen se repiten al mantenerlos apretados (manda toques seguidos).
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
- El volumen se ve solo en la línea de estado (el balancín no lo repite). Si la tele no informa
  un nivel (por ejemplo, el Chromecast manejando el volumen de la tele por HDMI manda `0/0`),
  no se muestra ningún número.
- **Enter después del texto, solo con un campo abierto**: un Enter sin campo "toca" lo que esté
  seleccionado en pantalla (en la prueba real abrió Prime Video desde Inicio), así que primero
  se espera a que la tele abra el buscador.
- **Conexión muerta**: se da por muerta si pasan 16 s sin que *nadie* mande nada. La tele no hace
  ping mientras recibe comandos, así que lo que manda el celu también cuenta, como en la librería
  de referencia.

## Probado con la tele real

13/11/2026 · Chromecast con Google TV en una tele Sony · Galaxy S25 Ultra con Android 17.

| Qué | Resultado |
|---|---|
| Permiso de red local (Android 17) | ✅ |
| Vinculación con el código | ✅ |
| Conexión, pings y reconexión al volver a la app | ✅ |
| Flechas, OK, Atrás, Inicio, borrar y Enter | ✅ |
| Volumen, silencio y play/pausa | Salen sin errores; falta verlos en pantalla |
| Abrir apps por link (YouTube, Netflix) | ✅ |
| Íconos de las apps desde Google Play | ✅ |
| Texto al enviar, en el buscador de Google TV | ✅ |
| Texto en vivo (letra por letra y borrados) | ✅ |
| Dictado por voz desde Inicio (abre el buscador y busca) | ✅ |

Lo que se aprendió:

- La tele avisa un campo de texto abierto con `ime_key_inject` y contadores distintos de 0, y
  después de cada cambio manda `ime_show_request` con el contador de campo nuevo.
- Sin campo abierto, los contadores llegan en 0. En Inicio, la tele abre el buscador al recibir
  texto, pero ese primer texto se pierde.
- Mientras recibe comandos, la tele no manda pings.
- Hay un mensaje que no figura en los `.proto` de referencia (campo 24). El log muestra sus bytes
  para poder descifrarlo.
- Falta probar el resto de los links de la lista de apps y el encendido.

Si algo falla, Ajustes → Log de conexión muestra cada mensaje que va y viene, y se puede copiar.
