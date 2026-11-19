# Catálogo de intenciones (Windows)

Cada módulo declara sus intenciones con parámetros tipados. La PC publica este catálogo al
conectarse; el teléfono no pide nada que la PC no sepa hacer.

| Intención | Módulo | Riesgo | Parámetros | Qué hace |
|---|---|---|---|---|
| `SET_VOLUME` | audio | cambio | `level` 0–100 **o** `delta` −100…100 | Volumen general |
| `MUTE_AUDIO` | audio | cambio | `muted` (sí/no) | Silencio |
| `GET_VOLUME` | audio | consulta | — | Dice el volumen |
| `MEDIA_PLAY` | media | cambio | `query` (≤120), `app` (`spotify`/`youtube`) | Sigue lo que sonaba, abre Spotify, o busca y pone una canción |
| `MEDIA_PAUSE` / `MEDIA_RESUME` | media | cambio | — | Pausa / sigue |
| `MEDIA_NEXT` / `MEDIA_PREVIOUS` | media | cambio | — | Pasa / vuelve de canción (y dice cuál suena) |
| `MEDIA_NOW_PLAYING` | media | consulta | — | Qué está sonando |
| `OPEN_APPLICATION` | apps | cambio | `name` (≤80) | Abre un programa del menú Inicio |
| `CLOSE_APPLICATION` | apps | cambio | `name` | Cierra un programa (como tocar la X) |
| `FOCUS_APPLICATION` | apps | cambio | `name` | Trae un programa al frente |
| `LIST_APPLICATIONS` | apps | consulta | — | Programas instalados |
| `MINIMIZE_WINDOWS` / `RESTORE_WINDOWS` | windows | cambio | — | Minimiza todo / vuelve a mostrar |
| `OPEN_SYSTEM_PAGE` | system | cambio | `page` (lista cerrada) | Configuración, Explorador, Descargas, Papelera, Administrador de tareas, Bluetooth, Wi-Fi, Sonido… |
| `LOCK_SESSION` | system | cambio | — | Bloquea la PC |
| `SCREEN_OFF` | system | cambio | — | Apaga el monitor |
| `SET_BRIGHTNESS` | system | cambio | `level` o `delta` | Brillo (notebooks) |
| `SLEEP` / `SHUTDOWN` / `RESTART` | system | **sensible** | — | Suspende / apaga / reinicia (30 s para arrepentirse) |
| `CANCEL_SHUTDOWN` | system | cambio | — | Cancela el apagado |
| `TAKE_SCREENSHOT` | capture | cambio | — | Guarda una captura en Imágenes › Lucy Remota |
| `GET_TIME` | info | consulta | — | Hora de la PC |
| `GET_SYSTEM_INFO` | info | consulta | — | Batería, disco y memoria |
| `WEB_SEARCH` | web | cambio | `query` (≤200) | Busca en Google en el navegador |

## Agregar una intención

1. En Windows, en el módulo que corresponda (`windows/src/LucyRemota/Modules`), agregá un `IntentSpec`
   con sus parámetros y el caso en `ExecuteAsync`. Se publica sola en el catálogo.
2. En el teléfono, agregá la frase en `android/remoto/.../PcInterpreter.kt` (o el mapeo en
   `RemoteExecutor.kt` si ya la entiende el intérprete de Sara).
3. Si es una intención nueva, subí `Minor` en los dos `Proto` y poné `Since`.
