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
    // В ветке MAIN сохраняем JLine для красивого интерфейса на Windows
    implementation("org.jline:jline:3.26.3")
    implementation("org.fusesource.jansi:jansi:2.4.1")
    implementation("net.java.dev.jna:jna:5.14.0")
    
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Заменяем jvmToolchain на более простые настройки, 
// которые работают с отключенным auto-detect
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}
