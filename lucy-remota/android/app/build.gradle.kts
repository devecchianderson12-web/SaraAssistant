plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Voz Daniela (Piper) con sherpa-onnx: la librería se baja una vez de su release oficial.
val sherpaVersion = "1.13.8"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")
if (!sherpaAar.isFile) {
    sherpaAar.parentFile.mkdirs()
    val tmp = File(sherpaAar.path + ".part")
    uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar").toURL()
        .openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    tmp.renameTo(sherpaAar)
}

android {
    namespace = "com.lucyremota.emisor"
    compileSdk = 35

    defaultConfig {
        // Otra app (no reemplaza a Sara): Lucy Remota · Emisor.
        applicationId = "com.lucyremota.emisor"
        minSdk = 29
        targetSdk = 35
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        buildConfigField("String", "UPDATE_REPO", "\"devecchianderson12-web/SaraAssistant\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        // Clave de depuración fija y pública (NO usar para publicar): permite actualizar sin desinstalar.
        getByName("debug") {
            storeFile = rootProject.file("keystore/lucyremota-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*", "/META-INF/DEPENDENCIES",
            "/META-INF/INDEX.LIST", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF", "/META-INF/FastDoubleParser-*",
        )
        resources.pickFirsts += setOf("/META-INF/*.kotlin_module", "/META-INF/versions/9/module-info.class")
    }
    lint { abortOnError = false }
}

dependencies {
    implementation(project(":nucleo"))
    implementation(project(":protocolo"))
    implementation(project(":remoto"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)

    // Escanear el QR de la PC (sin servicios de Google: funciona sin Internet).
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    // Voz local: «Lucy» (Vosk) y la voz Daniela (Piper).
    implementation(libs.vosk.android)
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(files(sherpaAar))
    implementation(libs.commons.compress)
    implementation(libs.anthropic.java)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.junit)
}
