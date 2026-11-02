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
        maven { url = uri("https://api.xposed.info/"); content { includeGroup("de.robv.android.xposed") } }
    }
}

rootProject.name = "JiDian"
include(":app")
// Optional independent process fixture; never included in the shipped app.
if (providers.gradleProperty("hookProbe").orNull == "true") {
    include(":hook-probe")
    project(":hook-probe").projectDir = file("tools/hook-probe")
}
