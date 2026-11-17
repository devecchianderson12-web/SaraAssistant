# Lucy para Windows

Lucy es **la misma asistente que Sara**, con el mismo cerebro: el módulo `core` (intérprete de
órdenes, memoria, rutinas por frase, habilidades, ejecutor con respuestas breves, IA con Google o
Claude y el orquestador de la conversación) es compartido. Sólo cambian el nombre, la palabra de
activación y la parte que toca el sistema (módulo `desktop`).

## Cómo se usa

1. Bajá `Lucy-Windows-v1.0.N.exe` de la última release (al lado del APK) y abrilo. No se instala ni
   pide administrador. La primera vez muestra «Preparando Lucy…» unos segundos.
2. Decí **«Lucy»** (o «Ey Lucy» si lo elegís en Ajustes), tocá la esfera o apretá **Ctrl+Alt+L**.
   También se puede decir todo junto: «Lucy, poné música».
3. Las órdenes se hacen en silencio con «Okey, voy.»; sólo habla de más cuando la respuesta es
   información (hora, clima, cuentas, preguntas).

## Qué hace (igual que en el teléfono)

| Función | En Windows |
|---|---|
| Música: poner, pausar, seguir, pasar, volver, sacar | Sesiones multimedia de Windows (Spotify, navegador, reproductores). «Poné X» usa la app que más usás: YouTube arranca el primer resultado solo; Spotify abre la búsqueda. |
| Volumen: subir, bajar, fijar, silenciar, máximo | Volumen del sistema (Core Audio). |
| Abrir apps y sitios | Apps del menú Inicio y sitios conocidos (YouTube, Gmail, WhatsApp Web, Netflix…). |
| Cerrar apps en segundo plano | Cierra con la X (si hay algo sin guardar, la app pregunta), menos la que estás usando. |
| Recordatorios, alarmas, temporizadores y rutinas | Suenan y Lucy avisa (con aviso en la bandeja) mientras Lucy esté abierta; arranca con Windows. |
| Clima | Open-Meteo, por tu zona (estimada por la conexión) o la ciudad de Ajustes. |
| Brillo, Wi-Fi, Bluetooth, batería, información | Brillo de pantallas de notebook, radios de Windows, WMI. Conectar un Bluetooth abre la lista. |
| Calendario y mapas | Abre Google Calendar (evento listo para guardar) y Google Maps. |
| Listas, notas, compras, cuentas, conversiones | Igual que Android. |
| Memoria y rutinas por frase | Igual que Android («cuando te diga buenas noches, sacá la música»). |
| Preguntas, Google, traductor, planificador | Igual que Android (clave de Google o Claude en Ajustes › IA). |
| Charla («¿cómo estás, Lucy?») | Igual que Android. |

**Sólo en el teléfono:** WhatsApp, llamadas, SMS, notificaciones y hogar. Lucy lo dice en vez de
simularlo.

## Esfera

La misma esfera de Sara, que late con la voz. Aparece arriba a la derecha al llamarla, sobre
cualquier ventana; se arrastra para moverla y con doble clic vuelve a la esquina. En Ajustes ›
Esfera se puede dejar siempre visible y cambiar el tamaño.

## Privacidad y permisos

- Todo queda en `%APPDATA%\Lucy` (ajustes, memoria, listas, historial). Las claves de IA se cifran
  con DPAPI (sólo tu usuario de Windows las puede leer).
- La palabra de activación y el reconocimiento son locales (Vosk). Si hay clave de Google y está
  activado «Entender mejor las órdenes», el audio de la orden (no el de la espera) se manda a Gemini.
- Si no escucha: Configuración de Windows › Privacidad › Micrófono › permitir a las apps de
  escritorio.

## Cómo se arma (CI)

`windows-latest`: pruebas de `core` y `desktop` → `jpackage` (Java propio, sólo los módulos
necesarios) con el modelo de voz en español → lanzador .NET Framework 4.8 con ícono que trae todo
adentro → **autoprueba del .exe final** (entiende las órdenes con «Lucy», responde, puente con
Windows, modelo de voz, ventana y esfera). Si falla, no se publica.
