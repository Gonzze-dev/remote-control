# Control TV

Control remoto propio para el Chromecast con Google TV. Habla directo con la tele por Wi‑Fi
(protocolo Android TV Remote v2, puertos 6467 y 6466), sin servidor.

## Descargar

**[Bajar Control TV (última versión)](https://github.com/Gonzze-dev/remote-control/releases/latest/download/control-tv.apk)**,
o escaneá el código con el celu:

<img src="docs/descarga-qr.png" width="160" alt="Código QR para bajar Control TV">

El link siempre baja la última versión. Al abrir el APK, Android pide permitir instalar apps desde el navegador
(o desde Archivos). Google Play Protect puede avisar que no conoce la app o bloquearla como "dañina": es porque
no viene de Play Store y la firma es nueva para Google. Tocá **Instalar de todas formas** y, si ofrece mandarla a
analizar, aceptá.

Después la app avisa sola cuando hay una versión nueva: aparece un punto en el engranaje, y en Ajustes,
"Actualizar a la versión…". Ese botón baja el APK con el navegador; al abrirlo, Android lo instala encima sin
perder la vinculación. La app no se instala sola a propósito: la 1.1.0 lo hacía y Play Protect la marcó como
dañina. También se puede compartir el link desde Ajustes → Compartir la app.

## Publicar una versión

Las versiones las compila y publica GitHub Actions ([release.yml](.github/workflows/release.yml)) con la clave
de firma guardada en los secretos del repo:

1. Subir `appVersion` en [app/build.gradle.kts](app/build.gradle.kts) (por ejemplo, `1.2.0`) y hacer commit.
2. `git tag -a v1.2.0 -m "Lo que cambia"`: el mensaje de la etiqueta son las notas de la release.
3. `git push origin main v1.2.0`

El workflow revisa que la etiqueta coincida con `appVersion`, corre las pruebas, compila el APK, revisa que
la firma sea la de siempre y crea la release con `control-tv.apk`. Cada versión tiene que ser más alta que la
anterior: el `versionCode` sale de la versión (1.2.3 → 10203).

**La clave de firma** (`control-tv-release.jks`) no está en el repo. Hay una copia en `Documents\ControlTV-firma`
de la PC de Gonzalo, con su contraseña, y otra en los secretos `KEYSTORE_BASE64` y `KEYSTORE_PASSWORD` del repo.
Si se pierde, las versiones nuevas no se pueden instalar encima de las viejas.

## Compilar e instalar

1. Abrí la carpeta `control-tv` en Android Studio (está en el menú Inicio).
2. Conectá el celu con la depuración USB activada y apretá Run (▶).

Sin Android Studio, desde una terminal:

```
gradlew assembleDebug        → app/build/outputs/apk/debug/app-debug.apk
gradlew testDebugUnitTest    → pruebas + capturas de pantalla
```

Con `keystore.properties` en la raíz (lo ignora git, apunta a la clave de firma), las compilaciones de prueba
también se firman con la clave de las versiones publicadas, así se instalan encima sin perder la vinculación.

## Probar sin la tele

`gradlew testDebugUnitTest` corre 55 pruebas (y saltea una que necesita el Chromecast de verdad):

- **ProtocolTest**: bytes exactos de cada mensaje, el secreto de vinculación y el certificado.
- **AppLinksTest**: el link para abrir una app por paquete, el cambio de los links viejos y que el Inicio de
  CleanYourTV cuente como Inicio.
- **FakeTvTest**: una tele de mentira con TLS de verdad (`FakeTv.kt`). Prueba la vinculación con el
  código, el código mal tipeado, la sesión de control (teclas, texto, apps, ping, volumen),
  el celu desconocido, la huella cambiada, que dos celus vinculados no se pisen (la tele guarda cada uno por el
  nombre de su certificado), la reconexión, que muchos reinicios seguidos no
  dejen sesiones abiertas de más y que mandar comandos sin parar no corte la sesión.
- **CastClientTest**: un Chromecast de mentira con TLS de verdad (`FakeCast.kt`). Prueba los bytes de Cast v2,
  abrir el reproductor y cargar un archivo, el archivo que no se puede abrir, los PING, el final del video,
  cerrar el reproductor desde la tele y Detener.
- **MediaServerTest**: el servidor que le da el archivo al Chromecast: el archivo entero, los rangos para
  adelantar, el rango fuera del archivo y que solo se sirva el archivo publicado.
- **CastLiveTest**: muestra el logo de Google en el Chromecast real y lo cierra. Solo corre con
  `CAST_LIVE_HOST=192.168.1.31`.
- **UpdaterTest**: leer la última release de GitHub y que la versión dé el mismo `versionCode` que Gradle.
- **ScreenshotTest**: genera las capturas de `docs/diseno/` (quedan en `app/build/outputs/roborazzi/`).

## Diseño

Oscuro, porque se usa de noche frente a la tele: fondo grafito y un solo color de acento, ámbar.

| | |
|---|---|
| Fondo / superficies | `#0D0F13` · `#15181E` · `#1D2129` |
| Acento (OK, enviar, selección) | `#FF9F45` |
| Conectado / error | `#3DDC97` · `#FF5E5E` |

- **Control**: línea de estado arriba (punto de conexión, app en pantalla, volumen, Panel rápido,
  Transmitir y Ajustes). En el medio, el pad circular con el OK ámbar como protagonista; abajo, Atrás · Inicio ·
  Encendido y, más abajo, volumen · silencio · play/pausa. La barra de texto queda cerca del
  pulgar: el campo y el micrófono (borrar y mandar se hacen con el teclado del celu). Si el
  teclado ocupa la pantalla, se esconden las filas y queda el pad.
- **Apps**: grilla de tarjetas con el ícono de cada app. El ícono oficial se baja una vez de
  Google Play (por el nombre de paquete) y queda guardado; al editar una app se puede elegir una
  imagen propia. Sin ícono se ve una inicial de color, que es lo que muestran las capturas (así
  el repo no lleva logos de marcas). La app abierta en la tele se marca en ámbar. Si en la tele
  hay una app que no está en la lista (y no es una pantalla del sistema), arriba aparece
  "En pantalla" con el botón para agregarla.
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
- En el Inicio de CleanYourTV (ver más abajo) no hay campo: la app manda el texto y el launcher busca el texto
  entero en Google TV. Con "Texto en vivo" no se manda letra por letra, porque cada letra suelta lanzaría una
  búsqueda: el texto sale todo junto con la tecla de acción.
- Las flechas y el volumen se repiten al mantenerlos apretados (manda toques seguidos).
- OK, Atrás e Inicio hacen pulsación larga en la tele (`START_LONG` → `END_LONG`): por ejemplo, el menú contextual.
- Encendido y play/pausa no tienen pulsación larga, para no apagar la tele sin querer.
- **Panel rápido** manda la tecla de configuración del control y abre el panel de Google TV.
  Es la forma de llegar a la configuración: la tele rechaza los links a sus pantallas.
- Las apps sin link propio se abren **por paquete**: la app manda la ficha de Play Store
  (`market://details?id=…`), que se abre con "Abrir" seleccionado, y cuando la tele avisa que
  está en Play Store espera 1 s y aprieta OK. Si la app no está instalada, ese OK cae en "Instalar".
- **Transmitir** manda una foto, un video o un audio del celu al Chromecast, como el botón de transmitir de
  YouTube. "Fotos y videos" abre la galería; "Archivo del celu", el selector de archivos (Descargas y otras
  carpetas). El botón queda ámbar mientras se transmite; ahí mismo y en la notificación está Detener. Elegir
  otro archivo reemplaza al anterior en la misma sesión. Pausa, adelantar, Atrás e Inicio van con el control
  de siempre.

## Transmitir desde el celu

Es Cast v2, el protocolo del botón de transmitir, hecho a mano como el del control (`cast/`), sin la librería
de Google Cast:

1. El celu abre TLS al puerto 8009 del Chromecast y le pide el reproductor estándar de Google (Default Media
   Receiver, `CC1AD845`), que muestra fotos, video y audio a partir de una dirección.
2. Un servidor HTTP mínimo en el celu (`MediaServer`) sirve solo el archivo elegido, bajo una ruta con un
   código al azar, y entiende `Range` para que se pueda adelantar.
3. El celu le manda al reproductor `LOAD` con `http://<ip del celu>:<puerto>/m/<código>/<nombre>`, y el
   Chromecast lo baja de ahí.

`CastService` es un servicio en primer plano (tipo `mediaPlayback`): con la notificación y un wake lock
sigue sirviendo una peli entera aunque el celu se bloquee o la app se cierre. El permiso de lectura del
archivo se pide persistente y se suelta al terminar. La sesión termina sola cuando el video llega al final o
cuando se cierra el reproductor en la tele. El reproductor informa las fotos como `PAUSED`, así que una foto
queda en pantalla hasta que se cierra.

Formatos: MP4 con H.264 o HEVC y audio AAC anda siempre. Los `.mkv` y `.mov` se presentan como MP4 para que el
reproductor los intente: andan si los códecs son de los que entiende (no AC3, DTS ni subtítulos).

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
- **Abrir por paquete con la ficha de Play Store**: el plan usaba `market://launch?id=…`, pero
  esta tele lo rechaza aunque la app esté instalada. Las apps guardadas con el link viejo se
  pasan solas al nuevo.
- **Sin accesos directos a la configuración**: se probaron links a cada pantalla (`intent:` con
  y sin paquete, y abrir la app Configuración) y la tele rechazó todos. Quedó el botón Panel
  rápido.

## Probado con la tele real

13 y 14/11/2026 · Chromecast con Google TV en una tele Sony · Galaxy S25 Ultra con Android 17.

| Qué | Resultado |
|---|---|
| Permiso de red local (Android 17) | ✅ |
| Vinculación con el código | ✅ |
| Conexión, pings y reconexión al volver a la app | ✅ |
| Flechas, OK, Atrás, Inicio, borrar y Enter | ✅ |
| Volumen, silencio y play/pausa | Salen sin errores; falta verlos en pantalla |
| Encendido (la tele avisa que prendió) | ✅ |
| Panel rápido (abre la configuración) | ✅ |
| Abrir apps por link (YouTube, Netflix) | ✅ |
| Abrir apps por paquete (Moonlight, con la ficha de Play Store) | ✅ |
| Links a pantallas de configuración | ❌ La tele los rechaza |
| Íconos de las apps desde Google Play | ✅ |
| Texto al enviar, en el buscador de Google TV | ✅ |
| Texto en vivo (letra por letra y borrados) | ✅ |
| Dictado por voz desde Inicio (abre el buscador y busca) | ✅ |
| Buscar desde el Inicio de CleanYourTV, con teclado y voz | ✅ |
| Transmitir una foto, un video de WhatsApp y un capítulo MP4 de 277 MB (pausa, adelantar, Detener) | ✅ |

Lo que se aprendió:

- La tele avisa un campo de texto abierto con `ime_key_inject` y contadores distintos de 0, y
  después de cada cambio manda `ime_show_request` con el contador de campo nuevo.
- Sin campo abierto, los contadores llegan en 0. En Inicio, la tele abre el buscador al recibir
  texto, pero ese primer texto se pierde.
- Mientras recibe comandos, la tele no manda pings.
- Hay un mensaje que no figura en los `.proto` de referencia (campo 24). El log muestra sus bytes
  para poder descifrarlo.
- La tele rechaza los links que ninguna app sabe abrir: contesta `remote_error` y corta la
  conexión (la app reconecta sola en 0,1 s). Pasa con `intent:`, `android-app://` y
  `market://launch`, aunque la app esté instalada. `market://details` y
  `https://play.google.com/…` sí andan.
- Desde el protector de pantalla, la primera tecla solo despierta la tele.
- La tele guarda cada celu vinculado por el nombre (CN) de su certificado, y al vincular uno borra al que tenía
  el mismo nombre (`AtvRemote.KeyStoreManager: Deleting entry for … (CN=atvremote)`). Hasta la 1.1.0 todos los
  celus usaban `CN=atvremote`, así que vincular un segundo celu desvinculaba al primero. Desde la 1.1.1 cada
  celu tiene `CN=atvremote-<12 caracteres al azar>`, y uno con el nombre viejo lo cambia al volver a vincular.
- Falta probar el resto de los links de la lista de apps.

Si algo falla, Ajustes → Log de conexión muestra cada mensaje que va y viene, y se puede copiar.

## La tele con CleanYourTV

Desde el 14/11/2026 el Chromecast tiene la limpieza de [cleanyour.tv](https://cleanyour.tv/): telemetría,
publicidad y apps que no se usan desactivadas, y su launcher (`com.gathin.tvhome`) como Inicio. El Inicio de
Google sigue habilitado por debajo para no perder el casteo desde el celu, y un servicio de accesibilidad del
launcher manda el botón Inicio al nuevo. Lo que cambia para esta app:

- El launcher nuevo figura como "Inicio" y no aparece en "En pantalla" (`AppNames.CLEAN_HOME`).
- Buscar desde Inicio depende de un parche local del launcher: junta las letras que le llegan y lanza la
  búsqueda de Google TV. Sin el parche, el texto mandado desde Inicio no hace nada.
- Mantener apretado Inicio sigue abriendo el panel de Google, y Panel rápido anda igual.
- El buscador de YouTube no deja escribir desde el control: usa su propio teclado en pantalla. La voz sí anda.

El respaldo, los scripts para reinstalar el launcher o deshacer todo, y el parche están en
`Desktop\cleanyourtv\backup\comedor-2026-11-14\` (ver su `LEEME.md`).
