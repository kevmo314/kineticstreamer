plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
}

// ABIs to build native code for: the device's preferred ABI as injected by Android Studio
// (its first, comma separated), else -PnativeAbis=x86_64, else all (what CI and release want).
val nativeAbis = providers.gradleProperty("android.injected.build.abi").map { it.substringBefore(",") }
    .orElse(providers.gradleProperty("nativeAbis"))
    .orElse("arm64-v8a,armeabi-v7a,x86,x86_64")
    .get().split(",")
val goSourceDir = layout.projectDirectory.dir("src/main/go")
val thirdPartyOutputDir = goSourceDir.dir("third_party/output")
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")

// build.sh runs everywhere (Git Bash on Windows). `cmd /c` makes Windows pick bash from PATH
// rather than the WSL launcher in System32.
fun Exec.runBuildScript(step: String) {
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    workingDir = goSourceDir.asFile
    environment("NATIVE_ABIS", nativeAbis.joinToString(" "))
    commandLine((if (isWindows) listOf("cmd", "/c") else emptyList()) + listOf("bash", "build.sh", step))
}

val buildThirdPartyDeps = tasks.register<Exec>("buildThirdPartyDeps") {
    group = "build"
    description = "Build third-party native dependencies used by the Go JNI library"
    runBuildScript("deps")

    inputs.file(goSourceDir.file("third_party/CMakeLists.txt"))
    outputs.dirs(nativeAbis.map { thirdPartyOutputDir.dir(it) })
}

tasks.register<Exec>("buildGoLibrary") {
    group = "build"
    description = "Build the Go kinetic library with native JNI"
    dependsOn(buildThirdPartyDeps)
    runBuildScript("jni")

    inputs.files(fileTree(goSourceDir) {
        include("**/*.go", "**/*.c", "**/*.h", "go.mod", "go.sum")
        exclude("third_party/**")
    })
    inputs.dir(thirdPartyOutputDir)
    inputs.file(goSourceDir.file("build.sh"))
    outputs.dirs(nativeAbis.map { jniLibsDir.dir(it) })
}

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
