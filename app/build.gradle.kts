import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val configuredVersionName = providers.gradleProperty("appVersionName").orElse("1.2.0").get()
val configuredVersionCode = providers.gradleProperty("appVersionCode").orElse("10200").get().toInt()

val launcherIconSource = file("src/main/res/mipmap-nodpi/bilidl_icon_source.png")
val generatedLauncherRes = layout.buildDirectory.dir("generated/res/launcherIcon")
val generateLauncherIcon = tasks.register("generateLauncherIcon") {
    inputs.file(launcherIconSource)
    outputs.file(generatedLauncherRes.map { it.file("mipmap-nodpi/bilidl_icon.png") })
    doLast {
        val sourceImage = javax.imageio.ImageIO.read(launcherIconSource)
            ?: error("Unable to decode the supplied BiliDl launcher PNG")
        val width = sourceImage.width
        val height = sourceImage.height
        val exteriorMatte = BooleanArray(width * height)
        val queue = java.util.ArrayDeque<Int>()

        fun isWhiteMatte(index: Int): Boolean {
            val argb = sourceImage.getRGB(index % width, index / width)
            val alpha = (argb ushr 24) and 0xff
            val red = (argb ushr 16) and 0xff
            val green = (argb ushr 8) and 0xff
            val blue = argb and 0xff
            return alpha > 0 && red >= 232 && green >= 232 && blue >= 232 &&
                (maxOf(red, green, blue) - minOf(red, green, blue)) <= 20
        }

        fun visitEdge(index: Int) {
            if (!exteriorMatte[index] && isWhiteMatte(index)) {
                exteriorMatte[index] = true
                queue.addLast(index)
            }
        }

        for (x in 0 until width) {
            visitEdge(x)
            visitEdge((height - 1) * width + x)
        }
        for (y in 0 until height) {
            visitEdge(y * width)
            visitEdge(y * width + width - 1)
        }
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val x = index % width
            val y = index / width
            if (x > 0) visitEdge(index - 1)
            if (x + 1 < width) visitEdge(index + 1)
            if (y > 0) visitEdge(index - width)
            if (y + 1 < height) visitEdge(index + width)
        }

        val output = java.awt.image.BufferedImage(
            width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB
        )
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val argb = sourceImage.getRGB(x, y)
                output.setRGB(x, y, if (exteriorMatte[index]) 0 else (argb or (0xff shl 24)))
            }
        }
        val outputFile = generatedLauncherRes.get().file("mipmap-nodpi/bilidl_icon.png").asFile
        outputFile.parentFile.mkdirs()
        check(javax.imageio.ImageIO.write(output, "png", outputFile)) {
            "Unable to encode the transparent BiliDl launcher PNG"
        }
    }
}

android {
    namespace = "com.example.bilidl"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    sourceSets.getByName("main").res.srcDir(generatedLauncherRes)

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


tasks.named("preBuild").configure {
    dependsOn(generateLauncherIcon)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}


// Robolectric fetches instrumented Android framework jars at test runtime.
// Use Maven Central's canonical hostname rather than the legacy repo1 alias.
tasks.withType<Test>().configureEach {
    systemProperty(
        "robolectric.dependency.repo.url",
        "https://repo.maven.apache.org/maven2"
    )
}
