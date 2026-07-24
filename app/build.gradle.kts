plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "br.com.redesurftank.havaldock"
    compileSdk = 36

    defaultConfig {
        applicationId = "br.com.redesurftank.havaldock"
        minSdk = 28
        targetSdk = 28
        versionCode = 26
        versionName = "0.2.24"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // Assina apenas quando o keystore existe (CI). Localmente o release fica sem assinatura.
            if (file("release.keystore").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Bootstrap: sem minify/shrink p/ garantir build verde. Ligar quando o app crescer.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    lint {
        // App é sideload na central (não Google Play) e PRECISA targetar a API 28 do veículo.
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.material.icons.extended)
    debugImplementation(libs.ui.tooling)

    implementation(libs.shizuku)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)
}
