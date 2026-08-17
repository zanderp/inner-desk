import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.zanderp.innerdesk"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.zanderp.innerdesk"
        minSdk = 31
        targetSdk = 35
        versionCode = 50
        versionName = "0.8.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
        val telemetryUrl = (project.findProperty("telemetryUrl") as String?)
            ?: System.getenv("TELEMETRY_URL")
            ?: "https://innerdesk-telemetry.hello-3d9.workers.dev"
        buildConfigField("String", "TELEMETRY_URL", "\"$telemetryUrl\"")
        // F-Droid builds pass -Pfdroid so telemetry is opt-in (no Tracking anti-feature).
        val telemetryDefault = if (project.hasProperty("fdroid")) "false" else "true"
        buildConfigField("boolean", "TELEMETRY_DEFAULT", telemetryDefault)
    }

    signingConfigs {
        getByName("debug") {
            // Uses default debug keystore
        }
        val keystorePropsFile = rootProject.file("keystore.properties")
        if (keystorePropsFile.exists()) {
            val p = Properties()
            keystorePropsFile.inputStream().use { p.load(it) }
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // F-Droid signs with its own key. GitHub/IzzyOnDroid use the release keystore.
            signingConfig = when {
                project.hasProperty("fdroid") -> null
                signingConfigs.findByName("release") != null -> signingConfigs.getByName("release")
                else -> signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        jniLibs {
            excludes += listOf("lib/x86/**", "lib/x86_64/**", "lib/armeabi-v7a/**")
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }
    lint {
        disable += "BlockedPrivateApi"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.window:window:1.3.0")

    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")

    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
}
