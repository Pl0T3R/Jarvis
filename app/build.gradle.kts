import java.net.URI
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// sherpa-onnx (offline wake word, VAD, Whisper and Piper) is not on Maven Central; the AAR is fetched
// from its GitHub release once and checked against a fixed SHA-256.
val sherpaVersion = "1.13.8"
val sherpaSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")

fun sha256(f: File): String =
    MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

if (!sherpaAar.exists() || sha256(sherpaAar) != sherpaSha256) {
    sherpaAar.parentFile.mkdirs()
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
    logger.lifecycle("Downloading $url")
    URI(url).toURL().openStream().use { input -> sherpaAar.outputStream().use { input.copyTo(it) } }
    check(sha256(sherpaAar) == sherpaSha256) { "sherpa-onnx AAR checksum mismatch" }
}

android {
    namespace = "com.pl0t3r.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pl0t3r.jarvis"
        minSdk = 32
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // A fixed key kept in the repo, so every build (cloud or PC) installs as an update.
        create("jarvis") {
            storeFile = rootProject.file("keystore/jarvis.jks")
            storePassword = "jarvis-quest"
            keyAlias = "jarvis"
            keyPassword = "jarvis-quest"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("jarvis") }
        release {
            // R8 cuts the Anthropic SDK down to what JARVIS uses (the APK would be ~80 MB otherwise)
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("jarvis")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/*.kotlin_module", "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(files(sherpaAar))
    implementation("com.anthropic:anthropic-java:2.70.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
}

// Optional render of voice samples by the unit tests: ./gradlew test -PjarvisSamples=<dir with replies/*.wav>
tasks.withType<Test>().configureEach {
    project.findProperty("jarvisSamples")?.let { systemProperty("jarvis.samples", it.toString()) }
    project.findProperty("jarvisTranscripts")?.let { systemProperty("jarvis.transcripts", it.toString()) }
}
