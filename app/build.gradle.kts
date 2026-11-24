import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.qrikkiyomi"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "app.qrikkiyomi"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.4.5"
    }

    // Signing (2026-11-15): the key lives in the project's persistent
    // .local/ — never the machine-default debug keystore, which sits in
    // the build VM's home and is silently regenerated with a DIFFERENT
    // key when the VM is replaced, breaking update installs and wiping
    // app data on reinstall (the failure that hit another project).
    // Falls back to the default debug config when .local/ is absent
    // (e.g. a fresh checkout on a normal machine).
    val localKeystore = rootProject.file(".local/qr-ikkiyomi.jks")
    val localKeystorePassword = rootProject.file(".local/keystore-password.txt")
        .takeIf { it.exists() }?.readText()?.trim()
    signingConfigs {
        if (localKeystore.exists() && !localKeystorePassword.isNullOrEmpty()) {
            create("persistent") {
                storeFile = localKeystore
                storePassword = localKeystorePassword
                keyAlias = "qr-ikkiyomi"
                keyPassword = localKeystorePassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("persistent")
                ?: signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("persistent")
                ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        // No BuildConfig: generating even one .java file makes AGP run javac in
        // a Gradle Worker Daemon, whose loopback TCP is intercepted in the
        // author's build VM ("Never received a connection from Gradle Worker
        // Daemon"). Pure-Kotlin sources compile in-process instead. Version
        // info is read from PackageManager at runtime (see SettingsScreen).
        buildConfig = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            // Pre-delivery UI verification (docs/VERIFICATION.md): the
            // Robolectric screenshot / navigation tests need the merged
            // manifest and resources on the unit-test classpath.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // CameraX
    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // ML Kit barcode scanning (bundled model: works fully offline)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // QR generation
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")

    // Pre-delivery UI verification on the JVM (docs/VERIFICATION.md):
    // Robolectric renders the real screens without a device, Roborazzi
    // pins them to golden screenshots. Pinned to the versions the local
    // build VM's mirror carries (Robolectric 4.14.1 = SDK 35 android-all).
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.35.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.35.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.2.1")
    // Real org.json for the plain unit tests: the android.jar stub throws
    // "not mocked" for JSONObject, and this jar precedes it on the test
    // classpath (docs/VERIFICATION.md).
    testImplementation("org.json:json:20240303")
}

// The Gradle Test executor cannot run in the author's build VM (its
// worker-daemon loopback is intercepted), so unit tests there are run
// by driving JUnitCore directly (see run-tests.sh). This task dumps the
// exact unit-test runtime classpath AGP assembled, so the direct run
// sees the same classes, resources and test_config.properties.
tasks.register("writeTestRuntimeClasspath") {
    // NOTE: do NOT wire testTask's classpath into inputs.files — Gradle then
    // pulls testDebugUnitTest itself into the task graph, and the Gradle Test
    // Executor cannot run in this VM (see docs/VERIFICATION.md). Depending on
    // the compile tasks explicitly and reading the classpath lazily in doLast
    // yields the same file list without executing the Test task.
    dependsOn("compileDebugUnitTestKotlin")
    val testTask = tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest")
    val outFile = layout.buildDirectory.file("test-runtime-classpath.txt")
    outputs.file(outFile)
    doLast {
        outFile.get().asFile.writeText(
            testTask.get().classpath.files
                .joinToString(File.pathSeparator) { it.absolutePath }
        )
    }
}
