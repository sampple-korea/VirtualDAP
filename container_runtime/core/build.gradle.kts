plugins { id("com.android.library") }

val upstream = rootProject.file("third_party/blackbox/Bcore/src/main")
val prepared = layout.buildDirectory.dir("generated/upstream")
val prepareSources = tasks.register<Exec>("prepareContainerSources") {
    inputs.dir(upstream)
    inputs.dir(rootProject.file("third_party/dobby"))
    inputs.dir(file("overrides"))
    inputs.file(rootProject.file("scripts/prepare_container_sources.py"))
    outputs.dir(prepared)
    outputs.dir(layout.buildDirectory.dir("generated/dobby"))
    commandLine(
        System.getenv("VIRTUALDAP_PYTHON")
            ?: if (file("/usr/bin/python3.12").isFile) "/usr/bin/python3.12" else "python3",
        rootProject.file("scripts/prepare_container_sources.py"),
        "--upstream", upstream,
        "--dobby", rootProject.file("third_party/dobby"),
        "--overrides", file("overrides"),
        "--output", prepared.get().asFile,
    )
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "top.niunaijun.blackbox"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 34
        externalNativeBuild {
            cmake { targets += "blackbox" }
        }
    }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").apply {
        manifest.srcFile(prepared.map { it.file("AndroidManifest.xml") }.get().asFile)
        java.directories.add(prepared.get().dir("java").asFile.path)
        aidl.directories.add(prepared.get().dir("aidl").asFile.path)
        res.directories.add(prepared.get().dir("res").asFile.path)
        assets.directories.add(prepared.get().dir("assets").asFile.path)
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

tasks.named("preBuild").configure { dependsOn(prepareSources) }

dependencies {
    implementation(project(":containerReflection"))
    annotationProcessor(project(":containerCompiler"))
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.moandjiezana.toml:toml4j:0.7.2")
    implementation("com.github.tiann:FreeReflection:3.2.2")
}
