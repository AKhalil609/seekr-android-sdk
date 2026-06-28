plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
    alias(libs.plugins.maven.publish)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Exposed: callers await loadTrack(...) on their own dispatcher.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}

// Coordinates, POM and signing come from gradle.properties (root + this module) and are
// driven by the com.vanniktech.maven.publish plugin. It also wires the sources + javadoc jars.
