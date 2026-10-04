pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("net.fabricmc.fabric-loom") version providers.gradleProperty("loom_version").get()
        val kotlinVersion = providers.gradleProperty("kotlin_version").get()
        kotlin("jvm") version kotlinVersion
        kotlin("plugin.serialization") version kotlinVersion
    }
}

rootProject.name = "flansmod-recoded"
