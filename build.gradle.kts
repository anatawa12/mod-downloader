plugins {
    kotlin("jvm") version "1.7.10"
    kotlin("plugin.serialization") version "1.7.10"
    id("com.anatawa12.compile-time-constant") version "1.0.5"
    id("com.github.johnrengelman.shadow") version "8.0.0"
    application
}

group = "com.anatawa12"
version = property("version").toString()

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(platform("io.ktor:ktor-bom:2.0.3"))
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-io")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.3")

    testImplementation(platform("io.kotest:kotest-bom:5.4.1"))
    testImplementation("io.kotest:kotest-framework-api")
    testImplementation("io.kotest:kotest-assertions-core")

    testImplementation("io.mockk:mockk:1.12.5")

    testRuntimeOnly(platform("io.kotest:kotest-bom:5.2.1"))
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
