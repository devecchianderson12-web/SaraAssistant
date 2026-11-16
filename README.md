# SaraAssistant

Asistente de voz para Android, nativo en Kotlin + Jetpack Compose. Se activa diciendo **«Sara»** o tocando el círculo central, entiende órdenes en español (con preferencia rioplatense) y en la versión 1.0 controla **música** y **volumen**.

```
Vos:  «Sara»
Sara: «Te escucho.»
Vos:  «Poné música»
Sara: «Perfecto, voy a reproducir música.»   → ejecuta y vuelve a esperar «Sara»
```

---

## 1. Estado de verificación (versión 1.0)

| Elemento | Estado |
|---|---|
| Compilación (`assembleDebug`, AGP 8.7.3, Kotlin 2.0.21, Gradle 8.11.1) | ✅ **Compilación verificada** |
| 58 pruebas unitarias del núcleo + 3 de la app | ✅ **Pruebas unitarias aprobadas** |
| 2 pruebas de integración con AudioManager y MediaSession reales | ✅ **Prueba en emulador** (Android 14, API 34) |
| Interfaz, descarga e instalación del modelo, servicio de micrófono | ✅ Prueba en emulador |
| Escucha con pantalla apagada (servicio tipo `microphone`, audio no silenciado) | ✅ Prueba en emulador |
| Sara como asistente predeterminado + gesto de asistente (sesión sobre cualquier app) | ✅ Prueba en emulador |
| Reactivación automática tras reiniciar (excepción oficial de `VoiceInteractionService`, Android 14) | ✅ Prueba en emulador |
| Órdenes de volumen ejecutadas sobre el sistema real (50 %, +30 %, «bajale al 10 %», máximo, silencio) | ✅ Prueba en emulador (órdenes por texto, el emulador no tiene micrófono) |
| Detección de «Sara» (Vosk, dos etapas) con audio real | ✅ 51/51 aciertos con 51 grabaciones sintéticas de 3 voces (positivas y negativas), mismo modelo y misma biblioteca Kaldi, en PC |
| Detección y órdenes **con tu voz y micrófono real** | ⏳ **Pendiente de prueba en dispositivo físico** |
| Voz offline (TTS del teléfono) | ⏳ Pendiente en dispositivo físico (el emulador usado no trae motor TTS; Sara informa el error correctamente) |
| Voz neuronal Azure (es-AR-ElenaNeural) y reconocimiento Azure | ⏳ **Pendiente de credenciales** |
| Porcupine | ⏳ Pendiente de credenciales y archivo `.ppn` |
| Workflow de GitHub Actions | ✅ Preparado (no ejecutado: no se subió nada a GitHub) |

---

## 2. Instalación (APK)

1. Copiá `SaraAssistant-…-debug.apk` al teléfono (o descargalo del release de GitHub).
2. Abrilo y permití **«Instalar apps desconocidas»** para el navegador o el gestor de archivos.
3. Requisitos: **Android 10 o superior**, ~200 MB libres (app + modelo de voz).

### Actualizaciones dentro de la app
Cada push a `main` publica un release **SaraAssistantV&lt;n&gt;** en GitHub. Al abrir la app, Sara consulta el último release (como máximo cada 30 minutos, o con *Ajustes › Sistema › Actualizaciones › Buscar*) y, **sólo si la versión es más nueva**, muestra un cartel con **Actualizar**. Descarga el APK, abre la confirmación del instalador de Android y borra el archivo descargado al terminar. La primera vez Android pide permitir que SaraAssistant **instale apps** (el cartel lleva directo a esa opción).

> El APK está firmado con una clave de depuración fija (`keystore/sara-debug.keystore`) para que cada versión nueva se instale encima de la anterior sin desinstalar. **No sirve para publicar en Play Store.**

---

## 3. Configuración paso a paso

