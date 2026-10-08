plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Les identifiants de la cle d'envoi Play ne doivent jamais vivre dans Git.
// Sans ces quatre variables, Gradle produit volontairement un bundle release
// non signe, suffisant pour la CI mais impossible a publier par erreur.
val uploadStoreFile = providers.environmentVariable("PLUXY_PLAYER_UPLOAD_STORE_FILE").orNull
val uploadStorePassword = providers.environmentVariable("PLUXY_PLAYER_UPLOAD_STORE_PASSWORD").orNull
val uploadKeyAlias = providers.environmentVariable("PLUXY_PLAYER_UPLOAD_KEY_ALIAS").orNull
val uploadKeyPassword = providers.environmentVariable("PLUXY_PLAYER_UPLOAD_KEY_PASSWORD").orNull
val hasUploadSigning = listOf(
    uploadStoreFile,
    uploadStorePassword,
    uploadKeyAlias,
    uploadKeyPassword,
).all { !it.isNullOrBlank() }
require(listOf(uploadStoreFile, uploadStorePassword, uploadKeyAlias, uploadKeyPassword).all { it.isNullOrBlank() } || hasUploadSigning) {
    "Signature PLUXY_PLAYER_UPLOAD incomplete : configurez les quatre variables ou retirez-les toutes."
}
val debugStoreFile = providers.environmentVariable("PLUXY_PLAYER_DEBUG_STORE_FILE").orNull
val debugStorePassword = providers.environmentVariable("PLUXY_PLAYER_DEBUG_STORE_PASSWORD").orNull
val debugKeyAlias = providers.environmentVariable("PLUXY_PLAYER_DEBUG_KEY_ALIAS").orNull
val debugKeyPassword = providers.environmentVariable("PLUXY_PLAYER_DEBUG_KEY_PASSWORD").orNull
val hasDebugSigning = listOf(
    debugStoreFile, debugStorePassword, debugKeyAlias, debugKeyPassword,
).all { !it.isNullOrBlank() }
require(listOf(debugStoreFile, debugStorePassword, debugKeyAlias, debugKeyPassword).all { it.isNullOrBlank() } || hasDebugSigning) {
    "Signature PLUXY_PLAYER_DEBUG incomplete : configurez les quatre variables ou retirez-les toutes."
}

android {
    namespace = "com.pluxy.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pluxy.tv"
        minSdk = 23            // Android TV 6.0+ ET mobile (Android 6+)
        targetSdk = 36
        versionCode = 61
        versionName = "1.7.7"
    }

    signingConfigs {
        // A different SDK/ANDROID_USER_HOME must not silently change local APK identity.
        getByName("debug") {
            storeFile = file("${providers.systemProperty("user.home").get()}/.android/debug.keystore")
        }
        if (hasUploadSigning) {
            create("playUpload") {
                storeFile = file(uploadStoreFile!!)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
        if (hasDebugSigning) {
            create("continuousDebug") {
                storeFile = file(debugStoreFile!!)
                storePassword = debugStorePassword
                keyAlias = debugKeyAlias
                keyPassword = debugKeyPassword
            }
        }
    }

    buildTypes {
        // Local builds keep Android's normal debug key. CI injects a protected,
        // stable test key so a downloaded debug APK can update the previous one.
        getByName("debug") {
            if (hasDebugSigning) signingConfig = signingConfigs.getByName("continuousDebug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasUploadSigning) signingConfig = signingConfigs.getByName("playUpload")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        buildConfig = true
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(project(":serverapp"))
    // Version stable courante : correctifs ExoPlayer/HLS récents sans canal preview.
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-common:$media3")

    // UI Android TV (Leanback)
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Reseau / JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    // Chargement d'images (affiches/backdrops TMDB)
    implementation("io.coil-kt:coil:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}









