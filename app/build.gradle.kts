import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "com.anatej.c2s"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anatej.c2s"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Release APKs stay phone-only. Local emulator verification can opt in
        // with -PincludeEmulatorAbi=true without changing the published size.
        ndk {
            val abis = mutableListOf("armeabi-v7a", "arm64-v8a")
            if (providers.gradleProperty("includeEmulatorAbi").orNull == "true") {
                abis += "x86_64"
            }
            abiFilters += abis
        }
    }

    // The base APK stays small (Latin OCR only). The "full" variant bundles
    // the Chinese, Japanese, Korean and Devanagari models for people who need
    // them. ML Kit's bundled models cannot be downloaded at runtime.
    flavorDimensions += "languages"
    productFlavors {
        create("latin") {
            dimension = "languages"
            buildConfigField("boolean", "FULL_LANGUAGES", "false")
        }
        create("full") {
            dimension = "languages"
            buildConfigField("boolean", "FULL_LANGUAGES", "true")
        }
    }

    // Signing credentials live in keystore/keystore.properties (not in git).
    // Without that file the release build is simply unsigned.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // On-device neural text recognition, model bundled in the APK — no Play Services needed
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Extra scripts only in the "full" variant. The code that uses these
    // classes lives in src/full/java so the Latin variant stays compilable.
    add("fullImplementation", "com.google.mlkit:text-recognition-chinese:16.0.1")
    add("fullImplementation", "com.google.mlkit:text-recognition-devanagari:16.0.1")
    add("fullImplementation", "com.google.mlkit:text-recognition-japanese:16.0.1")
    add("fullImplementation", "com.google.mlkit:text-recognition-korean:16.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18")
    testImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
