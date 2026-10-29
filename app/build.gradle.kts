plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-parcelize")
}

// ABIs to build native code for: Android Studio's injected ABI, else -PnativeAbis=x86_64,
// else all (what CI and release builds want).
val nativeAbis = providers.gradleProperty("android.injected.build.abi")
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
    compileSdk = 36

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

    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-video:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.camera:camera-extensions:1.4.1")

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
