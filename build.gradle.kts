plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
}

android {
    namespace = "com.jzolee.vibrationmonitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jzolee.vibrationmonitor"
        minSdkVersion(rootProject.extra["defaultMinSdkVersion"] as Int)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")

    // Chart library
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0") {
        exclude(group = "com.android.support")  // Elkerüljük a konfliktusokat
    }

    // Fájlkezelés
    implementation("commons-io:commons-io:2.11.0")  // Egyszerű fájl műveletekhez
    implementation("androidx.documentfile:documentfile:1.0.1")  // Scoped Storage támogatás
    
    // BLE
    implementation("no.nordicsemi.android:ble-ktx:2.6.1")
    
    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")
    
    // Tesztelés
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}