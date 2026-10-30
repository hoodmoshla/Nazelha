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
val previewSigningEnvironment =
    mapOf(
        "NAZELHA_KEYSTORE_FILE" to providers.environmentVariable("NAZELHA_KEYSTORE_FILE").orNull,
        "NAZELHA_STORE_PASSWORD" to providers.environmentVariable("NAZELHA_STORE_PASSWORD").orNull,
        "NAZELHA_KEY_ALIAS" to providers.environmentVariable("NAZELHA_KEY_ALIAS").orNull,
        "NAZELHA_KEY_PASSWORD" to providers.environmentVariable("NAZELHA_KEY_PASSWORD").orNull,
    )
val productionSigningEnvironment =
    mapOf(
        "NAZELHA_PRODUCTION_KEYSTORE_FILE" to providers.environmentVariable("NAZELHA_PRODUCTION_KEYSTORE_FILE").orNull,
        "NAZELHA_PRODUCTION_STORE_PASSWORD" to providers.environmentVariable("NAZELHA_PRODUCTION_STORE_PASSWORD").orNull,
        "NAZELHA_PRODUCTION_KEY_ALIAS" to providers.environmentVariable("NAZELHA_PRODUCTION_KEY_ALIAS").orNull,
        "NAZELHA_PRODUCTION_KEY_PASSWORD" to providers.environmentVariable("NAZELHA_PRODUCTION_KEY_PASSWORD").orNull,
    )

if (providers.gradleProperty("nazelhaRequirePreviewSigning").orNull == "true") {
    val missingInputs = previewSigningEnvironment.filterValues { it.isNullOrBlank() }.keys
    check(missingInputs.isEmpty()) {
        "Missing required Nazelha preview signing inputs: ${missingInputs.joinToString()}"
    }
    val keystorePath = previewSigningEnvironment.getValue("NAZELHA_KEYSTORE_FILE")!!
    check(file(keystorePath).isFile) { "Nazelha preview signing keystore file is unavailable." }
}

if (providers.gradleProperty("nazelhaRequireProductionSigning").orNull == "true") {
    val missingInputs = productionSigningEnvironment.filterValues { it.isNullOrBlank() }.keys
    check(missingInputs.isEmpty()) {
        "Missing required Nazelha production signing inputs: ${missingInputs.joinToString()}"
    }
    val keystorePath = productionSigningEnvironment.getValue("NAZELHA_PRODUCTION_KEYSTORE_FILE")!!
    check(file(keystorePath).isFile) { "Nazelha production signing keystore file is unavailable." }
}

android {
    compileSdk = 35

    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "com.hoodmoshla.nazelha"
        minSdk = 24
        targetSdk = 35
        versionCode = 100_110_400
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

    signingConfigs {
        create("nazelhaPreview") {
            previewSigningEnvironment["NAZELHA_KEYSTORE_FILE"]?.let { storeFile = file(it) }
            storePassword = previewSigningEnvironment["NAZELHA_STORE_PASSWORD"]
            keyAlias = previewSigningEnvironment["NAZELHA_KEY_ALIAS"]
            keyPassword = previewSigningEnvironment["NAZELHA_KEY_PASSWORD"]
            storeType = "JKS"
        }
        create("nazelhaProduction") {
            productionSigningEnvironment["NAZELHA_PRODUCTION_KEYSTORE_FILE"]?.let { storeFile = file(it) }
            storePassword = productionSigningEnvironment["NAZELHA_PRODUCTION_STORE_PASSWORD"]
            keyAlias = productionSigningEnvironment["NAZELHA_PRODUCTION_KEY_ALIAS"]
            keyPassword = productionSigningEnvironment["NAZELHA_PRODUCTION_KEY_PASSWORD"]
            storeType = "JKS"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("nazelhaProduction")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("distribution") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-preview"
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("nazelhaPreview")
            matchingFallbacks += listOf("release")
        }
        debug {
            val minifyDebugTarget =
                project.providers.gradleProperty("nazelhaUiTestsWithoutR8").orNull != "true"
            isMinifyEnabled = minifyDebugTarget
            isShrinkResources = minifyDebugTarget
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
            applicationId = "com.snapnet.preview"
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

    namespace = "com.hoodmoshla.nazelha"
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
