import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bunny.engine"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Kotlin 2.2 removed kotlinOptions; compilerOptions is set below.
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    // Lontar talks to InnerTube directly over HTTPS. These are platform-level
    // libraries (HTTP, coroutines) — no third-party YouTube code of any kind.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Fase 8 (Anyam): SabrDataSource is the Media3 face of the spool.
    // Only the datasource module — the player itself stays in :app.
    implementation("androidx.media3:media3-datasource:1.11.1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json on the JVM so parser tests exercise the same API the
    // Android runtime provides on device.
    testImplementation("org.json:json:20230618")
}
