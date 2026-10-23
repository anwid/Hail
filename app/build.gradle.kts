plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    val signingProps = file("../signing.properties")
    // Source archives do not include Git metadata, but should still be buildable.
    val hasGitMetadata = rootProject.file(".git").exists()
    val commitHash = if (hasGitMetadata) providers.exec {
        workingDir = rootDir
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.get().trim() else "custom"
    val commitSubject = if (hasGitMetadata) providers.exec {
        workingDir = rootDir
        commandLine("git", "log", "-1", "--pretty=%s")
    }.standardOutput.asText.get().trim() else ""

    namespace = "com.aistra.hail"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aistra.hail"
        minSdk = 23
        targetSdk = 36
        versionCode = 35
        versionName = "1.11.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = if (hasGitMetadata) "-g$commitHash" else "-custom"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (!commitSubject.startsWith("[release]")) {
                versionNameSuffix = if (hasGitMetadata) "-g$commitHash" else "-custom"
            }
            signingConfig = if (signingProps.exists()) {
                val props = `java.util`.Properties().apply { load(signingProps.reader()) }
                signingConfigs.create("release") {
                    storeFile = file(props.getProperty("storeFile"))
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
            } else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }
    androidResources {
        generateLocaleConfig = true
        // Do not compress the dex files, so the apk can be imported as a privileged app
        noCompress += "dex"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach {
            if (it is com.android.build.api.variant.impl.VariantOutputImpl)
                it.outputFileName = "Hail-v${it.versionName.get()}.apk"
        }
    }
}
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.pinyin4j)
    implementation(libs.material)
    implementation(libs.insetter)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.dhizuku.api)
    implementation(libs.appiconloader)
    implementation(libs.compose.preference)
    implementation(libs.commons.text)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hiddenapibypass)
    compileOnly(libs.libxposed.api)
}
