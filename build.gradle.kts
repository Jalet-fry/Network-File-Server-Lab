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
    // В ЭТОЙ ВЕТКЕ (TERMUX) JLINE УДАЛЕН ДЛЯ МАКСИМАЛЬНОЙ СОВМЕСТИМОСТИ
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Принудительная совместимость с Java 17
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}
