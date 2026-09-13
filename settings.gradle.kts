pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.tiann") }
        }
    }
}

rootProject.name = "VirtualDAP"
include(":app")
include(":containerCore", ":containerReflection", ":containerCompiler")
project(":containerCore").projectDir = file("container_runtime/core")
project(":containerReflection").projectDir = file("container_runtime/reflection")
project(":containerCompiler").projectDir = file("container_runtime/compiler")
include(":musicFixture")
project(":musicFixture").projectDir = file("test_apps/music_fixture")
include(":musicFeature")
project(":musicFeature").projectDir = file("test_apps/music_feature")
