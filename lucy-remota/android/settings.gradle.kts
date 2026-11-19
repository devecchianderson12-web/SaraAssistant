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

// Lucy Remota · Emisor (Android): proyecto independiente de Sara.
rootProject.name = "LucyRemota"
include(":protocolo", ":nucleo", ":remoto")
// Las pruebas del protocolo corren sin el SDK de Android (en la PC y en la nube).
if (System.getenv("SIN_ANDROID") == null) include(":app")
