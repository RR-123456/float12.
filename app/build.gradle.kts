plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pragon.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pragon.mobile"
        minSdk = 31
        targetSdk = 34
        versionCode = 60
        versionName = "6.0"
        ndk { abiFilters += "arm64-v8a" }   // v12: phones only (one native set = smallest APK)
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")   // on-device LLMs
    // v12: speech comes from sherpa-onnx. Its native libraries (app/src/main/jniLibs) and Kotlin API (app/src/main/java/com/k2fsa)
    // are fetched by tools/fetch_sherpa.sh, so they are not in this folder. Vosk is gone.
    implementation("org.apache.commons:commons-compress:1.27.1")   // unpacks the .tar.bz2 speech model
}
