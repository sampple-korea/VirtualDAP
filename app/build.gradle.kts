import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

private fun testProtoBytes(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
    parts.forEach(output::writeBytes)
    output.toByteArray()
}

private fun testProtoVarint(input: Long): ByteArray {
    var value = input
    return ByteArrayOutputStream().use { output ->
        while (value and -0x80L != 0L) {
            output.write(((value and 0x7f) or 0x80).toInt())
            value = value ushr 7
        }
        output.write(value.toInt())
        output.toByteArray()
    }
}

private fun testProtoMessage(number: Int, value: ByteArray): ByteArray = testProtoBytes(
    testProtoVarint((number.toLong() shl 3) or 2),
    testProtoVarint(value.size.toLong()),
    value,
)

private fun testProtoString(number: Int, value: String): ByteArray =
    testProtoMessage(number, value.toByteArray(Charsets.UTF_8))

private fun testProtoNumber(number: Int, value: Long): ByteArray = testProtoBytes(
    testProtoVarint(number.toLong() shl 3),
    testProtoVarint(value),
)

private fun testBundletoolModule(name: String, delivery: Int, path: String): ByteArray {
    val metadata = testProtoBytes(testProtoString(1, name), testProtoNumber(6, delivery.toLong()))
    val description = testProtoBytes(testProtoString(2, path), testProtoMessage(3, byteArrayOf()))
    return testProtoBytes(testProtoMessage(1, metadata), testProtoMessage(2, description))
}

private fun testBundletoolVariant(abi: Int, alternative: Int, prefix: String): ByteArray {
    val abiTargeting = testProtoBytes(
        testProtoMessage(1, testProtoNumber(1, abi.toLong())),
        testProtoMessage(2, testProtoNumber(1, alternative.toLong())),
    )
    val targeting = testProtoMessage(2, abiTargeting)
    return testProtoBytes(
        testProtoMessage(1, targeting),
        testProtoMessage(2, testBundletoolModule("base", 1, "$prefix/base.apk")),
        // Preserve a separately delivered decoder feature so class loading proves cluster parsing.
        testProtoMessage(2, testBundletoolModule("decoder", 2, "$prefix/feature.apk")),
    )
}

private fun testBundletoolToc(): ByteArray = testProtoBytes(
    testProtoMessage(1, testBundletoolVariant(3, 5, "variants/arm64")),
    testProtoMessage(1, testBundletoolVariant(5, 3, "variants/x86_64")),
    testProtoString(4, "com.virtualdap.fixture.music"),
)

val prepareMusicFixture = tasks.register("prepareMusicFixture") {
    dependsOn(":musicFixture:assembleDebug", ":musicFeature:assembleDebug")
    val baseApk = project(":musicFixture").layout.buildDirectory.file("outputs/apk/debug/musicFixture-debug.apk")
    val featureApk = project(":musicFeature").layout.buildDirectory.file("outputs/apk/debug/musicFeature-debug.apk")
    val output = layout.buildDirectory.dir("generated/musicFixtureAssets")
    inputs.files(baseApk, featureApk)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        baseApk.get().asFile.copyTo(directory.resolve("music-fixture.apk"), overwrite = true)
        ZipOutputStream(directory.resolve("music-fixture.apks").outputStream()).use { zip ->
            for ((name, file) in listOf("arbitrary-base-name.apk" to baseApk, "decoder-feature.apk" to featureApk)) {
                zip.putNextEntry(ZipEntry(name))
                file.get().asFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        ZipOutputStream(directory.resolve("music-fixture-bundletool.apks").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("toc.pb"))
            zip.write(testBundletoolToc())
            zip.closeEntry()
            for ((prefix, base, feature) in listOf(
                Triple("variants/arm64", baseApk, featureApk),
                Triple("variants/x86_64", baseApk, featureApk),
            )) {
                for ((name, file) in listOf("$prefix/base.apk" to base, "$prefix/feature.apk" to feature)) {
                    zip.putNextEntry(ZipEntry(name))
                    file.get().asFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }
}

android {
    namespace = "com.virtualdap.host"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.virtualdap.host"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    // Retained direct-USB sources are compiled only by JVM regression tests, never into the APK.
    sourceSets.getByName("test").kotlin.directories.add(rootProject.file("compatibility/direct_usb/kotlin").path)
    sourceSets.getByName("androidTest").assets.directories.add(
        layout.buildDirectory.dir("generated/musicFixtureAssets").get().asFile.path,
    )
    sourceSets.getByName("main").assets.directories.add(rootProject.file("third_party/notices").path)
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        // API 37 is used to compile current AndroidX; API 36 is the current Play target policy.
        disable += "OldTargetApi"
    }
}

tasks.configureEach {
    if (name == "mergeDebugAndroidTestAssets" ||
        name == "generateDebugAndroidTestLintModel" ||
        name == "lintAnalyzeDebugAndroidTest"
    ) dependsOn(prepareMusicFixture)
}

dependencies {
    implementation(project(":containerCore"))
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
