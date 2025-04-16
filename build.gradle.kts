plugins {
    kotlin("jvm") version "2.1.10"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    // TagExtractor에서 사용하는 라이브러리들
    // json
    implementation("com.google.code.gson:gson:2.13.0")
    // http networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // coroutine
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

tasks.test {
    useJUnitPlatform()
}