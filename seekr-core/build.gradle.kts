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

// The playback-attestation secret is not in git: it comes from the publishing environment
// (-Pseekr.attestationSecret=… or SEEKR_ATTESTATION_SECRET) and is written into a generated
// source file. Built without one, the SDK sends no token.
val attestationSecret = providers.gradleProperty("seekr.attestationSecret")
    .orElse(providers.environmentVariable("SEEKR_ATTESTATION_SECRET"))
    .orElse("")
val attestationDir = layout.buildDirectory.dir("generated/seekr/kotlin")

val generateAttestationDefaults = tasks.register("generateAttestationDefaults") {
    inputs.property("secret", attestationSecret)
    outputs.dir(attestationDir)
    outputs.cacheIf { false } // never put the secret in a build cache
    doLast {
        val escaped = attestationSecret.get().replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        val dir = attestationDir.get().asFile.resolve("tv/seekr/previews/core/internal").apply { mkdirs() }
        dir.resolve("AttestationDefaults.kt").writeText(
            """
            package tv.seekr.previews.core.internal

            internal object AttestationDefaults {
                private const val SECRET = "$escaped"

                fun secret(): ByteArray? = SECRET.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
            }
            """.trimIndent() + "\n",
        )
    }
}

sourceSets.named("main") {
    java.srcDir(files(attestationDir).builtBy(generateAttestationDefaults))
}

dependencies {
    // Exposed: callers await loadTrack(...) on their own dispatcher.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}

// Coordinates, POM and signing come from gradle.properties (root + this module) and are
// driven by the com.vanniktech.maven.publish plugin. It also wires the sources + javadoc jars.
