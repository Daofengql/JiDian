plugins {
    id("com.android.application")
}

android {
    namespace = "com.root.fakeloc"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.root.fakeloc"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
    compileOnly("de.robv.android.xposed:api:82")
}

// Committed assets keep Android-only builds independent of Node. When dependencies are
// installed, rebuild automatically so Android Studio never packages stale web sources.
val buildWebUi by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("node", rootProject.file("scripts/build-web.mjs").absolutePath)
    inputs.files(rootProject.fileTree("web"), rootProject.file("scripts/build-web.mjs"), rootProject.file("package-lock.json"))
    outputs.dir(layout.projectDirectory.dir("src/main/assets/ui"))
    onlyIf { rootProject.file("node_modules/esbuild").exists() }
}
tasks.named("preBuild") { dependsOn(buildWebUi) }
