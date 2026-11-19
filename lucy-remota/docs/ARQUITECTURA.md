# Arquitectura

```
Voz → Reconocimiento → Interpretación → Intención → Orden estructurada → Red cifrada
    → Autenticación → Validación → Módulo de Windows → Respuesta → Voz
```

## Teléfono (Emisor) — `android/`

- **app** (Kotlin, Jetpack Compose): pantalla, servicio en primer plano (escucha de «Lucy» y
  conexión con la PC aunque la pantalla esté apagada), identidad en el almacén seguro, lector de QR
  (CameraX + ZXing, sin servicios de Google), voz y actualizador propio.
- **nucleo**: copia del cerebro de Sara (intérprete, orquestador, motores). Es una copia a propósito:
  Lucy Remota evoluciona sin afectar a Sara ni a la Lucy actual.
- **remoto**: `PcInterpreter` (frases propias de la PC: capturas, ventanas, apagado, configuración…)
  y `RemoteExecutor` (intención → orden de Windows, espera la confirmación de la PC, pide confirmación
  para lo sensible). La charla y las preguntas se contestan en el teléfono.
- **protocolo**: el protocolo (descubrimiento, negociación, cifrado, órdenes), sin Android: se prueba
  en la PC y contra el Receptor real.
- Voz: «Lucy» con Vosk (local), reconocimiento de Google (con Internet) o Vosk (sin Internet), voz
  Daniela (Piper, local).

## Computadora (Receptor) — `windows/`

- **LucyRemota.Core** (.NET 8, sin Windows): protocolo, seguridad, vinculación, validación de órdenes
  (duplicados, vencidas, parámetros, confirmación) y la conexión. Se prueba en cualquier sistema.
- **LucyRemota** (.NET 8 Windows): módulos que hacen las cosas con las funciones de Windows directas
  (volumen con Core Audio, música con los controles multimedia del sistema, programas del menú
  Inicio, ventanas, capturas, energía), la ventana y la bandeja.
- Un solo `.exe` autocontenido y precompilado (ReadyToRun): arranca rápido, no necesita instalar .NET.

## Por qué así

- **.NET 8 en Windows** (y no Java como la Lucy actual): llega directo a las APIs de Windows sin
  procesos intermedios (las órdenes tardan milisegundos), usa ~40 MB de memoria privada y no necesita
  nada instalado.
- **La PC se conecta al teléfono**: Windows no muestra el aviso del firewall (que pide
  administrador) para conexiones salientes. Funciona también con el hotspot del teléfono.
- **Sin servidores externos**: todo pasa en la red local; sin Internet sigue andando (salvo el
  reconocimiento de Google y las preguntas a la IA, que caen a Vosk/Wikipedia o avisan).
- **Cifrado propio con primitivas estándar** (ECDH, ECDSA, HKDF, AES-GCM) en lugar de TLS con
  certificados: la autenticación mutua con claves fijadas al vincular es más simple de hacer bien
  en los dos lados y se prueba de punta a punta.

## Pruebas

- Núcleo de Windows (xUnit): vinculación QR y código, código equivocado, impostor con otra clave,
  duplicados, vencidas, validación, confirmación, mensaje alterado y reconexión, desvincular.
- Teléfono (JUnit): cifrado, HKDF (vector de la RFC 5869), QR, y **contra el Receptor real de .NET**
  (de la frase hablada a la PC y la respuesta).
- Windows real (autoprueba del `.exe`): teléfono simulado + órdenes reales (hora, sistema, apps,
  capturas, ventanas, abrir/cerrar el Bloc de notas, seguridad, duplicados) y captura de la ventana.
- Emulador Android: la app real se vincula con la PC simulada y manda órdenes.
