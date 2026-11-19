# Protocolo Lucy Remota (versión 1)

El teléfono (**Emisor**) interpreta la voz y manda **órdenes estructuradas**; la PC (**Receptor**)
las valida y ejecuta acciones declaradas. Nunca viaja ni se ejecuta texto libre como comando.

Implementaciones: `windows/src/LucyRemota.Core` (C#) y `android/protocolo` (Kotlin). Se prueban una
contra la otra en cada compilación.

## 1. Descubrimiento (UDP)

La PC **nunca espera conexiones**: así Windows no pide administrador para el firewall. En cambio:

1. La PC manda un anuncio cada 2 s (sólo si le falta conectar algún teléfono) por broadcast
   (`255.255.255.255` y el de cada red), a la puerta de enlace (el teléfono, si es su hotspot) y a
   las últimas IP conocidas, al puerto UDP **47801**:
   ```json
   {"lr":1, "t":"beacon", "pc":"pc-1a2b…", "name":"MI-PC", "pairing":false}
   ```
2. El teléfono, si esa PC es la vinculada (o la que está vinculando), contesta al remitente:
   ```json
   {"lr":1, "t":"here", "pc":"pc-1a2b…", "dev":"tel-…", "port":47800}
   ```
3. La PC se conecta por TCP a ese puerto (conexión saliente: Windows la permite).

## 2. Marcos

Cada mensaje: 4 bytes big-endian con el largo + los datos (máximo 256 KB). La negociación va en JSON
plano; después todo va cifrado.

## 3. Negociación (autenticación mutua y claves de sesión)

Cada lado tiene una **clave de identidad** ECDSA P-256 (la del teléfono vive en el almacén seguro de
Android; la de la PC está cifrada con el usuario de Windows). En cada conexión se crean claves
**efímeras** ECDH P-256 (secreto hacia adelante).

1. PC → teléfono: `{"t":"hello","v":1,"minor":0,"pc","name","pc_key","eph","nonce","pairing"}`
2. Teléfono → PC: `{"t":"auth","v":1,"dev","name","dev_key","eph","nonce","now","sig"[,"pair","pair_mode"]}`
3. PC → teléfono: `{"t":"auth_ok","sig"[,"pair"]}` o `{"t":"auth_fail","reason"}`

- `H` = SHA-256 de (con largo de 4 bytes delante de cada campo): `"lucy-remota/1"`, pc, pc_key,
  eph_pc, nonce_pc, dev, dev_key, eph_dev, nonce_dev. Claves en formato SubjectPublicKeyInfo (DER).
- `sig` del teléfono = ECDSA-SHA256 sobre `"dev" ‖ H`; el de la PC sobre `"pc" ‖ H` (formato DER).
- Teléfono vinculado: su `dev_key` tiene que ser igual a la guardada. PC vinculada: `pc_key` igual a
  la guardada.
- Vinculación por **QR**: el QR trae la huella `SHA-256(pc_key)` y un código de 128 bits. El
  teléfono comprueba la huella; prueba que escaneó el QR con `pair = HMAC-SHA256(código, "dev" ‖ H)`.
- Vinculación por **código manual** (8 caracteres): los dos lados prueban conocerlo
  (`HMAC(código, "dev" ‖ H)` y `HMAC(código, "pc" ‖ H)`). La ventana dura 5 minutos y se cierra a
  los 5 intentos fallidos.
- Claves de sesión: `HKDF-SHA256(ECDH(eph_pc, eph_dev), sal = H, info = "lucy-remota/1 keys", 64)`:
  32 bytes PC→teléfono y 32 teléfono→PC. **AES-256-GCM**, nonce = 4 bytes en cero + contador de 8
  bytes por sentido. Un mensaje alterado, repetido o fuera de orden no descifra y corta la conexión.

## 4. Mensajes de la sesión (cifrados, JSON)

| `t` | De | Para qué |
|---|---|---|
| `capabilities` | PC | Catálogo de intenciones (nombre, módulo, riesgo, parámetros con tipo y límites, versión) |
| `command` | Teléfono | Una orden |
| `status` | PC | Estado de una orden |
| `ping` / `pong` | Los dos | Latido (PC cada 15 s, teléfono cada 20 s); sin nada en 45 s se corta y se reconecta |
| `unpair` | Teléfono | Desvincularse |
| `unpaired` | PC | La PC desvinculó al teléfono |
| `bye` | Los dos | Cierre ordenado |

### Orden

```json
{"t":"command", "v":1, "command_id":"6f1c…", "intent":"SET_VOLUME",
 "params":{"level":70}, "sent_at":1763600000000, "expires_ms":10000, "confirmed":false}
```

### Estados

```json
{"t":"status", "command_id":"6f1c…", "status":"success", "message":"Listo, volumen al 70 %.",
 "result":{"level":70, "previous":45}}
```

`received` → `processing` → uno final: `success`, `error`, `unsupported` (intención desconocida),
`invalid` (parámetros mal), `expired` (llegó tarde), `needs_confirmation` (acción sensible sin
`confirmed`), `unauthorized`, `busy`. El teléfono **no anuncia que se hizo** hasta recibir `success`.

## 5. Relé (redes distintas)

Si no se encuentran en la red local (por ejemplo, el teléfono con datos móviles y la PC por cable),
usan un relé en Internet: un Worker de Cloudflare (`relay/`, plan gratis) que **sólo reenvía bytes**.
La negociación de la sección 3 y el cifrado de la sección 4 son los mismos, así que el relé no puede
leer, cambiar ni inventar órdenes.

- **Sala:** `sala = hex(SHA-256("lucy-remota/relay/1" ‖ pc_key ‖ dev_key))[0:40]` (claves SPKI).
  Sólo la PC y el teléfono vinculados la conocen.
- **Conexión:** cada lado abre un WebSocket saliente a `wss://<relé>/v1/<sala>?role=pc|dev`. Una
  conexión nueva del mismo lado reemplaza a la vieja (el teléfono cambió de red).
- **Avisos del relé** (texto): `peer:1` el otro lado está (cada vez que llega uno, la PC empieza una
  negociación nueva con `hello`), `peer:0` el otro se fue. Cada marco de la sección 2 viaja en un
  mensaje binario.
- **Latido:** cada lado manda el texto `ping` cada 25 s; el relé contesta `pong` sin despertar a la
  sala (no gasta cuota). Sin nada en 70 s, se reconecta.
- **Reglas extra:** por el relé no se vincula (sólo un teléfono ya vinculado en persona); la PC sólo
  acepta al teléfono de esa sala, y el teléfono no borra la vinculación por un rechazo que llegue
  por el relé.
- **Preferencia:** en cuanto vuelven a verse en la red local, la PC se conecta directo, la sesión
  directa reemplaza a la del relé y los dos dejan el relé.

## 6. Reglas

- **Duplicados:** la PC recuerda cada `command_id` 10 minutos; si llega de nuevo devuelve el mismo
  resultado sin ejecutar. El teléfono reintenta con el mismo id si se cortó antes de `received`.
- **Vencimiento:** con la diferencia de relojes medida al conectarse, si la orden llega después de
  `expires_ms` (1–120 s) no se ejecuta.
- **Validación:** sólo intenciones del catálogo, sólo parámetros declarados, con su tipo, rango, largo
  máximo y sin caracteres de control. Los enumerados sólo aceptan sus opciones.
- **Confirmación:** las intenciones de riesgo `sensitive` (apagar, reiniciar, suspender) devuelven
  `needs_confirmation`; el teléfono pregunta y manda una orden nueva con `confirmed: true`.
- **Versiones:** se ignoran campos desconocidos. `v` (mayor) sólo cambia si hay incompatibilidad;
  las intenciones nuevas suben `minor` y llevan `since`.
