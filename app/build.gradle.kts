import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Ключ підпису релізу: release.keystore у корені проєкту, пароль — `release.storePassword` у
// local.properties (обидва не в git). Втратите ключ — наступну версію доведеться ставити з нуля,
// з видаленням застосунку й сканів: тримайте копію.
val localProperties = Properties().apply { rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) } }
val releaseStore = rootProject.file("release.keystore").takeIf { it.isFile }

android {
    namespace = "uk.mwms1488.pdfshoot"
    compileSdk = 37

    defaultConfig {
        applicationId = "uk.mwms1488.pdfshoot"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // OpenCV — нативна бібліотека: лише архітектури телефонів (і емулятора на Mac), без x86.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = localProperties.getProperty("release.storePassword")
                keyAlias = "pdfshoot"
                keyPassword = localProperties.getProperty("release.storePassword")
            }
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            // R8 викидає невживаний код бібліотек: APK менший, запуск швидший.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // Нативні бібліотеки в APK стиснуті: файл, що качається для оновлення, удвічі менший.
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    // Власна камера: повна роздільність, спалах і ліхтар, рамка документа наживо.
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    // Пошук країв, вирівнювання перспективи, покращення сторінки — нативно, на повній роздільності.
    implementation("org.opencv:opencv:4.12.0")
    // Поворот фото з галереї.
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
