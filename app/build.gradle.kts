@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.FilterConfiguration

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.room)
    alias(libs.plugins.ktfmt.gradle)
}

val splitApks = !project.hasProperty("noSplits")

val abiFilterList = (properties["ABI_FILTERS"] as String).split(';')

val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)

val baseVersionName = currentVersion.name
val currentVersionCode = currentVersion.code.toInt()

android {
    compileSdk = 35

    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "com.snapnet.preview"
        minSdk = 24
        targetSdk = 35
        versionCode = 100_070_400
        check(versionCode == currentVersionCode)

        versionName = baseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        if (splitApks) {
            splits {
                abi {
                    isEnable = true
                    reset()
                    include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                    // One APK per ABI, each carrying only its own native libraries. A universal APK
                    // would bundle all four architectures and roughly quadruple the download.
                    isUniversalApk = false
                }
            }
        } else {
            ndk { abiFilters.addAll(abiFilterList) }
        }
    }

    room { schemaDirectory("$projectDir/schemas") }
    ksp { arg("room.incremental", "true") }

    androidComponents {
        onVariants { variant ->
            variant.outputs.forEach { output ->
                val name =
                    if (splitApks) {
                        output.filters
                            .find { it.filterType == FilterConfiguration.FilterType.ABI }
                            ?.identifier
                    } else {
                        abiFilterList.firstOrNull()
                    }

                val baseAbiCode = abiCodes[name]

                if (baseAbiCode != null) {
                    output.versionCode.set(baseAbiCode + (output.versionCode.get() ?: 0))
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "proguard-rules-debug.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    flavorDimensions += "publishChannel"

    productFlavors {
        create("generic") {
            dimension = "publishChannel"
            isDefault = true
        }

        create("githubPreview") {
            dimension = "publishChannel"
        }

        create("fdroid") {
            dimension = "publishChannel"
            versionName = "$baseVersionName-(F-Droid)"
        }
    }

    lint { disable.addAll(listOf("MissingTranslation", "ExtraTranslation", "MissingQuantity")) }

    applicationVariants.all {
        val buildTypeName = buildType.name
        outputs.all {
            val outputImpl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            // Match on the known ABI identifiers rather than inspecting the filter type: the type of
            // getFilterType() differs between AGP API generations (a String in the legacy FilterData
            // API, an enum in FilterConfiguration), while getIdentifier() is a String in both.
            val abi =
                listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                    .firstOrNull { candidate ->
                        outputImpl.filters.any { it.identifier == candidate }
                    }
            // Deliberately excludes the flavour name so the published files are predictable:
            //   Nazelha-<version>-arm64-v8a-release.apk
            outputImpl.outputFileName =
                if (abi != null) "Nazelha-${defaultConfig.versionName}-$abi-$buildTypeName.apk"
                else "Nazelha-${defaultConfig.versionName}-$buildTypeName.apk"
        }
    }

    kotlinOptions { freeCompilerArgs = freeCompilerArgs + "-opt-in=kotlin.RequiresOptIn" }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs.useLegacyPackaging = true
    }
    androidResources { generateLocaleConfig = true }

    namespace = "com.snapnet"
}

ktfmt { kotlinLangStyle() }

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":color"))

    implementation(libs.bundles.core)

    implementation(libs.androidx.lifecycle.runtimeCompose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.androidxCompose)
    implementation(libs.bundles.accompanist)

    implementation(libs.coil.kt.compose)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.jsoup)

    implementation(libs.bundles.youtubedlAndroid)

    implementation(libs.mmkv)

    testImplementation(libs.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.espresso.core)
    // AndroidJUnitRunner uses Kotlin stdlib at runtime; keep it in the test APK even when R8 shrinks the app APK.
    androidTestImplementation(libs.kotlin.stdlib)
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation(libs.androidx.compose.ui.tooling)
}
