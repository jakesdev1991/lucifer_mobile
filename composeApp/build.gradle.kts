plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lucifer.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lucifer.mobile"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        multiDexEnabled = true
        multiDexKeepProguard = file("multidex-keep.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
        )
    }

    buildFeatures {
        compose = true
        viewBinding = false
        dataBinding = false
        aidl = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE,LICENSE.txt,license.txt,NOTICE,NOTICE.txt}"
        }
    }

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].java.srcDirs("src/androidMain/kotlin")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].assets.srcDirs("src/androidMain/assets")
    sourceSets["main"].aidl.srcDirs("src/androidMain/aidl")

    namespace = "com.lucifer.mobile"
}

dependencies {
    // Compose BOM 2024.10.01 provides Compose 1.7.x with Kotlin 1.9.24 / Compose Compiler 1.5.14
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))

    // Compose - versions managed by BOM
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.ui:ui-unit")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation-layout")

    // Activity & Navigation
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Ktor
    implementation("io.ktor:ktor-client-android:2.3.13")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.13")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.13")

    // Crypto
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("com.auth0.android:jwtdecode:2.1.0")

    // JSch for VM SSH
    implementation("com.github.mwiede:jsch:0.2.20")

    // Shizuku: user-authorized shell/system API bridge (ADB wireless debugging or root).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Dhizuku: a device-owner identity borrowed from an app the owner provisioned (Maven Central).
    // Lucifer reaches it by reflection so a missing artifact degrades to "Dhizuku not installed"
    // instead of a build failure, but the class has to be in the APK for that reflection to find it.
    implementation("io.github.iamr0s:Dhizuku-API:2.5.4")

    // Hidden-API exemption, needed by DevicePolicyManager calls made as a delegated owner.
    // Used reflectively; the build does not depend on it being loadable at runtime.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    // AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // MultiDex
    implementation("androidx.multidex:multidex:2.0.1")
}