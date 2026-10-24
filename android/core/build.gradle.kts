import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Camera session (handshake, commands, status) and workflow engine.
// Depends only on the CameraTransport interface, never on Android BLE, so
// it is unit-tested on the JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":protocol"))
    api(libs.coroutines.core)
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
