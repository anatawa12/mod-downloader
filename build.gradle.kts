plugins {
    kotlin("jvm") version "1.6.10"
    kotlin("plugin.serialization") version "1.6.10"
    id("com.anatawa12.compile-time-constant") version "1.0.5"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    application
}

group = "com.anatawa12"
version = property("version").toString()

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(platform("io.ktor:ktor-bom:1.6.7"))
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-io")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.2")

    testImplementation(platform("io.kotest:kotest-bom:5.1.0"))
    testImplementation("io.kotest:kotest-framework-api")
    testImplementation("io.kotest:kotest-assertions-core")

    testRuntimeOnly(platform("io.kotest:kotest-bom:5.0.3"))
    testRuntimeOnly("io.kotest:kotest-runner-junit5")
}

application {
    mainClass.set("com.anatawa12.downloader.Main")
}

tasks.createCompileTimeConstant {
    constantsClass = "com.anatawa12.downloader.Constants"
    values(mapOf("version" to "$version"))
}

tasks.jar.get().dependsOn(tasks.shadowJar)
tasks.jar.get().enabled = false
tasks.shadowJar.get().archiveClassifier.set("")

tasks.withType<Test> {
    useJUnitPlatform()
}
