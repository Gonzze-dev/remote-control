import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// La versión se sube acá antes de publicar; la etiqueta vX.Y.Z tiene que coincidir (el workflow lo revisa).
// `-PappVersion=1.0.9` la cambia para una compilación de prueba.
val appVersion = (findProperty("appVersion") as String?) ?: "1.2.0"

/** 1.2.3 → 10203. Updater.versionCode hace la misma cuenta con la etiqueta de la release. */
fun versionCodeOf(version: String): Int {
    val (major, minor, patch) = version.split('.').map { it.toInt() }
    require(minor <= 99 && patch <= 99) { "Versión inválida: $version" }
    return major * 10_000 + minor * 100 + patch
}

// La clave de firma no está en el repo: en esta PC la nombra keystore.properties (git lo ignora) y en GitHub
// Actions llega por variables de entorno desde los secretos del repo. Sin ninguna, se usa la de depuración.
val keystore = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signing(key: String, env: String): String? = keystore.getProperty(key) ?: System.getenv(env)?.takeIf { it.isNotBlank() }
val releaseStoreFile = signing("storeFile", "KEYSTORE_FILE")

android {
    namespace = "com.gonzalo.controltv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gonzalo.controltv"
        minSdk = 26
        targetSdk = 37
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signing("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signing("keyAlias", "KEY_ALIAS") ?: "control-tv"
                keyPassword = signing("keyPassword", "KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // Con la clave a mano, las compilaciones de prueba también se firman con ella: así se instalan encima de
        // la versión publicada sin desinstalar (y sin perder la vinculación).
        debug {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // BouncyCastle trae el mismo archivo en varios jars.
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE.md",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // Las pruebas de capturas (Roborazzi) guardan los PNG en app/build/outputs/roborazzi.
                test.systemProperty("roborazzi.test.record", "true")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.bouncycastle.bcpkix)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
