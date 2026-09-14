plugins { id("com.android.application") }

android {
    namespace = "com.virtualdap.fixture.music"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    dynamicFeatures += setOf(":musicFeature")
    defaultConfig {
        applicationId = "com.virtualdap.fixture.music"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
