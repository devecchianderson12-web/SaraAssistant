# Vosk usa JNA: conservar las clases nativas y sus firmas.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
# Porcupine
-keep class ai.picovoice.** { *; }
