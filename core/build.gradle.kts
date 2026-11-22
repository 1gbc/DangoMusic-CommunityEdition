import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    kotlin("jvm")
}

repositories {
    mavenCentral()
    maven("https://libraries.minecraft.net")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.named<ProcessResources>("processResources") {
    // :core 中残留的上游 key.dangomusic.* JSON 不含 CE 键名；由各版本子模块提供完整的 dangomusicce lang JSON。
    exclude("assets/dangomusicce/lang/*.json")
}

dependencies {
    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("org.apache.logging.log4j:log4j-api:2.22.1")
    implementation("org.jcraft:jorbis:0.0.17")
    implementation("javazoom:jlayer:1.0.1")
    implementation("com.google.zxing:core:3.3.3")
}
