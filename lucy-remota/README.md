# Lucy Remota

Tu teléfono es el micrófono y la voz; tu computadora Windows hace las cosas.

Decís «Lucy, poné música» en el teléfono → el teléfono entiende la orden → se la manda cifrada a
la PC (por la red local, o por Internet si estás en otra red) → la PC la valida y la hace → le contesta al teléfono → Lucy te dice
«Listo, puse la música».

Es un proyecto **independiente**: no reemplaza ni modifica a Sara ni a la Lucy de Windows que ya
existe. Las tres apps pueden convivir.

| App | Archivo en el release | Dónde va |
|---|---|---|
| **Lucy Remota · Emisor** | `LucyRemota-v1.0.N.apk` | El teléfono (Android 10 o más nuevo) |
| **Lucy Remota · Receptor** | `LucyRemota-v1.0.N.exe` | La computadora (Windows 10/11, 64 bits) |

## Instalar y vincular (una sola vez)

1. **En la computadora:** bajá `LucyRemota-…exe` y abrilo. No se instala ni pide
   administrador; podés tenerlo en cualquier carpeta o en un pendrive (guarda sus datos al lado, en
   `LucyRemota-datos`).
2. Tocá **«Vincular teléfono»**: aparece un QR y un código de 8 letras (valen 5 minutos).
3. **En el teléfono:** instalá `LucyRemota-…apk`, abrilo, dale permiso al micrófono y
   tocá **«Escanear QR»** (o «Escribir código» si no querés usar la cámara).
4. Listo: en los dos aparece «Conectada». Desde ahí se reconectan solos.

Para vincularlos, el teléfono y la PC tienen que estar en **la misma red Wi-Fi** (o la PC en el
**hotspot del teléfono**). Después también funcionan en **redes distintas** (el teléfono con datos
móviles y la PC por cable, por ejemplo) a través del relé de Internet; en la PC aparece «(por
Internet)» y en el teléfono «Por Internet». Si vuelven a estar en la misma red, pasan solos a la
conexión directa.

### Relé para redes distintas (una sola vez)

El relé es un Worker de Cloudflare gratis en tu cuenta, con el código de [`relay/`](relay/):

1. En Cloudflare: **Workers & Pages › Create › Import a repository**, elegí este repositorio.
2. Nombre del proyecto `lucy-remota-relay`, carpeta raíz (*Root directory*) `lucy-remota/relay`,
   rama `main`. **Deploy**.
3. Abrí `https://lucy-remota-relay.<tu-subdominio>.workers.dev/`: tiene que decir
   `lucy-remota-relay ok`.

Las apps usan `https://lucy-remota-relay.devecchianderson12.workers.dev`. Para usar otra dirección
en la PC, escribila en `LucyRemota-datos/relay.txt` (o `no` para usar sólo la red local).

## Usarla

Decí «Lucy» (o tocá el círculo) y pedí lo que quieras. Algunos ejemplos:

- «Poné música», «poné Queen», «pausá», «siguiente canción», «¿qué está sonando?»
- «Subí el volumen», «volumen al 30», «silenciá la computadora»
- «Abrí Google Chrome», «abrí el explorador de archivos», «cerrá Spotify», «cambiá a Word»
- «Sacá una captura de pantalla», «minimizá todas las ventanas», «apagá la pantalla»
- «Abrí la configuración de Windows», «abrí descargas», «abrí el administrador de tareas»
- «Bloqueá la computadora», «apagá la compu» (te pregunta antes), «cancelá el apagado»
- «Decime qué hora es», «¿cómo está la compu?», «buscá en Google recetas de pizza»
- Preguntas y charla («¿qué es la fotosíntesis?») las contesta el teléfono.

En la PC, la ventana muestra el teléfono conectado y cada orden recibida (hecha, con error,
inválida…). Al cerrarla queda en la bandeja; «Salir» la cierra del todo.

## Desvincular

- Desde el teléfono: «Desvincular».
- Desde la PC: elegí el teléfono en la lista › «Desvincular».

Para volver a usarlos, se vuelven a vincular con un QR nuevo.

## Más información

- [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md): cómo está hecho y por qué.
- [`protocol/PROTOCOLO.md`](protocol/PROTOCOLO.md): el protocolo entre el teléfono y la PC.
- [`docs/INTENCIONES.md`](docs/INTENCIONES.md): catálogo de órdenes de Windows.
- [`docs/LIMITACIONES.md`](docs/LIMITACIONES.md): lo que todavía no se puede y por qué.
