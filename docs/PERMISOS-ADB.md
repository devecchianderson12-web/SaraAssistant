# Dar todos los permisos de una vez desde una PC (opcional)

Android exige aprobar cada permiso a mano. Si tenés una PC, podés darlos todos juntos con ADB
(bajo tu supervisión, con el teléfono conectado por USB y la «Depuración USB» activada en
Opciones de desarrollador). Sólo afecta a SaraAssistant.

```bash
P=com.sara.assistant
# Permisos comunes
for p in RECORD_AUDIO POST_NOTIFICATIONS READ_CONTACTS ACCESS_COARSE_LOCATION CALL_PHONE READ_CALENDAR BLUETOOTH_CONNECT; do
  adb shell pm grant $P android.permission.$p
done
# Ajustes restringidos (apps instaladas fuera de Play Store)
adb shell appops set $P ACCESS_RESTRICTED_SETTINGS allow
# Mostrar sobre otras apps, modificar ajustes (brillo) y alarmas exactas
adb shell appops set $P SYSTEM_ALERT_WINDOW allow
adb shell appops set $P WRITE_SETTINGS allow
adb shell appops set $P SCHEDULE_EXACT_ALARM allow
# Batería sin restricciones
adb shell dumpsys deviceidle whitelist +$P
# Acceso a notificaciones (control de música) y a No molestar
adb shell cmd notification allow_listener $P/$P.media.SaraNotificationListener
adb shell cmd notification allow_dnd $P
# Accesibilidad (ojo: reemplaza la lista de servicios de accesibilidad activos)
adb shell settings put secure enabled_accessibility_services $P/$P.apps.SaraAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

El asistente predeterminado se elige en el teléfono: Ajustes › Apps › Apps predeterminadas ›
Asistente digital › SaraAssistant. En Xiaomi activá también «Inicio automático» para Sara.