1. **Abrí SaraAssistant** y tocá **«Descargar modelo (40 MB)»**. Es el modelo de voz en español de Vosk; se baja una sola vez y todo se procesa en el teléfono. (También podés bajar el zip en la PC desde <https://alphacephei.com/vosk/models> — `vosk-model-small-es-0.42.zip` — e importarlo en *Ajustes › Activación › Importar .zip*).
2. Activá **«Escuchar «Sara»»** y aceptá los permisos de **micrófono** y **notificaciones**. Aparece una notificación fija: mientras esté, Sara puede oír su nombre (también con la pantalla apagada o bloqueada).
3. Tocá **«Activar acceso»** (acceso a notificaciones) para que Sara pueda pausar, pasar canciones y confirmar lo que pasa en Spotify, YouTube Music, etc.
   - **Android 13 o superior con el APK instalado a mano:** si la opción aparece gris, andá a *Ajustes del sistema › Apps › SaraAssistant › ⋮ (arriba a la derecha) › «Permitir ajustes restringidos»* y volvé a intentar.
4. **Recomendado:** *Ajustes › Sistema › Asistente predeterminado › Cambiar* y elegí **SaraAssistant** como *Asistente digital*. Con eso:
   - mantener Inicio / deslizar desde la esquina abre a Sara, incluso con la pantalla bloqueada;
   - Sara puede reactivar la escucha sola después de reiniciar el teléfono (si activás *«Reactivar al encender el teléfono»*).
5. **Batería:** en *Ajustes › Sistema › Batería › Ajustar*, dejá SaraAssistant **sin restricciones**. En Xiaomi, Huawei, Samsung, Oppo/Realme/OnePlus revisá además las opciones propias del fabricante (inicio automático, «apps en suspensión»). Guía por marca: <https://dontkillmyapp.com>.
6. **Voz natural online (opcional):** creá un recurso **Speech** en <https://portal.azure.com> (tiene nivel gratuito) y cargá *clave* y *región* en *Ajustes › Voz › Azure Speech › Configurar*. Sara usará **es-AR-ElenaNeural** (voz femenina rioplatense). La clave se guarda cifrada con el Keystore de Android; nunca está en el código.
7. **Voz offline:** usa las voces instaladas del teléfono (*Ajustes del sistema › Idioma › Texto a voz*). Instalá la voz en español de tu motor TTS (por ejemplo, «Servicios de voz de Google» › Español (Estados Unidos)).

---

## 4. Qué le podés pedir

| Música | Volumen |
|---|---|
| «Sara, poné música» / «reproducí una canción» / «quiero escuchar música» | «Subí el volumen» / «más fuerte» (+10 %) |
| «Poné música de Soda Stereo» (si la app admite búsqueda) | «Subí un 30 % la música» → suma 30 puntos |
| «Pausá la música» / «frená» | «Bajale al 10 %» / «poné el volumen al 50 %» → valor absoluto |
| «Continuá reproduciendo» / «dale play» | «Volumen máximo» / «al palo» / «a todo volumen» |
| «Siguiente canción» / «pasá de tema» | «Poné el volumen a la mitad» |
| «Canción anterior» / «la de antes» | «Silenciá la música» / «quitá el silencio» |
| «Detené la música» / «pará la música» | «Subí un poco» (+5 %) / «bajá bastante» (−25 %) |

- **Relativo vs. absoluto:** «subí **un** 30 %» suma 30 puntos porcentuales; «poné el volumen **al** 30 %» fija el 30 % del rango.
- Sólo se toca el **volumen multimedia**: alarmas, llamadas y notificaciones no se modifican.
- Si una app no admite una acción, Sara lo dice («Radio X no permite pasar a la siguiente canción»). Si no puede confirmar el cambio, también lo dice.

---

## 5. Arquitectura

```
:core  (Kotlin puro, sin Android → pruebas rápidas en la JVM)
  model/         Intenciones, estados, eventos, errores
  state/         Máquina de estados (7 estados) con transiciones validadas
  engine/        Interfaces WakeWordEngine · SpeechRecognitionEngine · VoiceEngine
                 RecognitionRouter y VoiceRouter (políticas online/offline/fallback)
  interpreter/   TextNormalizer · SpanishNumbers · RuleBasedCommandInterpreter
                 CompositeCommandInterpreter (punto de extensión para IA conversacional)
  execution/     VolumeCalculator · DefaultCommandExecutor · ResponseCatalog (frases rioplatenses)
  assistant/     AssistantOrchestrator: espera → escucha → interpreta → ejecuta → responde → espera
  permissions/   PermissionPlanner · ServiceStartPolicy (reglas por versión de Android)

:app  (Android)
  speech/vosk/   VoskModelManager · VoskWakeWordEngine · VoskSpeechRecognitionEngine
  wakeword/      PorcupineWakeWordEngine (alternativa opcional)
  speech/        AndroidSpeechRecognitionEngine (servicio del sistema / en el dispositivo)
  azure/         AzureNeuralVoiceEngine · AzureSpeechRecognitionEngine (REST, sin SDK)
  voice/         AndroidTtsVoiceEngine (voces locales u online del sistema)
  media/         AndroidMediaControl (MediaSession/MediaController) · SaraNotificationListener
  audio/         MicrophoneSource · AndroidVolumeControl (AudioManager) · foco de audio · señal sonora
  services/      SaraListeningService (FGS micrófono) · VoiceInteractionService · Session · RecognitionService · BootReceiver
  settings/ data/ DataStore · credenciales cifradas (Keystore) · historial · diagnóstico
  ui/            Compose: inicio con orbe animado, historial, ajustes
  AppContainer   Inyección de dependencias manual (grafo chico, sin procesamiento de anotaciones)
```

### Los tres motores independientes

| Motor | Implementaciones | Notas |
|---|---|---|
| **WakeWordEngine** | **Vosk** (predeterminado, sin cuenta) · Porcupine (opcional) | Vosk en dos etapas: (1) gramática restringida `["sara", "[unk]"]` con umbral de confianza según la sensibilidad; (2) ante un candidato, se transcribe el fragmento con vocabulario libre y se acepta sólo si «Sara» aparece al principio. La etapa 2 eliminó todos los falsos positivos de prueba («subí el volumen», «para la próxima», «vamos a bajar»…). |
| **SpeechRecognitionEngine** | Online: servicio del sistema (Google) · Azure Speech. Offline: Vosk · Android en el dispositivo (12+) | Política: Online/Automático con conexión → online; si se corta → offline (pidiendo repetir si ya habías hablado); **Offline nunca invoca un motor online**; si nada está disponible se explica por qué. |
| **VoiceEngine** | Online: Azure neuronal (es-AR-ElenaNeural, audio en streaming) · voces online del sistema. Offline: voces instaladas | *Automático* = online con respaldo offline. *Siempre online* = sin respaldo (informa el error). *Siempre offline* = sólo local. La calidad offline depende de las voces del teléfono: no se promete calidad neuronal. |

Cambiar un proveedor no toca la lógica principal: el orquestador sólo conoce las interfaces.

### Estados visuales
Inactiva · Esperando «Sara» · Escuchando · Procesando · Ejecutando · Hablando · Error.

---

## 6. Android por versión

| Versión | Comportamiento |
|---|---|
| **10 (API 29)** | Servicio en primer plano con micrófono; puede reactivarse solo al encender. |
| **11 (API 30)** | Un servicio iniciado desde segundo plano no recibe audio: tras reiniciar, Sara muestra una notificación «Tocá para activar» (excepción oficial por interacción con notificación), salvo que sea el asistente predeterminado. |
| **12-13 (API 31-33)** | Además no se pueden iniciar servicios en primer plano desde segundo plano. Se maneja `ForegroundServiceStartNotAllowedException`. En 13 se pide `POST_NOTIFICATIONS` al activar la escucha. |
| **14-15 (API 34-35)** | Se declara `FOREGROUND_SERVICE_MICROPHONE` y `foregroundServiceType="microphone"`; el servicio sólo se inicia con la app visible, desde la notificación o desde el `VoiceInteractionService` (excepciones oficiales). `BOOT_COMPLETED` no inicia el micrófono. |

Sara **no** usa superposiciones ni accesibilidad para saltear la pantalla de bloqueo y no intenta evadir restricciones.

**Pantalla apagada ≠ teléfono apagado:** con la pantalla apagada o bloqueada Sara sigue escuchando si el servicio está activo; con el teléfono apagado no corre ninguna app.

---

## 7. Privacidad

- La palabra «Sara» se procesa **100 % en el teléfono**. El audio vive sólo en memoria y se descarta al terminar cada frase. **Nunca se graba ni se guarda audio.**
- En modo **Offline** nunca se envía audio a Internet. En Online/Automático sólo se envía la orden dicha después de «Sara», al proveedor elegido.
- Indicadores: notificación fija mientras escucha, indicador verde de micrófono en la app, ícono de micrófono del sistema (Android 12+). Botón **Detener** en la notificación.
- Historial: sólo texto, máximo 100 órdenes, se puede desactivar y borrar. Excluido de copias de seguridad.
- Credenciales cifradas con AES-GCM del Android Keystore, excluidas de backups. Nunca en el código fuente.

---

## 8. Compilar

Requisitos: **JDK 17**, Android SDK con *Platform 35* y *Build-Tools 35* (Android Studio Ladybug o superior lo instala solo).

```bash
./gradlew test                       # pruebas unitarias
./gradlew assembleDebug              # APK en app/build/outputs/apk/debug/
./gradlew connectedDebugAndroidTest  # pruebas de integración (emulador o teléfono conectado)
```

Abrí la carpeta en Android Studio con *File › Open*; sincroniza solo.

### Probar órdenes en un emulador (sin micrófono)
Sólo en la variante debug existe un receptor protegido (permiso `DUMP`, sólo adb):
```bash
adb shell am broadcast -a com.sara.assistant.DEBUG_COMMAND -p com.sara.assistant --es text "'subí el volumen un 30%'"
```

### GitHub Actions
`.github/workflows/build.yml` se ejecuta en cada push a `main`, en pull requests y manualmente: valida el wrapper, configura Java 17 y Gradle con caché, descarga dependencias, corre las pruebas, compila el APK debug y lo publica como **artefacto**. En cada push a `main` además crea el release **SaraAssistantV&lt;n&gt;** con el APK. Permisos mínimos (`contents: read`; `contents: write` sólo en el job de release). No usa secretos.

---

## 9. Porcupine (opcional)

1. Creá una AccessKey gratis en <https://console.picovoice.ai>.
2. Entrená la palabra **«Sara»** en **español** y descargá el `.ppn` para **Android**.
3. Descargá `porcupine_params_es.pv` desde el repositorio de Porcupine (`lib/common`), de la misma versión mayor que la biblioteca (v4).
4. En *Ajustes › Activación* elegí **Porcupine › Configurar**, pegá la AccessKey e importá ambos archivos.

---

## 10. Limitaciones técnicas conocidas

- **Pruebas con voz real pendientes:** la detección y el reconocimiento se validaron con audio sintético y el emulador no tiene micrófono. La precisión real depende del micrófono, el ruido y el acento.
- **Consumo de batería:** Vosk analiza audio de forma continua (más consumo que un detector dedicado por hardware). Porcupine consume menos pero requiere cuenta. Los detectores siempre activos de bajo consumo del sistema (`AlwaysOnHotwordDetector` / `HotwordDetectionService`) están reservados a apps del sistema.
- **Fabricantes:** algunas capas (MIUI, EMUI, One UI, ColorOS) pueden cortar servicios aunque estén en primer plano. Ver sección 3.5.
- **Durante llamadas** Android entrega silencio al micrófono: Sara no detecta su nombre hasta que termina la llamada (la app lo indica como «Micrófono sin audio»).
- **Control multimedia:** depende de que cada app publique su MediaSession y las acciones que admite. Sin acceso a notificaciones, Sara envía teclas multimedia sin poder confirmar el resultado (y lo aclara). Sin ninguna app de música abierta antes, «poné música» puede no tener a quién pedírselo.
- **Abrir una app de música** sólo es posible con Sara visible (Android bloquea abrir actividades desde segundo plano).
- **Reconocimiento online del sistema:** requiere un servicio instalado (normalmente la app de Google). Cuando Sara es el asistente predeterminado, el sistema marca el reconocedor de Sara como predeterminado; por eso Sara elige explícitamente el de Google.
- **`SaraRecognitionService`** existe porque Android lo exige para registrar un asistente; otras apps sólo obtienen audio si Sara puede usar el micrófono en ese momento.
- **Voz offline:** su naturalidad depende del motor TTS del teléfono. No existe voz rioplatense offline en la mayoría de los motores; se elige la más cercana (AR/UY > Latinoamérica > España).
- **Modelo Vosk pequeño:** frases muy largas o vocabulario poco común se reconocen peor que con el motor online.
- **APK de ~60 MB:** incluye bibliotecas nativas de Vosk y Porcupine para ARM 64/32 bits y x86_64.

---

## 11. Próximos pasos sugeridos
- Modelo conversacional: implementar `CommandInterpreter` remoto y pasarlo a `CompositeCommandInterpreter` (sólo se consulta si las reglas no entienden la orden).
- Nuevas intenciones (alarmas, llamadas, mensajes) sumando reglas y ejecutores sin tocar los motores.

Licencia: MIT (ver `LICENSE`, incluye componentes de terceros).
