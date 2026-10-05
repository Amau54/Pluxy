plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val uploadStoreFile = providers.environmentVariable("PLUXY_SERVER_UPLOAD_STORE_FILE").orNull
val uploadStorePassword = providers.environmentVariable("PLUXY_SERVER_UPLOAD_STORE_PASSWORD").orNull
val uploadKeyAlias = providers.environmentVariable("PLUXY_SERVER_UPLOAD_KEY_ALIAS").orNull
val uploadKeyPassword = providers.environmentVariable("PLUXY_SERVER_UPLOAD_KEY_PASSWORD").orNull
val hasUploadSigning = listOf(
    uploadStoreFile,
    uploadStorePassword,
    uploadKeyAlias,
    uploadKeyPassword,
).all { !it.isNullOrBlank() }
require(listOf(uploadStoreFile, uploadStorePassword, uploadKeyAlias, uploadKeyPassword).all { it.isNullOrBlank() } || hasUploadSigning) {
    "Signature PLUXY_SERVER_UPLOAD incomplete : configurez les quatre variables ou retirez-les toutes."
}
val debugStoreFile = providers.environmentVariable("PLUXY_SERVER_DEBUG_STORE_FILE").orNull
val debugStorePassword = providers.environmentVariable("PLUXY_SERVER_DEBUG_STORE_PASSWORD").orNull
val debugKeyAlias = providers.environmentVariable("PLUXY_SERVER_DEBUG_KEY_ALIAS").orNull
val debugKeyPassword = providers.environmentVariable("PLUXY_SERVER_DEBUG_KEY_PASSWORD").orNull
val hasDebugSigning = listOf(
    debugStoreFile, debugStorePassword, debugKeyAlias, debugKeyPassword,
).all { !it.isNullOrBlank() }
require(listOf(debugStoreFile, debugStorePassword, debugKeyAlias, debugKeyPassword).all { it.isNullOrBlank() } || hasDebugSigning) {
    "Signature PLUXY_SERVER_DEBUG incomplete : configurez les quatre variables ou retirez-les toutes."
}

android {
    namespace = "com.pluxy.server"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pluxy.server"
        minSdk = 23
        targetSdk = 36
        versionCode = 49
        versionName = "1.5.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
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
        getByName("debug") {
            if (hasDebugSigning) signingConfig = signingConfigs.getByName("continuousDebug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasUploadSigning) signingConfig = signingConfigs.getByName("playUpload")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(project(":receiver-core"))
    implementation(project(":receiver-dlna"))
    implementation(project(":receiver-cast"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
