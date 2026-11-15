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

## 2. Para retomar (15/11/2026)

La **1.2.0** está en `main` (commit `90492cf`) pero **sin publicar**: no tiene etiqueta. Está instalada en el celu
de Gonzalo como compilación de prueba (firmada con la clave de las versiones, así que se actualiza encima sin perder nada). Trae:

- **Teles de la casa:** busca las teles en la red (`_androidtvremote2._tcp`), guarda varias, cambia de una a
  otra desde el título del control o desde Ajustes, y si una cambia de IP la encuentra sola comparando la huella
  de su certificado. ✅ probado.
- **Botones de volumen del celu** manejan el de la tele con la app abierta. ✅ probado.
- **Touchpad** en vez de flechas (Ajustes). ✅ probado.
- **Control TV en los ajustes rápidos** del celu (el panel que se baja desde arriba). ✅ probado.
- **Apps de la casa** como lista de fábrica, y a las listas guardadas se les sacan Plex, Twitch, Spotify y Kodi
  (si no se editaron) y se suman Flow y Moonlight. ✅ probado.
- **Voz al Asistente** manteniendo apretado el micrófono. ⚠ **Falta probarlo desde el celu** (ver abajo).

Lo primero mañana:

1. **Probar la voz al Asistente:** mantener apretado el micrófono hasta que se ponga naranja y la barra diga
   "Hablale a la tele…", decir "abrí YouTube" y soltar. Tocarlo (sin mantener) es el dictado del celu, que ya
   andaba: la noche del 14 ese fue el que se probó ("Abrir YouTube" llegó como texto y Google TV abrió YouTube).
   - El protocolo está confirmado desde la PC con `TvLiveLab` (`TV_LAB_STEP=voice`): el Asistente entendió
     "Batman". La clave fue mandar el audio en **trozos de 20 KB**: con trozos de 3,2 KB llega pero el
     Asistente devuelve vacío (eso le pasaba a la app hasta la última compilación, que ya manda 20 KB).
   - Para ver qué entendió: `adb logcat | grep FINAL_RESULT` en la tele, o el log de la app, que anota
     "→ Voz: … s al Asistente, … bytes, pico …" (un pico menor a ~500 es silencio).
2. **Actualizar el README** con lo nuevo (teles de la casa, voz, touchpad, botones de volumen, acceso rápido;
   las pruebas son 60, 2 saltadas) y regenerar las capturas de `docs/diseno`.
3. **Publicar la 1.2.0:** `git tag -a v1.2.0` (el mensaje son las notas: lo de la lista de arriba) y
   `git push origin v1.2.0`.

## 3. Lo que quedó probado el 14/11/2026

- **Volumen y silencio:** el Chromecast manda `<User Control Pressed>` Volume up/down/mute por HDMI-CEC a la
  Sony y la Sony contesta ACK (historial de `dumpsys hdmi_control`). Play/pausa sale sin errores; falta verlo con
  algo reproduciéndose.
- **Links de cada app** (se le preguntó a la tele qué app abre cada uno con `cmd package query-activities`):
  YouTube, Netflix (`netflix://`), Prime Video, Disney+ y Crunchyroll (`crunchyroll://`) andan. Flow y
  Moonlight no tienen link propio: van por paquete. Plex, Twitch, Spotify y Kodi no están en la tele.
- **Firma propia:** hecha (clave en `Documents\ControlTV-firma`, secretos en GitHub, versiones con GitHub
  Actions).

## 4. Otros pendientes

- **Google Play:** pedirle a Google que revise la app (Play Protect la marcó como "dañina" en la 1.1.0, cuando
  se instalaba sola) y, más adelante, el registro de desarrollador que va a exigir Google para instalar apps
  fuera de Play Store.
- **Mensaje 24:** la tele responde al texto con un mensaje que no está en los `.proto`. Hace falta un campo de
  texto abierto en la tele para provocarlo; la app anota sus bytes en el log cuando llega.
- **Texto en vivo de fábrica:** queda apagado. En el Inicio de CleanYourTV no sirve (no hay campo) y el texto
  normal, con la tecla de acción, anda en todos lados.
- **Widget** en la pantalla de inicio del celu (el acceso en los ajustes rápidos ya está).
- **La depuración de la tele quedó prendida** a propósito, para investigar si se vuelve a perder una
  vinculación. Apagarla cuando no haga falta (Opciones para desarrolladores: Depuración inalámbrica, Depuración
  por USB y Revocar autorizaciones).
- **"PC de pruebas"** quedó vinculado a la tele (lo usa `TvLiveLab`, con su certificado en el scratchpad de la
  sesión). No pisa a ningún celu.
- **El parche del launcher de CleanYourTV** (buscar desde Inicio) es local: si se actualiza cleanyourtv hay que
  volver a aplicarlo (`Desktop\cleanyourtv\backup\comedor-2026-11-14\parche-launcher.patch`). Se podría
  proponer a su autor.
