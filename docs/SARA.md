# Sara — asistente personal autónomo (V28 en adelante)

Este documento resume la arquitectura actual, qué hace cada habilidad, qué permisos y
configuraciones necesita y qué falta probar en un teléfono real.

## 1. Cómo piensa Sara

```
Voz («Sara» o tocar la nebulosa)
  └─ Reconocimiento (Google / Vosk / Azure)
       └─ Intérprete
            1. Rutina por voz guardada («cuando te diga buenas noches…»)
            2. Varias órdenes en una frase («poné música y bajá el volumen al 30») → Secuencia
            3. Reglas locales (sin Internet, instantáneas):
               memoria · WhatsApp · charla · HABILIDADES · cerrar apps · abrir apps · volumen · música
            4. Pregunta de información → IA con búsqueda (Google primero)
            5. Pedido nuevo o complejo → PLANIFICADOR (IA con herramientas = habilidades)
       └─ Ejecutor
            · Acciones sensibles → confirmación («¿Le mando "Hola" a Juan?»)
            · Faltan datos → pregunta de seguimiento («¿Qué le digo?»)
            · Límite de 60 s por orden: Sara nunca queda trabada
       └─ Respuesta hablada + conversación continua (opcional)
```

### Planificador

- **Claude** (si hay clave) planifica; si no, **Gemini** con funciones. Si el primero falla, prueba el otro.
- Sólo puede actuar con las habilidades registradas: nunca ejecuta código ni texto arbitrario.
- Cada parámetro se valida antes de ejecutar. Máximo 8 pasos por pedido.
- Las acciones sensibles piden confirmación al usuario antes de ejecutarse.
- Cada herramienta devuelve **HECHO / PEDIDO / A MEDIAS / FALTA PERMISO / FALLÓ**. La IA tiene
  prohibido decir que algo se hizo si no recibió **HECHO**.
- Las consultas se reintentan una vez si fallan; las acciones que cambian algo, nunca.

### Proveedores

| Uso | Primero | Alternativa |
|---|---|---|
| Órdenes conocidas, cuentas, hora, listas, alarmas… | Local (sin Internet) | — |
| Clima | Open-Meteo (gratis, sin clave, ~1 s) | — |
| Preguntas con datos actuales | Google (Gemini + búsqueda) | Claude (búsqueda web) |
| Planificación de pedidos complejos | Claude | Gemini (funciones) |

Las claves se guardan cifradas con el Keystore de Android y se excluyen de las copias de seguridad.

## 2. Habilidades

Riesgo: **consulta** (no cambia nada), **cambio** (reversible), **sensible** (pide confirmación).

| Habilidad | Acciones | Riesgo | Permisos | Verificación |
|---|---|---|---|---|
| teléfono (música, volumen, apps, WhatsApp, memoria) | poner/controlar música, volumen, abrir app, cerrar apps, WhatsApp, recordar, olvidar | cambio · WhatsApp y olvidar: sensible | Control de música, Accesibilidad, Contactos, Abrir apps | Sesión multimedia reproduciendo; volumen releído; WhatsApp: botón Enviar tocado |
| alarma | alarma, temporizador, ver | cambio | — (Abrir apps si estás en otra app) | La próxima alarma del sistema coincide con la pedida |
| recordatorios | crear, ver, borrar | cambio | — | Guardado y programado con AlarmManager |
| rutinas | programar, ver, pausar, reanudar, borrar | cambio · borrar: sensible | — | Guardada y programada |
| clima | consultar (hoy, mañana, pasado) | consulta | Ubicación (o decir la ciudad) | Respuesta de Open-Meteo |
| calculadora | calcular, convertir | consulta | — | Cálculo local |
| dispositivo | linterna, brillo, Wi-Fi, Bluetooth, No molestar, batería, info | cambio | Modificar ajustes (brillo), No molestar | Linterna: callback de cámara; brillo y No molestar: relectura del sistema |
| llamadas | llamar, SMS | llamar: sensible | Contactos, Llamadas (opcional) | Sin permiso de llamadas deja el número marcado |
| calendario | ver (hoy/mañana), crear | consulta / cambio | Calendario | Crear abre el calendario: el usuario toca Guardar |
| mapas | navegar, lugares cercanos, ¿dónde estoy? | cambio / consulta | Ubicación | Abre Google Maps |
| notificaciones | leer resumen | consulta | Control de música (acceso a notificaciones) | Lista de notificaciones activas |
| listas | notas, tareas, compras: agregar, leer, completar, quitar, vaciar | cambio · vaciar: sensible | — | Se relee la lista guardada |
| google | buscar | consulta | Clave de IA | — |
| traductor | traducir | consulta | Clave de IA | — |
| hogar | abre Google Home | cambio | — | Ver limitaciones |
| diagnóstico | revisar | consulta | — | Permisos, conexión, IA, accesibilidad, detección y alarmas exactas |

