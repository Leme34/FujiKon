plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fujikon.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fujikon.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 53
        versionName = "1.6.46"
        vectorDrawables { useSupportLibrary = true }
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // TFLite maps the model from the APK; compression would force a full
        // in-memory copy (and can break FileDescriptor-based loading).
        noCompress += "tflite"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    applicationVariants.all {
        val appVersionName = versionName
        outputs.all {
            // Name every APK by the app version, never by the build type.
            // Debug and release builds still live in separate directories.
            (this as com.android.build.gradle.internal.api.ApkVariantOutputImpl)
                .outputFileName = "FujiKon-$appVersionName.apk"
        }
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    // Robolectric downloads the Android runtime jar on first use. Aliyun mirrors
    // Maven Central; override with -ProbolectricRepo=... or -ProbolectricDir=... .
    systemProperty(
        "robolectric.dependency.repo.url",
        providers.gradleProperty("robolectricRepo").getOrElse("https://maven.aliyun.com/repository/public")
    )
    providers.gradleProperty("robolectricDir").orNull?.let { dir ->
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", dir)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("io.coil-kt:coil-compose:2.7.0")
    // Also a transitive dependency of coil; declared explicitly because the
    // Agnes client needs a reliable non-2xx response body on all devices.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // On-device AI semantic segmentation. Both models are plain TFLite graphs
    // with built-in ops only (no MediaPipe custom ops), so the stock runtime is
    // enough. Quantised ADE20K recognises sky / person / scene classes, while
    // the PASCAL-VOC DeepLab model supplies object-level subject boundaries.
    // assets are kept uncompressed so Interpreter can mmap them directly.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
