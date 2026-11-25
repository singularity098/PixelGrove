import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ABIs to build the Rust engine for: phones (arm64) and the emulator (x86_64).
// `-Ppixelg.abis=arm64-v8a` builds just one, which halves the build time.
val abis = (findProperty("pixelg.abis") as String? ?: "arm64-v8a,x86_64").split(",").map { it.trim() }
val rustOut = layout.buildDirectory.dir("rustJniLibs")

// Release signing: from keystore.properties (or PIXELG_KEYSTORE_* environment variables);
// without one, release builds are signed with the debug key so they still install.
val keystore = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
    System.getenv("PIXELG_KEYSTORE_FILE")?.let { setProperty("storeFile", it) }
    System.getenv("PIXELG_KEYSTORE_PASSWORD")?.let { setProperty("storePassword", it) }
    System.getenv("PIXELG_KEY_ALIAS")?.let { setProperty("keyAlias", it) }
    System.getenv("PIXELG_KEY_PASSWORD")?.let { setProperty("keyPassword", it) }
}

android {
    namespace = "app.pixelg"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "app.pixelg"
        // Android 11: MediaStore generations, All files access, ThumbnailUtils for HEIC.
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += abis }
    }

    signingConfigs {
        if (keystore.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].jniLibs.srcDir(rustOut)

    packaging {
        jniLibs.useLegacyPackaging = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Builds the engine (android/core, which links the PixelGrove server crate) with
// cargo-ndk. Skip with -Ppixelg.skipRust when the libraries are already in place.
val cargoBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libpixelg.so with cargo-ndk"
    val core = rootProject.file("core")
    workingDir = core
    val args = mutableListOf("cargo", "ndk")
    abis.forEach { args += listOf("-t", it) }
    args += listOf("-P", "30", "-o", rustOut.get().asFile.absolutePath, "build", "--release", "--locked")
    commandLine(args)
    inputs.files(fileTree(core) { include("src/**", "Cargo.toml", "Cargo.lock") })
    inputs.files(fileTree(rootProject.file("..")) { include("src/**", "data/**", "Cargo.toml", "Cargo.lock") })
    outputs.dir(rustOut)
    onlyIf { !project.hasProperty("pixelg.skipRust") }
}
tasks.named("preBuild") { dependsOn(cargoBuild) }

// The engine built for this machine, so unit tests (src/test) can run it through JNI.
val hostEngine by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds libpixelg for the host, for unit tests"
    workingDir = rootProject.file("core")
    commandLine("cargo", "build", "--release", "--locked")
    onlyIf { !project.hasProperty("pixelg.skipRust") }
}
tasks.withType<Test>().configureEach {
    dependsOn(hostEngine)
    systemProperty("java.library.path", rootProject.file("core/target/release").absolutePath)
    // The engine starts once per process, and each test class starts its own.
    forkEvery = 1
    // Screenshots (ScreenshotTest) are written to build/outputs/roborazzi.
    systemProperty("roborazzi.test.record", "true")
    (project.findProperty("pixelg.demo") as String?)?.let { systemProperty("pixelg.demo", it) }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.osmdroid)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
