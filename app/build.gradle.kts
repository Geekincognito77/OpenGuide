plugins {
    id("com.android.application")
}

// The release tag on GitHub must match this, with a leading "v", for the in-app update check.
val appVersion = providers.gradleProperty("appVersion").getOrElse("1.1.0")
val updateApi = providers.gradleProperty("updateApi")
    .getOrElse("https://api.github.com/repos/Zapiltar-E7/OpenGuide/releases/latest")

android {
    namespace = "com.openguide.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openguide.tv"
        // Fire OS 5 is API 22; Fire OS 8 is API 30.
        minSdk = 22
        targetSdk = 34
        versionCode = appVersion.split('.').fold(0) { acc, part -> acc * 100 + part.toInt() }
        versionName = appVersion
        buildConfigField("String", "UPDATE_API", "\"$updateApi\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideload build: signed with the local debug key so it installs without a keystore.
            signingConfig = signingConfigs.getByName("debug")
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
    val media3 = "1.8.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-base:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
}
