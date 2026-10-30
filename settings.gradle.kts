pluginManagement {
    repositories {
        val proxy = System.getenv("BLUETOOTH_FINDER_MAVEN_PROXY")
        if (proxy != null) maven { url = uri("$proxy/google"); isAllowInsecureProtocol = true }
        if (proxy != null) maven { url = uri("$proxy/central"); isAllowInsecureProtocol = true }
        google(); mavenCentral(); gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val proxy = System.getenv("BLUETOOTH_FINDER_MAVEN_PROXY")
        if (proxy != null) maven { url = uri("$proxy/google"); isAllowInsecureProtocol = true }
        if (proxy != null) maven { url = uri("$proxy/central"); isAllowInsecureProtocol = true }
        google(); mavenCentral()
    }
}
rootProject.name = "BluetoothFinder"
include(":app")