Ejemplos: «despertame a las 7 y media», «temporizador de 10 minutos», «recordame mañana a las 9
llamar al médico», «todos los días a las 8 decime el clima», «¿va a llover mañana en Córdoba?»,
«cuánto es 15 por ciento de 200», «3 pulgadas a centímetros», «prendé la linterna», «brillo al 40»,
«¿cuánta batería me queda?», «llamá a mamá», «¿qué tengo mañana?», «llevame a Plaza de Mayo»,
«farmacias cerca de mí», «leeme las notificaciones», «agregá leche a la lista de compras»,
«cómo se dice buenos días en inglés», «diagnóstico».

## 3. Memoria

- Preferencias: «cada vez que te pida música abrí BitChord» (se cambia y se olvida por voz).
- Rutinas por voz: «cuando te diga buenas noches, hacé sacá la música y cerrá las apps».
- Datos personales: «acordate que…». Sólo se guardan si el usuario lo pide.
- Conversación: la IA recuerda los últimos minutos de charla y la conversación se reinicia sola.
- Todo se consulta («¿qué recordás?») y se borra desde Ajustes › Inteligencia y memoria.

## 4. Permisos y configuración

| Permiso | Para qué | Cómo se activa |
|---|---|---|
| Micrófono | Escuchar | Diálogo del sistema |
| Notificaciones | Avisos y botones | Diálogo del sistema |
| Control de música (acceso a notificaciones) | Música, «leeme las notificaciones» | Ajustes del sistema (*) |
| Batería sin restricciones | Escuchar con pantalla bloqueada | Diálogo directo |
| Abrir apps (mostrar sobre otras apps) | Abrir apps, alarmas y mapas desde segundo plano | Ajustes del sistema |
| Accesibilidad | Cerrar apps, enviar WhatsApp | Ajustes del sistema (*) |
| Contactos | WhatsApp, llamadas y SMS por nombre | Diálogo del sistema |
| Ubicación | Clima local, «dónde estoy» | Diálogo del sistema |
| Llamadas | Llamar sin tocar | Diálogo del sistema |
| Calendario | Leer eventos | Diálogo del sistema |
| Modificar ajustes | Brillo | Ajustes del sistema |
| No molestar | Activar/desactivar No molestar | Ajustes del sistema |
| Clave de IA | Preguntas abiertas y planificación | Ajustes › Inteligencia (Google es gratis) |

(*) Si aparece gris: Info de la app › ⋮ › «Permitir ajustes restringidos». En Xiaomi también hace
falta activar «Inicio automático».

En Inicio sólo aparecen los permisos esenciales y el que pidió la última orden. Todos los demás
están en Ajustes › Permisos, cada uno con su botón.

## 5. Limitaciones reales de Android (y la alternativa usada)

- **Wi-Fi y Bluetooth:** desde Android 10 una app no puede prenderlos ni apagarlos. Sara consulta
  el estado y abre el panel del sistema.
- **SMS:** se deja el mensaje escrito en la app de mensajes y vos tocás enviar. Enviar SMS solo
  exige ser la app de SMS predeterminada.
- **Hogar inteligente:** Google no ofrece una API para que otras apps controlen Google Home. Sara
  abre Google Home (o Home Assistant, Mi Home, Smart Life si están instalados).
- **Alarmas:** se crean en la app de reloj. Desde segundo plano hace falta el permiso «Abrir apps»;
  si no lo tiene, Sara deja una notificación para tocar.
- **Calendario:** crear eventos abre el calendario con los datos cargados y vos tocás Guardar
  (escribir directo requiere otro permiso).
- **Interrumpir a Sara mientras habla:** el micrófono se usa para la voz de Sara y la cancelación
  de eco no es confiable en todos los teléfonos. Para cortarla, tocá la nebulosa.
- **Recordatorios exactos:** si el sistema limita las alarmas exactas, pueden demorarse unos
  minutos. El diagnóstico lo avisa.
- **Palabra de activación:** funciona con pantalla apagada mediante un servicio en primer plano
  (micrófono). Algunos fabricantes cortan servicios en segundo plano si no se quita la
  optimización de batería.

## 6. Compilar y probar

```bash
./gradlew test assembleDebug   # JDK 17
```

GitHub Actions (`.github/workflows/build.yml`) corre las pruebas y compila en cada push. En `main`
publica el release `SaraAssistantV<n>`, y la app avisa sola que hay versión nueva.

## 7. Qué hay que probar en un teléfono real

1. Activación por voz con y sin música, y con la pantalla bloqueada.
2. Conversación continua: después de una respuesta, hablar sin decir «Sara».
3. Alarma, temporizador y recordatorio (también con la app cerrada y el teléfono bloqueado).
4. Rutina programada a una hora cercana.
5. Clima con ubicación y con ciudad.
6. Linterna, brillo (después de dar el permiso), No molestar, batería.
7. Llamada (con y sin permiso de llamadas) y SMS.
8. Calendario: leer eventos de hoy y crear uno.
9. Mapas: navegar y lugares cercanos.
10. WhatsApp a un contacto y al chat «Notas» archivado.
11. Pedido complejo con IA, por ejemplo «poné música tranquila, bajá el volumen al 20 y recordame
    en una hora sacar la ropa».
12. Cerrar apps con Accesibilidad.
