import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ---- Release signing -------------------------------------------------------------------------------------------
// The personal release key is NEVER in the repo. It comes from (first match wins):
//   1. environment variables (CI): SBH_KEYSTORE_FILE, SBH_KEYSTORE_PASSWORD, SBH_KEY_ALIAS, SBH_KEY_PASSWORD
//   2. keystore.properties in the repo root (git-ignored): storeFile, storePassword, keyAlias, keyPassword
// If neither is complete, any release build fails with an explicit message: there is no fallback to the debug key
// (an APK signed with another key could never update the installed one).
val signingFromEnv = listOf("SBH_KEYSTORE_FILE", "SBH_KEYSTORE_PASSWORD", "SBH_KEY_ALIAS", "SBH_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull }
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val signingFromFile = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").associateWith { keystoreProps.getProperty(it) }
val releaseSigning: Map<String, String>? = when {
    signingFromEnv.values.all { !it.isNullOrBlank() } -> mapOf(
        "storeFile" to signingFromEnv.getValue("SBH_KEYSTORE_FILE")!!,
        "storePassword" to signingFromEnv.getValue("SBH_KEYSTORE_PASSWORD")!!,
        "keyAlias" to signingFromEnv.getValue("SBH_KEY_ALIAS")!!,
        "keyPassword" to signingFromEnv.getValue("SBH_KEY_PASSWORD")!!,
    )
    signingFromFile.values.all { !it.isNullOrBlank() } -> signingFromFile.mapValues { it.value!! }
    else -> null
}

// ---- Version ---------------------------------------------------------------------------------------------------
// versionName comes from the git tag (CI exports SBH_VERSION_NAME=0.2.0 for tag v0.2.0); locally it defaults below.
// versionCode = MAJOR*10000 + MINOR*100 + PATCH: strictly increasing for every higher semver, which is exactly what
// Android needs to accept an update (Obtainium installs over the old app only if versionCode grows).
// Limits: MINOR and PATCH must be < 100; pre-release suffixes (-rc1) are not supported.
val sbhVersionName = providers.environmentVariable("SBH_VERSION_NAME").orElse("0.2.0").get().removePrefix("v")
val semver = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(sbhVersionName)
    ?: throw GradleException("SBH_VERSION_NAME='$sbhVersionName' is not MAJOR.MINOR.PATCH (e.g. 0.1.0)")
val (vMajor, vMinor, vPatch) = semver.destructured.toList().map { it.toInt() }
if (vMinor > 99 || vPatch > 99) throw GradleException("MINOR and PATCH must be < 100 to keep versionCode monotonic")
val sbhVersionCode = vMajor * 10_000 + vMinor * 100 + vPatch

android {
    namespace = "app.sbh.reader"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.sbh.reader"
        minSdk = 28
        targetSdk = 36
        versionCode = sbhVersionCode
        versionName = sbhVersionName
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Fail fast (and clearly) when a signed release is requested without the personal key.
gradle.taskGraph.whenReady {
    val needsSigning = allTasks.any { it.path == ":app:packageRelease" || it.path == ":app:bundleRelease" }
    if (needsSigning && releaseSigning == null) {
        throw GradleException(
            """
            |
            |Release signing key not configured: refusing to build an unsigned or debug-signed release APK.
            |Provide the personal key either as environment variables
            |  SBH_KEYSTORE_FILE, SBH_KEYSTORE_PASSWORD, SBH_KEY_ALIAS, SBH_KEY_PASSWORD
            |or in keystore.properties (git-ignored) in the repo root:
            |  storeFile=...  storePassword=...  keyAlias=...  keyPassword=...
            |See README.md, "Installare e aggiornare l'app" (step A and B).
            """.trimMargin(),
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.health.connect)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.workmanager)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
