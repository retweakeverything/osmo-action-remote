import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin port of the DJI R SDK codec verified in tools/mac-probe.
// No Android or third-party dependencies, so it is unit-tested on the JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.junit)
}
