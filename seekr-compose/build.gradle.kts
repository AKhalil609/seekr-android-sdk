import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "tv.seekr.previews.compose"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
}

dependencies {
    api(project(":seekr-android"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
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
