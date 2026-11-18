import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Lucy: la misma asistente que Sara (módulo core) en Windows. Se empaqueta como .exe portable en CI.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.sara.lucy.MainKt")
    applicationName = "Lucy"
}

dependencies {
    implementation(project(":core"))
    implementation(libs.org.json)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.vosk.jvm)
    implementation(libs.jna.jvm)
    implementation(libs.jna.platform.jvm)
    // Voces de Piper (API de Java de sherpa-onnx en src/main/java; las DLL las agrega la compilación de Windows).
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Número de versión (el mismo de la release: SaraAssistantV<n>), para avisar de actualizaciones.
val lucyVersion = providers.gradleProperty("lucyVersion").orElse("0")
val versionResource = tasks.register("lucyVersionResource") {
    val out = layout.buildDirectory.dir("generated/lucy-version")
    inputs.property("version", lucyVersion)
    outputs.dir(out)
    doLast { out.get().file("lucy-version.txt").asFile.apply { parentFile.mkdirs(); writeText(lucyVersion.get()) } }
}
sourceSets.main { resources.srcDir(versionResource) }

// Prueba de la palabra «Lucy» con el modelo real: ./gradlew :desktop:test -PlucyModel=<carpeta del modelo>
tasks.test {
    systemProperty("lucy.model", providers.gradleProperty("lucyModel").getOrElse(""))
}
