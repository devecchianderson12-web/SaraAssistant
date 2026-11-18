plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sara.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sara.assistant"
        minSdk = 29
        targetSdk = 35
        // En GitHub Actions el número de ejecución hace crecer versionCode en cada build.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Repositorio donde se publican las versiones (GitHub Releases) para el actualizador interno.
        buildConfigField("String", "UPDATE_REPO", "\"devecchianderson12-web/SaraAssistant\"")
        // Voces naturales sin que el usuario configure nada: clave de Azure Speech (plan gratis F0) que
        // pone la compilación en la nube desde los secretos del repositorio (nunca está en el código).
        buildConfigField("String", "AZURE_KEY", "\"${System.getenv("SARA_AZURE_KEY").orEmpty().trim()}\"")
        buildConfigField("String", "AZURE_REGION", "\"${System.getenv("SARA_AZURE_REGION").orEmpty().trim().lowercase()}\"")
        vectorDrawables.useSupportLibrary = true
        // Teléfonos ARM y emuladores x86_64 (x86 de 32 bits se omite para reducir el tamaño).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        // Clave de depuración fija y pública (NO usar para publicar): permite actualizar el APK
        // de CI sobre una instalación anterior sin desinstalar.
        getByName("debug") {
            storeFile = rootProject.file("keystore/sara-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
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
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Motores locales
    implementation(libs.vosk.android)
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.porcupine.android)
    // IA conversacional (Claude) con el SDK oficial de Anthropic.
    implementation(libs.anthropic.java)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
