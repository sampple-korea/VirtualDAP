plugins { id("com.android.application") }

android {
    namespace = "com.virtualdap.fixture.music"
    compileSdk = 37
    dynamicFeatures += setOf(":musicFeature")
    defaultConfig {
        applicationId = "com.virtualdap.fixture.music"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
