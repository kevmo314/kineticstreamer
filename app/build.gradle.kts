import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
}

val nativeAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
val goSourceDir = layout.projectDirectory.dir("src/main/go")
val thirdPartyOutputDir = goSourceDir.dir("third_party/output")
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")
val sharedThirdPartyLibs = listOf("libusb-1.0.so", "libsrt.so", "librist.so")
val isWindowsHost = System.getProperty("os.name").lowercase().contains("windows")

fun thirdPartyLibraries() = nativeAbis.flatMap { abi ->
    val libDir = thirdPartyOutputDir.dir("$abi/lib")
    val opensslLibExtension = if (isWindowsHost) "a" else "so"
    sharedThirdPartyLibs.map { libDir.file(it) } + listOf(
        libDir.file("libssl.$opensslLibExtension"),
        libDir.file("libcrypto.$opensslLibExtension"),
    )
}

fun jniLibraries() = nativeAbis.flatMap { abi ->
    val libDir = jniLibsDir.dir(abi)
    val requiredLibs = listOf(
        libDir.file("libkinetic.so"),
        libDir.file("libusb-1.0.so"),
        libDir.file("libsrt.so"),
        libDir.file("librist.so"),
    )
    if (isWindowsHost) requiredLibs else requiredLibs + listOf(
        libDir.file("libssl.so"),
        libDir.file("libcrypto.so"),
    )
}

fun Exec.configureNativeBuildEnvironment() {
    workingDir = goSourceDir.asFile

    // Set environment variables if needed
    // Look for NDK in the Android SDK location, falling back to local.properties / OS defaults
    val osName = System.getProperty("os.name").lowercase()
    val isWindows = osName.contains("windows")
    val isMac = osName.contains("mac") || osName.contains("darwin")
    val sdkDirFromProps = rootProject.file("local.properties").takeIf { it.exists() }
        ?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }
        ?.substringAfter("sdk.dir=")
    val androidHome = System.getenv("ANDROID_HOME") ?: sdkDirFromProps ?:
        if (isWindows) "C:/Users/${System.getProperty("user.name")}/AppData/Local/Android/Sdk"
        else if (isMac) "${System.getProperty("user.home")}/Library/Android/sdk"
        else "${System.getProperty("user.home")}/Android/Sdk"

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
        else "$androidHome/ndk/26.1.10909125"

    environment("ANDROID_NDK", androidNdk)
    environment("ANDROID_HOME", androidHome)
}

val buildThirdPartyDeps = tasks.register<Exec>("buildThirdPartyDeps") {
    group = "build"
    description = "Build third-party native dependencies used by the Go JNI library"

    configureNativeBuildEnvironment()

    if (isWindowsHost) {
        commandLine("powershell", "-ExecutionPolicy", "Bypass", "-File", "./build.ps1", "-DepsOnly")
    } else {
        commandLine("bash", "./build.sh", "--deps-only")
    }

    inputs.file(goSourceDir.file("build.sh"))
    inputs.file(goSourceDir.file("build.ps1"))
    inputs.file(goSourceDir.file("third_party/CMakeLists.txt"))
    outputs.files(thirdPartyLibraries())
    outputs.dirs(nativeAbis.map { abi -> thirdPartyOutputDir.dir("$abi/include") })
}

// Task to build the Go library with native JNI
tasks.register<Exec>("buildGoLibrary") {
    group = "build"
    description = "Build the Go kinetic library with native JNI"
    dependsOn(buildThirdPartyDeps)

    configureNativeBuildEnvironment()

    // Detect OS and use appropriate command
    if (isWindowsHost) {
        commandLine("powershell", "-ExecutionPolicy", "Bypass", "-File", "./build.ps1", "-SkipDeps")
    } else {
        commandLine("bash", "./build.sh", "--skip-deps")
    }

    inputs.files(fileTree(goSourceDir) {
        include("**/*.go")
        include("**/*.c")
        include("**/*.h")
        include("go.mod")
        include("go.sum")
        exclude("include/**")
        exclude("third_party/**")
    })
    inputs.files(thirdPartyLibraries())
    inputs.file(goSourceDir.file("build.sh"))
    inputs.file(goSourceDir.file("build.ps1"))
    outputs.files(jniLibraries())
}

// Make preBuild depend on buildGoLibrary
tasks.named("preBuild") {
    dependsOn("buildGoLibrary")
}

android {
    namespace = "com.kevmo314.kineticstreamer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kevmo314.kineticstreamer"
        minSdk = 31
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Known issues are recorded in the baseline so CI fails only on new lint errors.
    lint {
        baseline = file("lint-baseline.xml")
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.extensions)

    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
