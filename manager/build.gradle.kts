import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.rikka.tools.refine)
    id("kotlin-parcelize")
}

android {
    namespace = "frb.axeron.manager"

    defaultConfig {
        applicationId = "com.foxygen.manager"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("frb-project")
        }
        release {
            signingConfig = signingConfigs.getByName("frb-project")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    applicationVariants.all {
        val ts = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyMMddHHmm"))
        outputs.all {
            val outputImpl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            outputImpl.outputFileName = "fOxygen_v${versionName}_${versionCode}-${buildType.name}_$ts.apk"

            val outDir = File(rootDir, "out")
            val mappingPath = File(outDir, "mapping").absolutePath

            assembleProvider.get().doLast {
                // copy mapping.txt kalau minify aktif
                if (buildType.isMinifyEnabled) {
                    copy {
                        from(mappingFileProvider.get())
                        into(mappingPath)
                        rename {
                            "manager-v${versionName}.txt"
                        }
                    }
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {

    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.destinations.core)
    ksp(libs.compose.destinations.ksp)
    implementation(libs.colorpicker.compose)

    implementation(libs.compose.coil)
    implementation(libs.compose.coil.gif)
    implementation(libs.appiconloader.coil)
    implementation(libs.appiconloader)

    implementation(libs.androidx.webkit)
    implementation(libs.androidx.core.ktx)
    // Splash screen on every API level, with the theme swap after it (Theme.AxManager.Starting).
    implementation(libs.androidx.core.splashscreen)
    // Installs the baseline profiles the AndroidX libraries (Compose among them) ship, so their
    // code is compiled ahead of time instead of being interpreted on the first launches.
    implementation(libs.androidx.profileinstaller)

    implementation(libs.rikka.compatibility)
    implementation(libs.rikka.parcelablelist)
    implementation(libs.rikka.hidden.compat)
    compileOnly(libs.rikka.hidden.stub)

    implementation(libs.topjohnwu.libsu.core)
    implementation(libs.topjohnwu.libsu.io)

    implementation(libs.gson)
    implementation(libs.markdown)
    implementation(project(":server"))

    implementation(project(":aidl"))
    implementation(project(":api"))
    implementation(project(":adb"))
    implementation(project(":shared"))
    implementation(project(":provider"))
    implementation(project(":server-shared"))
    implementation(project(":axerish"))
    implementation(project(":axsh"))
    // libaxreignite.so: the plugin igniter, run from the manager's native lib dir.
    implementation(project(":reignite"))
    implementation(libs.sdp.android)
    implementation(libs.material)
    implementation(libs.mmrl.ui)
    implementation(libs.hiddenapibypass)
    implementation(libs.ansi.library)
    implementation(libs.ansi.library.ktx)

    implementation(libs.sheet.compose.dialogs.core)
    implementation(libs.sheet.compose.dialogs.list)
    implementation(libs.sheet.compose.dialogs.input)

    implementation(libs.androidx.work.runtime.ktx)
}

// scripts/ax_reignite.dex is rebuilt from :reignite on every build, so the asset can never be
// older than libaxreignite.so (it was a prebuilt file nobody regenerated).
tasks.matching { it.name.matches(Regex("merge\\w*Assets")) }.configureEach {
    dependsOn(":reignite:igniterAsset")
}
