plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val configuredVersionName = providers.gradleProperty("appVersionName").orElse("1.2.0").get()
val configuredVersionCode = providers.gradleProperty("appVersionCode").orElse("10200").get().toInt()

android {
    namespace = "com.example.bilidl"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.example.bilidl"
        minSdk = 29
        targetSdk = 35
        versionCode = configuredVersionCode
        versionName = configuredVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-D_FILE_OFFSET_BITS=64")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric downloads Android framework jars at test runtime.
            // Use Maven Central's canonical host rather than the legacy repo1 alias.
            systemProperty(
                "robolectric.dependency.repo.url",
                "https://repo.maven.apache.org/maven2"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":bilivdl-ui"))
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("com.google.zxing:core:3.5.3")

    // Native in-app playback for Bilibili DASH audio/video streams.
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("org.robolectric:robolectric:4.14.1")
}


ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}
