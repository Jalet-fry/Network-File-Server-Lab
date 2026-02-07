plugins {
    kotlin("jvm") version "2.0.20"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

application {
    mainClass.set("sspoirs.MainKt")
}

dependencies {
    // В версии для Termux удалены зависимости jline, jansi и jna
    // Они требуют нативные библиотеки, которые не поддерживаются в Termux
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// УДАЛЕНО: jvmToolchain(17) - это вызывает ошибку SystemInfo в Termux
// Вместо этого используем стандартные настройки целевой версии Java
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}
