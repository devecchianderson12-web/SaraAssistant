# Números de versión

Cada app lleva su propio número y sólo sube cuando esa app cambia:

| App | Archivo con el número | Archivo publicado |
|---|---|---|
| Lucy Remota (Android) | `lucy-remota/android/VERSION` | `LucyRemota-vX.Y.Z.apk` |
| Lucy Remota (Windows) | `lucy-remota/windows/VERSION` | `LucyRemota-vX.Y.Z.exe` |
| Sara (Android) | `app/VERSION` | `SaraAssistant-vX.Y.Z.apk` |
| Lucy de Windows | (ídem) | `Lucy-Windows-vX.Y.Z.exe` |

Reglas (pedidas por el usuario):

- Formato `X.Y.Z`. El primer número de este esquema es **1.5.2**.
- Cada actualización de una app sube el último número en 1 (Lucy Remota se publica sólo cuando sube
  su número: si el release ya tiene esa versión, no se vuelve a subir). El último número llega como máximo a
  **10**: después de `1.5.10` viene `1.6.0` (y después de `1.10.10`, `2.0.0`).
- Para comparar versiones se usa `X·10000 + Y·100 + Z` (1.5.2 → 10502). Es también el
  `versionCode` de Android. Las versiones viejas `1.0.N` valen `N`, así cualquier 1.5.x es más nueva.
