import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    id("dev.architectury.loom") version "1.13-SNAPSHOT" apply false
    kotlin("jvm") version "2.2.21" apply false
    id("com.gradleup.shadow") version "9.2.2" apply false
}

version = "0.6.0-dev.2"

val modVersion = version.toString()

subprojects {
    if (name == "core") return@subprojects

    apply(plugin = "dev.architectury.loom")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.gradleup.shadow")

    val loader = property("loom.platform") as String
    val mcVersion = property("minecraft_version") as String
    val mcRange = property("minecraft_range") as String
    val compat = property("compat") as String
    val javaVersion = (property("java_version") as String).toInt()
    val loaderDeps: List<Pair<String, String>> = when (loader) {
        "fabric" -> listOf(
            "modImplementation" to "net.fabricmc:fabric-loader:${property("fabric_loader_version")}",
            "modImplementation" to "net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}",
        )
        "forge" -> listOf("forge" to "net.minecraftforge:forge:${property("forge_version")}")
        "neoforge" -> listOf("neoForge" to "net.neoforged:neoforge:${property("neoforge_version")}")
        else -> error("Unknown loader: $loader")
    }

    group = "dangomusicce"
    version = modVersion
    extensions.configure<BasePluginExtension> {
        archivesName.set("dangomusicce")
    }

    repositories {
        mavenCentral()
        maven("https://maven.neoforged.net/releases/") { name = "NeoForge" }
    }

    val tagsOutputDir = layout.buildDirectory.dir("generated/sources/tags/java")
    val generateTags by tasks.registering {
        val currentVersion = modVersion
        inputs.property("version", currentVersion)
        outputs.dir(tagsOutputDir)
        doLast {
            val file = tagsOutputDir.get().file("dangomusicce/Tags.java").asFile
            file.parentFile.mkdirs()
            file.writeText(
                """
                package dangomusicce;

                public final class Tags {
                    public static final String VERSION = "$currentVersion";

                    private Tags() {}
                }
                """.trimIndent() + "\n",
                Charsets.UTF_8,
            )
        }
    }

    val sharedKotlin = rootProject.file("src/main/kotlin")
    val compatKotlin = rootProject.file("platform/mc$compat/kotlin")
    val sharedResources = rootProject.file("src/main/resources")

    extensions.configure<SourceSetContainer> {
        val main = getByName("main")
        main.java.srcDir(tagsOutputDir)
        main.resources.srcDir(sharedResources)
    }

    extensions.configure<KotlinJvmProjectExtension> {
        sourceSets.getByName("main").kotlin.srcDir(sharedKotlin)
        sourceSets.getByName("main").kotlin.srcDir(compatKotlin)
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))
        }
    }

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.toVersion(javaVersion)
        targetCompatibility = JavaVersion.toVersion(javaVersion)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(javaVersion)
        options.encoding = "UTF-8"
    }

    tasks.named("compileJava") {
        dependsOn(generateTags)
    }
    tasks.named("compileKotlin") {
        dependsOn(generateTags)
    }

    val shade = configurations.create("shade") {
        isCanBeResolved = true
        isCanBeConsumed = false
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        }
    }
    configurations.getByName("implementation").extendsFrom(shade)

    val loom = extensions.getByType<LoomGradleExtensionAPI>()
    dependencies {
        "minecraft"("com.mojang:minecraft:$mcVersion")
        "mappings"(loom.officialMojangMappings())
        loaderDeps.forEach { (configuration, notation) -> configuration(notation) }

        "shade"(project(":core"))
        "shade"(kotlin("stdlib"))
    }

    tasks.named<ProcessResources>("processResources") {
        exclude("mcmod.info", "assets/dangomusicce/lang/*.lang")
        val props = mapOf(
            "version" to modVersion,
            "minecraft_range" to mcRange,
            "java_version" to javaVersion.toString(),
            "target" to "$mcVersion-$loader",
            "mod_id" to "dangomusicce",
            "mod_name" to "DangoMusic Community Edition",
        )
        inputs.properties(props)
        filesMatching(listOf("fabric.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml", "dangomusic-build.properties")) {
            expand(props)
        }
    }

    val shadowJar = tasks.named<ShadowJar>("shadowJar") {
        configurations.set(listOf(shade))
        archiveClassifier.set("dev-shadow")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        relocate("kotlin", "dangomusicce.shadow.kotlin")
        relocate("org.intellij.lang.annotations", "dangomusicce.shadow.annotations.intellij")
        relocate("org.jetbrains.annotations", "dangomusicce.shadow.annotations.jetbrains")
        relocate("javazoom", "dangomusicce.shadow.javazoom")
        relocate("com.jcraft", "dangomusicce.shadow.jcraft")
        relocate("com.google.zxing", "dangomusicce.shadow.zxing")
        exclude("module-info.class", "META-INF/versions/**", "META-INF/*.kotlin_module", "META-INF/maven/**")
        addMultiReleaseAttribute.set(false)
    }

    tasks.named<RemapJarTask>("remapJar") {
        inputFile.set(shadowJar.flatMap { it.archiveFile })
        archiveVersion.set("$modVersion+$mcVersion")
        archiveClassifier.set(loader)
    }
}

tasks.register("build") {
    dependsOn(":core:check", ":1.21.1-neoforge:build")
}
