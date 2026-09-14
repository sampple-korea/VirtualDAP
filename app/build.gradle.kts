import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val prepareUsbLicense = tasks.register<Copy>("prepareUsbLicense") {
    from(rootProject.file("third_party/libusb/COPYING"))
    into(layout.buildDirectory.dir("generated/usbLicenseAssets/notices"))
    rename { "libusb-LICENSE.txt" }
}

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
    }
}

android {
    namespace = "com.virtualdap.host"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.virtualdap.host"
        minSdk = 33
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
        aidl = true
        compose = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    // Exercise the exact pure-JVM disk lifecycle used by the privileged AOSP runtime in CI.
    sourceSets.getByName("test").kotlin.directories.add(rootProject.file("platform_runtime/core").path)
    sourceSets.getByName("androidTest").kotlin.directories.add(rootProject.file("platform_runtime/input").path)
    sourceSets.getByName("androidTest").assets.directories.add(
        layout.buildDirectory.dir("generated/musicFixtureAssets").get().asFile.path,
    )
    sourceSets.getByName("main").assets.directories.add(rootProject.file("third_party/notices").path)
    sourceSets.getByName("main").assets.directories.add(layout.buildDirectory.dir("generated/usbLicenseAssets").get().asFile.path)
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
    if (name == "preBuild" || name.contains("Lint") || name.matches(Regex("merge.*Assets"))) {
        dependsOn(prepareUsbLicense)
    }
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
