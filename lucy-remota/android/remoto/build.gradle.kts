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

// Lo que une a los dos: el intérprete de frases de PC y el ejecutor que manda las órdenes a Windows.
dependencies {
    api(project(":nucleo"))
    api(project(":protocolo"))
    compileOnly(libs.org.json)
    testImplementation(libs.org.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    System.getenv("LUCY_INTEROP_QR")?.let { environment("LUCY_INTEROP_QR", it) }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
