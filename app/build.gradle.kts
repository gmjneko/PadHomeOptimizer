@file:Suppress("UnstableApiUsage")

import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "me.gmjneko.padhomeopt"
    compileSdk = 35
    defaultConfig {
        applicationId = namespace
        minSdk = 35
        targetSdk = 35
        versionCode = 4020
        versionName = "1.0.0"
    }
    signingConfigs {
        register("release") {
            enableV3Signing = true
            enableV4Signing = true
        }
    }
    buildFeatures {
        buildConfig = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    java.toolchain.languageVersion = JavaLanguageVersion.of(21)
    kotlin.jvmToolchain(21)
    packaging {
        applicationVariants.all {
            outputs.all {
                (this as BaseVariantOutputImpl).outputFileName = "PadHomeOptimize-$versionName.apk"
            }
        }
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
}
