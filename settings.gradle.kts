pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.architectury.dev/") { name = "Architectury" }
        maven("https://maven.minecraftforge.net/") { name = "Forge" }
        maven("https://maven.neoforged.net/releases/") { name = "NeoForge" }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "DangoMusic-CommunityEdition"

include(":core")

val targets = listOf("1.21.1-neoforge")
targets.forEach { target ->
    include(":$target")
    project(":$target").projectDir = file("versions/$target")
}
