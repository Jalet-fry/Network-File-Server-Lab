plugins {
    kotlin("jvm") version "2.0.20"
    application // Добавляем плагин для запуска
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

application {
    mainClass.set("sspoirs.MainKt") // Указываем входную точку
}

dependencies {
    implementation("org.jline:jline:3.26.3")
    implementation("org.fusesource.jansi:jansi:2.4.1")
    implementation("net.java.dev.jna:jna:5.14.0")
    
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
