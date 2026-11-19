import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // org.json la trae Android; en las pruebas se agrega.
    compileOnly(libs.org.json)
    testImplementation(libs.org.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // Prueba contra el Receptor de Windows real (.NET): la activa la compilación en la nube.
    System.getenv("LUCY_INTEROP_QR")?.let { environment("LUCY_INTEROP_QR", it) }
    System.getenv("LUCY_INTEROP_UDP")?.let { environment("LUCY_INTEROP_UDP", it) }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
