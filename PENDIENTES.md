# Pendientes

Cosas para hacer más adelante, en orden de prioridad.

## 1. Accesos a la configuración de la tele: resuelto

Se quería una sección con accesos a la configuración del televisor (Wi‑Fi, Bluetooth y el
resto). Se armó una pestaña "Tele" con una tarjeta por pantalla y se probó con la tele real el
14/11/2026:

| Qué se mandó | Resultado |
|---|---|
| Tecla `SETTINGS` (176) | ✅ Abre el panel rápido; la tele avisa `app com.android.tv.settings` |
| `intent:#Intent;action=android.settings.…;end` (Configuración, Wi‑Fi, Red, Bluetooth, Pantalla) | ❌ Rechazado |
| El mismo link con `package=com.android.tv.settings` | ❌ Rechazado |
| `market://launch?id=com.android.tv.settings` | ❌ Rechazado |

"Rechazado" quiere decir que la tele contesta `remote_error` con el mensaje del link (campo 90)
y cierra la conexión. La app reconecta sola en 0,1 s. La tele solo acepta links que alguna app
sabe abrir, y ninguno lleva a la configuración.

Así que se sacó la pestaña Tele y quedó un botón **Panel rápido** en el Control, en la fila de
volumen · silencio · play/pausa. Desde ese panel se llega a toda la configuración.

Navegar a cada pantalla con secuencias de teclas sigue descartado: dependen del idioma y la
versión de la tele, y se rompen con cualquier actualización.

### De rebote: abrir apps por paquete

La misma prueba mostró que `market://launch?id=…` se rechaza **también para apps instaladas**
(Moonlight, `com.limelight`). Así abría la app todo lo que iba por paquete: "Abrir por paquete
(plan B)", las apps agregadas solo con el paquete (Kodi, Moonlight) y "En pantalla → Agregar".

| Link para Moonlight | Resultado |
|---|---|
| `market://launch?id=com.limelight` | ❌ Rechazado |
| `android-app://com.limelight` | ❌ Rechazado |
| `market://details?id=com.limelight` | ✅ Abre la ficha de Play Store con "Abrir" seleccionado |
| `https://play.google.com/store/apps/details?id=com.limelight` | ✅ Lo mismo |

Ahora abrir por paquete manda `market://details` y, cuando la tele avisa que está en Play
Store (`com.android.vending`), la app espera 1 s y aprieta OK. Moonlight abrió en unos 2 s
desde el toque. Las apps guardadas con `market://launch` se pasan solas al formato nuevo.
Riesgo aceptado: si la app no está instalada en la tele, ese OK cae en "Instalar".

## 2. Otros pendientes

- **Probar viendo la tele:** volumen, silencio y play/pausa. Ya se mandan sin errores, pero
  nadie los vio en pantalla. El volumen pasa por HDMI hacia la Sony. El encendido ya está
  probado: la tele avisó que prendió (14/11/2026).
- **Probar el resto de las apps:** Prime Video, Disney+, Plex, Twitch, Spotify, Crunchyroll y
  Kodi. YouTube y Netflix ya están probadas, y Moonlight por paquete. Ojo: si un link no lo abre ninguna app, la tele
  corta la conexión (ver la sección 1). La app reconecta sola, pero conviene sacar los que fallen.
- **Mensaje 24:** la tele responde al texto con un mensaje que no está en los `.proto`. El log
  muestra sus bytes; falta capturarlos y ver qué es.
- **Texto en vivo de fábrica:** decidir si queda activado por defecto.
- **Firma propia para el APK:** hoy se firma con la clave de depuración de esta PC. Con un
  keystore propio (guardado fuera del repo) se puede actualizar la app desde otra compu.
- **Versión 2 del plan original:** botones de volumen físicos del celu, tile de Ajustes rápidos o
  widget, touchpad, voz directo al Asistente de la tele y más de un dispositivo.
