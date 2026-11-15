import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "tv.seekr.previews.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // android.jar stubs throw on use; the tests only touch interfaces (SharedPreferences) and pure code.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":seekr-core"))
    api(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    // Same version as libs.kotlinx.coroutines.android; not in the shared catalog to keep this module self-contained.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

// Coordinates, POM and signing come from gradle.properties (root + this module), driven by
// the com.vanniktech.maven.publish plugin. AGP's bundled Javadoc generator crashes on this
// Kotlin toolchain, so we disable it and attach an empty javadoc jar (the API is documented
// in the published -sources.jar; Central only requires a signed -javadoc.jar to exist).
mavenPublishing {
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = false,
        )
    )
}

val emptyJavadocJar = tasks.register<Jar>("emptyJavadocJar") {
    archiveClassifier.set("javadoc")
}
afterEvaluate {
    extensions.configure(PublishingExtension::class.java) {
        publications.withType(MavenPublication::class.java).configureEach {
            artifact(emptyJavadocJar)
        }
    }
}
