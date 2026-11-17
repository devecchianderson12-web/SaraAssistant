pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SaraAssistant"
include(":core", ":desktop")
// En la compilación de Windows (Lucy) no hace falta el SDK de Android.
if (System.getenv("LUCY_ONLY") == null) include(":app")
