plugins { id("com.android.dynamic-feature") }

android {
    namespace = "com.virtualdap.fixture.feature"
    compileSdk = 37
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies { implementation(project(":musicFixture")) }
