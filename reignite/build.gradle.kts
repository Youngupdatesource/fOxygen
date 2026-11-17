plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "frb.axeron.reignite"
    packaging {
        jniLibs {
            keepDebugSymbols += "**/libaxreignite.so"
        }
    }
}

dependencies {
    // DdmHandleAppName is hidden; app_process has it at runtime.
    compileOnly(libs.rikka.hidden.stub)
}

// ---- libaxreignite.so: the igniter's own dex ----
//
// The igniter runs in app_process (`CLASSPATH=<lib dir>/libaxreignite.so app_process /
// frb.axeron.reignite.Igniter`), from the plugin service and from the server's death watchdog.
// Its dex is R8-shrunk to what Igniter.main reaches and shipped as lib<abi>/libaxreignite.so,
// so it is in the manager's native lib dir with nothing to extract. ART goes by the dex magic,
// not the name. Same as axsh's libaxshboot.so.

// This fork ships ARM only (root build.gradle.kts: abiFilters, packaging excludes).
val igniterAbis = listOf("arm64-v8a", "armeabi-v7a")
val igniterLibDir = layout.buildDirectory.dir("generated/igniterLib")
val igniterDexDir = layout.buildDirectory.dir("intermediates/igniterDex")

android.sourceSets.getByName("main").jniLibs.srcDir(igniterLibDir)

fun classesJars(configuration: String) = configurations.getByName(configuration).incoming
    .artifactView {
        attributes { attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar") }
        lenient(true)
    }.files

val igniterDex = tasks.register<JavaExec>("igniterDex") {
    val ownJar = tasks.named("bundleLibRuntimeToJarRelease")
    val runtimeJars = classesJars("releaseRuntimeClasspath")
    // Compile-only (hidden-api stubs): referenced, provided by the framework at runtime.
    val libraryJars = classesJars("releaseCompileClasspath").minus(runtimeJars)
    val bootClasspath = androidComponents.sdkComponents.bootClasspath
    val rules = file("proguard-rules.pro")
    val outDir = igniterDexDir

    inputs.files(ownJar, runtimeJars, libraryJars, rules)
    outputs.dir(outDir)

    classpath = files(
        android.sdkDirectory.resolve("build-tools/${android.buildToolsVersion}/lib/d8.jar")
    )
    mainClass.set("com.android.tools.r8.R8")
    argumentProviders.add(CommandLineArgumentProvider {
        outDir.get().asFile.mkdirs()
        buildList {
            addAll(listOf("--release", "--min-api", "26", "--output", outDir.get().asFile.path))
            addAll(listOf("--pg-conf", rules.path))
            bootClasspath.get().forEach { addAll(listOf("--lib", it.asFile.path)) }
            libraryJars.forEach { addAll(listOf("--lib", it.path)) }
            ownJar.get().outputs.files.filter { it.name.endsWith(".jar") }.forEach { add(it.path) }
            runtimeJars.forEach { add(it.path) }
        }
    })
}

val igniterLib = tasks.register<Sync>("igniterLib") {
    into(igniterLibDir)
    igniterAbis.forEach { abi ->
        from(igniterDex.map { it.outputs.files.singleFile.resolve("classes.dex") }) {
            into(abi)
            rename { "libaxreignite.so" }
        }
    }
}

tasks.matching { it.name.matches(Regex("merge\\w*JniLibFolders")) }.configureEach {
    dependsOn(igniterLib)
}

// The same dex, also as the manager's asset scripts/ax_reignite.dex. Shell scripts and a server
// that can't reach the manager's lib dir run the igniter from the bin dir, and the manager's
// assets used to hold a dex that nobody rebuilt. Written outside any declared output, so the
// manager's merge*Assets tasks only have to run after it (see manager/build.gradle.kts).
val igniterAsset = tasks.register("igniterAsset") {
    dependsOn(igniterDex)
    val dex = igniterDex.map { it.outputs.files.singleFile.resolve("classes.dex") }
    val assets = rootProject.project(":manager").projectDir.resolve("src/main/assets/scripts")
    doLast {
        copy {
            from(dex)
            into(assets)
            rename { "ax_reignite.dex" }
        }
    }
}
