import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-parcelize")
}

// Task to build the Go library with native JNI
tasks.register<Exec>("buildGoLibrary") {
    group = "build"
    description = "Build the Go kinetic library with native JNI"
    workingDir = file("src/main/go")

    // Detect OS and use appropriate command
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")

    if (isWindows) {
        // On Windows, use PowerShell directly (no bash)
        commandLine("powershell", "-ExecutionPolicy", "Bypass", "-File", "./build_native.ps1")
    } else {
        // Unix-like systems (Linux, macOS)
        commandLine("bash", "./build_native.sh")
    }

    // Set environment variables if needed
    // Look for NDK in the Android SDK location
    val androidHome = System.getenv("ANDROID_HOME") ?:
        if (isWindows) "C:/Users/${System.getProperty("user.name")}/AppData/Local/Android/Sdk"
        else "/home/kevin/android"

    // Find the NDK version - prefer 26.x for compatibility
    val ndkDir = file("$androidHome/ndk")
    val ndkVersion = if (ndkDir.exists()) {
        ndkDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted()?.firstOrNull { it.startsWith("26.") }
            ?: ndkDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted()?.lastOrNull()
    } else {
        null
    }

    val androidNdk = System.getenv("ANDROID_NDK") ?:
        if (ndkVersion != null) "$androidHome/ndk/$ndkVersion"
        else if (isWindows) "$androidHome/ndk/26.1.10909125"
        else "/home/kevin/android/ndk/27.0.12077973"

    environment("ANDROID_NDK", androidNdk)
    environment("ANDROID_HOME", androidHome)
}

// Make preBuild depend on buildGoLibrary
tasks.named("preBuild") {
    dependsOn("buildGoLibrary")
}

android {
    namespace = "com.kevmo314.kineticstreamer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kevmo314.kineticstreamer"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        aidl = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation("androidx.camera:camera-core:1.3.3")
    implementation("androidx.camera:camera-camera2:1.3.3")
    implementation("androidx.camera:camera-lifecycle:1.3.3")
    implementation("androidx.camera:camera-video:1.3.3")
    implementation("androidx.camera:camera-view:1.3.3")
    implementation("androidx.camera:camera-extensions:1.3.3")

    implementation("androidx.concurrent:concurrent-futures:1.1.0")

    implementation("androidx.datastore:datastore-preferences:1.1.0")

    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.accompanist:accompanist-permissions:0.32.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
