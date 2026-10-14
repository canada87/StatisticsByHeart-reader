plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// OneDrive (or any sync client) locks files in build/ while uploading and breaks the Android build with
// "Unable to delete directory". If this repo lives in a synced folder, set `sbh.buildRoot` in
// ~/.gradle/gradle.properties (or pass -Psbh.buildRoot=...) to keep build outputs elsewhere.
providers.gradleProperty("sbh.buildRoot").orNull?.let { buildRoot ->
    allprojects {
        val relative = path.removePrefix(":").replace(':', '/')
        layout.buildDirectory.set(file("$buildRoot/${rootProject.name}/$relative"))
    }
}
