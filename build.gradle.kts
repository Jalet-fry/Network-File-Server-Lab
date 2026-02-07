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
    // Чистая версия без нативных зависимостей
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Принудительно устанавливаем версию байт-кода без использования тулчейнов
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}
