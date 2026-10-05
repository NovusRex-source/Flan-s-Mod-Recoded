plugins {
    id("net.fabricmc.fabric-loom")
    kotlin("jvm")
    kotlin("plugin.serialization")
}

val prop = { key: String -> providers.gradleProperty(key).get() }

version = prop("mod_version")
group = prop("maven_group")

repositories {
    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
        content { includeGroup("maven.modrinth") }
    }
    maven("https://maven.shedaniel.me/") { name = "Shedaniel" }       // Cloth Config
    maven("https://maven.terraformersmc.com/releases/") { name = "Terraformers" } // Mod Menu
    maven("https://maven.blamejared.com/") { name = "BlameJared" } // JEI
}

loom {
    splitEnvironmentSourceSets()

    mods {
        register("flansmod") {
            sourceSet(sourceSets.main.get())
            sourceSet(sourceSets.getByName("client"))
        }
    }
}

fabricApi {
    // Server GameTests in src/gametest (./gradlew runGameTest, also part of ./gradlew check)
    configureTests {
        createSourceSet = true
        modId = "flansmod-test"
        eula = true
        enableClientGameTests = true
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${prop("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${prop("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${prop("fabric_api_version")}")
    // Bundles Kotlin stdlib, coroutines and kotlinx.serialization at runtime
    implementation("net.fabricmc:fabric-language-kotlin:${prop("fabric_kotlin_version")}")

    implementation("maven.modrinth:geckolib:${prop("geckolib_version")}")
    implementation("me.shedaniel.cloth:cloth-config-fabric:${prop("cloth_config_version")}") {
        exclude(group = "net.fabricmc.fabric-api")
    }
    implementation("com.terraformersmc:modmenu:${prop("modmenu_version")}")

    // JEI is optional: compiled against its API, present at runtime only in dev.
    val jei = prop("jei_version")
    compileOnly("mezz.jei:jei-26.3-common-api:$jei")
    compileOnly("mezz.jei:jei-26.3-fabric-api:$jei")
    localRuntime("mezz.jei:jei-26.3-fabric:$jei")
}

tasks.processResources {
    val version = project.version
    inputs.property("version", version)
    filesMatching("fabric.mod.json") { expand("version" to version) }
}

java {
    withSourcesJar()
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

kotlin {
    jvmToolchain(25)
}
