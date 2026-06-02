plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pluxy.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pluxy.tv"
        minSdk = 23            // Android TV 6.0+ ET mobile (Android 6+)
        targetSdk = 34
        versionCode = 3
        versionName = "1.0.2"
    }

    // Clé de signature STABLE et versionnée : permet de mettre à jour l'app
    // par-dessus l'existante (même signature) sans avoir à la désinstaller.
    signingConfigs {
        create("pluxy") {
            storeFile = rootProject.file("keystore/pluxy.jks")
            storePassword = "pluxy-pluxy"
            keyAlias = "pluxy"
            keyPassword = "pluxy-pluxy"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("pluxy")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("pluxy")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    // Media3 1.4.1 : compile contre compileSdk 34 (aucun SDK 35 à télécharger).
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-common:$media3")

    // UI Android TV (Leanback)
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Réseau / JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    // Chargement d'images (affiches/backdrops TMDB)
    implementation("io.coil-kt:coil:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
