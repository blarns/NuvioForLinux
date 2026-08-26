import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject
import java.net.URI
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.util.Properties

abstract class GenerateRuntimeConfigsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val localPropertiesFile: RegularFileProperty

    @get:Input
    abstract val appVersionName: Property<String>

    @get:Input
    abstract val appVersionCode: Property<Int>

    @TaskAction
    fun generate() {
        val props = Properties()
        localPropertiesFile.asFile.orNull?.takeIf { it.exists() }?.inputStream()?.use { props.load(it) }

        var supabaseUrl = props.getProperty("SUPABASE_URL", "").trim()
        var supabaseAnonKey = props.getProperty("SUPABASE_ANON_KEY", "").trim()
        // An empty URL makes supabase-kt/ktor resolve every auth + REST call to localhost, which silently
        // breaks login, profile sync and the avatar catalog at runtime. This shipped in 0.1.13–0.1.17 because
        // local.properties (gitignored) was absent when those debs were built in a clean checkout/worktree.
        // When no override is supplied, fall back to the official Nuvio backend (post July 1, 2026 switch:
        // https://api.nuvio.tv). The anon key is Supabase's public client-side credential — it ships inside
        // every official Nuvio build and is safe to embed. Set both keys in local.properties to override
        // (e.g. to point at your own Supabase project); partial overrides still fail the build below.
        if (supabaseUrl.isBlank() && supabaseAnonKey.isBlank()) {
            supabaseUrl = "https://api.nuvio.tv"
            supabaseAnonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
                "eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzgxNTIxMzQ2LCJleHAiOjE5MzkyMDEzNDZ9." +
                "tmQaj682pwzehpqlgCDMnySOqiUvpgRbrE43T4VJpDI"
        }
        if (supabaseUrl.isBlank() || supabaseAnonKey.isBlank()) {
            error(
                "Exactly one of SUPABASE_URL / SUPABASE_ANON_KEY is set in local.properties. " +
                    "Refusing to generate a partial SupabaseConfig — an empty URL makes every auth/network request " +
                    "resolve to localhost and breaks login at runtime (exactly what shipped broken in 0.1.13–0.1.17). " +
                    "Set both keys to use a custom backend, or remove both to use the official Nuvio backend."
            )
        }

        val outDir = outputDir.get().asFile
        outDir.resolve("com/nuvio/app/core/network").apply {
            mkdirs()
            resolve("SupabaseConfig.kt").writeText(
                """
                |package com.nuvio.app.core.network
                |
                |object SupabaseConfig {
                |    const val URL = "$supabaseUrl"
                |    const val ANON_KEY = "$supabaseAnonKey"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/tmdb/TmdbConfig.kt").delete()

        outDir.resolve("com/nuvio/app/features/trakt").apply {
            mkdirs()
            resolve("TraktConfig.kt").writeText(
                """
                |package com.nuvio.app.features.trakt
                |
                |object TraktConfig {
                |    const val CLIENT_ID = "${props.getProperty("TRAKT_CLIENT_ID", "")}" 
                |    const val CLIENT_SECRET = "${props.getProperty("TRAKT_CLIENT_SECRET", "")}" 
                |    const val REDIRECT_URI = "${props.getProperty("TRAKT_REDIRECT_URI", "nuvio://auth/trakt")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/player/skip").apply {
            mkdirs()
            resolve("IntroDbConfig.kt").writeText(
                """
                |package com.nuvio.app.features.player.skip
                |
                |object IntroDbConfig {
                |    const val URL = "${props.getProperty("INTRODB_API_URL", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/details").apply {
            mkdirs()
            resolve("ImdbEpisodeRatingsConfig.kt").writeText(
                """
                |package com.nuvio.app.features.details
                |
                |object ImdbEpisodeRatingsConfig {
                |    const val IMDB_RATINGS_API_BASE_URL = "${props.getProperty("IMDB_RATINGS_API_BASE_URL", "")}" 
                |    const val IMDB_TAPFRAME_API_BASE_URL = "${props.getProperty("IMDB_TAPFRAME_API_BASE_URL", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/debrid").apply {
            mkdirs()
            resolve("PremiumizeConfig.kt").writeText(
                """
                |package com.nuvio.app.features.debrid
                |
                |object PremiumizeConfig {
                |    const val CLIENT_ID = "${props.getProperty("PREMIUMIZE_CLIENT_ID", "")}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/discord").apply {
            mkdirs()
            // A Discord application id is a public identifier (it is visible in every RPC
            // handshake), so the NuvioForLinux one is baked in as the default. Without it,
            // release builds — which have no local.properties — shipped an empty id and the
            // Rich Presence setting silently did nothing.
            val discordClientId = props.getProperty("DISCORD_CLIENT_ID", "")
                .ifBlank { "1533716415117398118" }
            resolve("DiscordConfig.kt").writeText(
                """
                |package com.nuvio.app.features.discord
                |
                |object DiscordConfig {
                |    const val CLIENT_ID = "$discordClientId"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/core/build").apply {
            mkdirs()
            resolve("AppVersionConfig.kt").writeText(
                """
                |package com.nuvio.app.core.build
                |
                |object AppVersionConfig {
                |    const val VERSION_NAME = "${appVersionName.get()}"
                |    const val VERSION_CODE = ${appVersionCode.get()}
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/settings").apply {
            mkdirs()
            resolve("CommunityConfig.kt").writeText(
                """
                |package com.nuvio.app.features.settings
                |
                |object CommunityConfig {
                |    const val CONTRIBUTIONS_URL = "${props.getProperty("CONTRIBUTIONS_URL", "")}"
                |    const val DONATIONS_BASE_URL = "${props.getProperty("DONATIONS_BASE_URL", "")}"
                |    const val DONATIONS_DONATE_URL = "${props.getProperty("DONATIONS_DONATE_URL", "")}"
                |}
                """.trimMargin()
            )
        }
    }
}

// jpackage doesn't let the Compose plugin declare extra package relationships, so
// repack the deb to (1) add a hard runtime dependency on libVLC — VLCJ dlopen's the
// system libvlc.so, so a fresh .deb install has NO playback without it — and (2) add a
// Recommends on the emoji font (stream lists are full of emoji-formatted addon text
// that renders as tofu without one installed).
abstract class PatchDebRecommendsTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputDirectory
    abstract val debDirectory: DirectoryProperty

    @get:Input
    abstract val recommends: Property<String>

    @get:Input
    abstract val extraDepends: Property<String>

    // Files (by name) inside the deb that must be marked executable. jpackage/dpkg can
    // drop the exec bit on bundled app resources, and the installed copy is root-owned so
    // the app can't chmod it at runtime — so we fix it in the package itself.
    @get:Input
    @get:Optional
    abstract val executables: Property<String>

    @TaskAction
    fun patch() {
        val debs = debDirectory.get().asFile.listFiles { file -> file.extension == "deb" }.orEmpty()
        val dep = extraDepends.get().takeIf { it.isNotBlank() }
        val rec = recommends.get().takeIf { it.isNotBlank() }
        val execNames = executables.orNull?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        debs.forEach { deb ->
            val workDir = File(temporaryDir, deb.nameWithoutExtension)
            workDir.deleteRecursively()
            execOperations.exec {
                commandLine("dpkg-deb", "-R", deb.absolutePath, workDir.absolutePath)
            }
            val control = workDir.resolve("DEBIAN/control")
            var lines = control.readLines().filterNot { it.isBlank() }
            var changed = false

            // Ensure bundled binaries are executable inside the package.
            if (execNames.isNotEmpty()) {
                workDir.walkTopDown()
                    .filter { it.isFile && it.name in execNames }
                    .forEach { bin ->
                        if (!bin.canExecute()) {
                            bin.setExecutable(true, false)
                            changed = true
                        }
                    }
            }

            // Append the runtime dependency onto the existing Depends: field (or add one).
            if (dep != null && lines.none { it.contains(dep) }) {
                lines = if (lines.any { it.startsWith("Depends:") }) {
                    lines.map { if (it.startsWith("Depends:")) "$it, $dep" else it }
                } else {
                    lines + "Depends: $dep"
                }
                changed = true
            }

            // Add the Recommends: field if absent.
            if (rec != null && lines.none { it.startsWith("Recommends:") }) {
                lines = lines + "Recommends: $rec"
                changed = true
            }

            if (changed) {
                control.writeText(lines.joinToString("\n") + "\n")
                execOperations.exec {
                    commandLine("dpkg-deb", "-b", "--root-owner-group", workDir.absolutePath, deb.absolutePath)
                }
            }
            workDir.deleteRecursively()
        }
    }
}

// Downloads the TorrServer Linux binary (P2P engine) into the app-resources dir at build
// time, so the ~74 MB binary stays out of git. Pinned + SHA-256 verified for reproducibility.
abstract class FetchTorrServerTask : DefaultTask() {
    @get:Input
    abstract val downloadUrl: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val target: RegularFileProperty

    @TaskAction
    fun fetch() {
        val out = target.get().asFile
        val expected = sha256.get().lowercase()
        if (out.exists() && sha256Of(out) == expected) {
            logger.lifecycle("TorrServer binary already present and verified: ${out.absolutePath}")
            return
        }
        out.parentFile.mkdirs()
        val url = downloadUrl.get()
        logger.lifecycle("Downloading TorrServer from $url")
        URI(url).toURL().openStream().use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        val actual = sha256Of(out)
        check(actual == expected) {
            "TorrServer checksum mismatch.\n  expected: $expected\n  actual:   $actual\n  file:     ${out.absolutePath}"
        }
        out.setExecutable(true, false)
        logger.lifecycle("TorrServer downloaded and verified (${out.length()} bytes)")
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

fun readXcconfigValue(file: File, key: String): String? {
    if (!file.exists()) return null
    return file.readLines()
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .map { line ->
            val separatorIndex = line.indexOf('=')
            line.substring(0, separatorIndex).trim() to line.substring(separatorIndex + 1).trim()
        }
        .firstOrNull { (entryKey, _) -> entryKey == key }
        ?.second
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

val supabaseProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}
val releaseStoreFile = supabaseProps.getProperty("NUVIO_RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStorePassword = supabaseProps.getProperty("NUVIO_RELEASE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = supabaseProps.getProperty("NUVIO_RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = supabaseProps.getProperty("NUVIO_RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeystore = releaseStoreFile?.let(rootProject::file)
val appVersionConfigFile = rootProject.file("iosApp/Configuration/Version.xcconfig")
val releaseAppVersionName = readXcconfigValue(appVersionConfigFile, "MARKETING_VERSION")
    ?: error("MARKETING_VERSION is missing from ${appVersionConfigFile.path}")
val releaseAppVersionCode = readXcconfigValue(appVersionConfigFile, "CURRENT_PROJECT_VERSION")
    ?.toIntOrNull()
    ?: error("CURRENT_PROJECT_VERSION is missing or invalid in ${appVersionConfigFile.path}")
val iosDistribution = (
    providers.gradleProperty("nuvio.ios.distribution").orNull
        ?: System.getenv("NUVIO_IOS_DISTRIBUTION")
        ?: supabaseProps.getProperty("NUVIO_IOS_DISTRIBUTION")
        ?: "appstore"
    ).trim().lowercase()
require(iosDistribution == "appstore" || iosDistribution == "full") {
    "NUVIO_IOS_DISTRIBUTION must be 'appstore' or 'full'."
}
val iosDistributionSourceDir = if (iosDistribution == "full") {
    "src/iosFull/kotlin"
} else {
    "src/iosAppStore/kotlin"
}
val iosFrameworkBundleId = "com.nuvio.media"
val fullCommonSourceDir = project.file("src/fullCommonMain/kotlin")
val fullPluginSourceDir = fullCommonSourceDir.resolve("com/nuvio/app/features/plugins")
val generatedRuntimeConfigDir = layout.buildDirectory.dir("generated/runtime-config/kotlin")
val requestedGradleTasks = gradle.startParameter.taskNames.map { taskName ->
    taskName.substringAfterLast(':').lowercase()
}
val isAndroidAppBundleBuild = requestedGradleTasks.any { taskName ->
    taskName == "bundle" ||
        taskName == "bundlerelease" ||
        taskName == "bundledebug" ||
        taskName.startsWith("bundleplaystore") ||
        taskName.startsWith("bundlefull") ||
        taskName.endsWith("bundle")
}

val generateRuntimeConfigs = tasks.register<GenerateRuntimeConfigsTask>("generateRuntimeConfigs") {
    outputDir.set(generatedRuntimeConfigDir)
    // Only wire the input when it actually exists, so an absent local.properties reaches the task's own
    // guard (clear error) instead of failing earlier on @InputFile "file doesn't exist" validation.
    rootProject.layout.projectDirectory.file("local.properties").let { lp ->
        if (lp.asFile.exists()) localPropertiesFile.set(lp)
    }
    appVersionName.set(releaseAppVersionName)
    appVersionCode.set(releaseAppVersionCode)
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateRuntimeConfigs)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    val iosTargets = listOf(
        iosArm64(),
        iosSimulatorArm64()
    )

    iosTargets.forEach { iosTarget ->
        iosTarget.compilations.getByName("main") {
            cinterops {
                create("commoncrypto") {
                    defFile(project.file("src/nativeInterop/cinterop/commoncrypto.def"))
                    compilerOpts("-I${project.projectDir}/src/nativeInterop/cinterop")
                }
            }

            if (iosDistribution == "full") {
                defaultSourceSet.kotlin.srcDir(fullCommonSourceDir)
            }
            defaultSourceSet.kotlin.srcDir(project.file(iosDistributionSourceDir))
            defaultSourceSet.dependencies {
                implementation(libs.ktor.client.darwin)
                if (iosDistribution == "full") {
                    implementation(libs.quickjs.kt)
                    implementation(libs.ksoup)
                }
            }
        }

        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            freeCompilerArgs += listOf("-Xbinary=bundleId=$iosFrameworkBundleId")
        }
    }
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generatedRuntimeConfigDir)
        }
        val jvmMain by getting {
            dependsOn(commonMain)
            kotlin.srcDir("src/desktopMain/kotlin")
            // ⚠ The matching resources dir, and it was missing until 0.3.7. Only the *kotlin*
            // half of desktopMain was ever registered, so every file under
            // src/desktopMain/resources was silently dropped from the build — including the 12
            // app-icon PNGs the window icon picker loads by classpath path. Nothing failed
            // loudly: Main.kt falls back to nuvio-icon.png, which happens to exist because a
            // duplicate copy sits in src/jvmMain/resources.
            resources.srcDir("src/desktopMain/resources")
            kotlin.srcDir(fullPluginSourceDir)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
                implementation(libs.ktor.client.java)
                // Plugin runtime (JS scrapers) — same deps upstream's desktop target uses
                implementation(libs.quickjs.kt)
                implementation(libs.ksoup)
                // VLCJ for cross-platform video playback on desktop
                implementation("uk.co.caprica:vlcj:4.8.2")
                // Ktor server for OAuth localhost redirect handler
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.netty)
                // MPRIS2 / D-Bus media key integration
                implementation("com.github.hypfvieh:dbus-java-core:4.3.1")
                implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:4.3.1")
            }
        }
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.appcompat)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.splashscreen)
            implementation(libs.androidx.work.runtime)
            implementation(libs.coil.gif)
            implementation("androidx.recyclerview:recyclerview:1.4.0")
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
            implementation("com.google.code.gson:gson:2.11.0")
            implementation("io.github.peerless2012:ass-media:0.4.0-beta01")
            implementation(libs.ktor.client.android)
            // Android-only Media3/ExoPlayer player stack
            implementation(libs.androidx.media3.exoplayer.hls)
            implementation(libs.androidx.media3.exoplayer.dash)
            implementation(libs.androidx.media3.exoplayer.smoothstreaming)
            implementation(libs.androidx.media3.exoplayer.rtsp)
            implementation(libs.androidx.media3.datasource)
            implementation(libs.androidx.media3.datasource.okhttp)
            implementation(libs.androidx.media3.decoder)
            implementation(libs.androidx.media3.session)
            implementation(libs.androidx.media3.common)
            implementation(libs.androidx.media3.container)
            implementation(libs.androidx.media3.extractor)
            // Android-only .aar library dependencies
            implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("lib-*.aar"))))
        }
        commonMain.dependencies {
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.coil.svg)
            implementation("dev.chrisbanes.haze:haze:1.7.2")
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.kermit)
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.auth)
            implementation(libs.supabase.functions)
            implementation(libs.supabase.storage)
            implementation(libs.supabase.realtime)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.reorderable)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// Android full flavor only: add scripting engine and HTML parser
afterEvaluate {
    dependencies {
        add("fullImplementation", files("libs/quickjs-kt-android-1.0.5-nuvio.aar"))
        add("fullImplementation", libs.ksoup)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    debugImplementation(libs.compose.uiTooling)
}

// Exclude duplicated media3 artifacts that might come from transitive deps
configurations.all {
    exclude(group = "androidx.media3", module = "media3-exoplayer")
    exclude(group = "androidx.media3", module = "media3-ui")
}

android {
    namespace = "com.nuvio.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    signingConfigs {
        create("release") {
            if (releaseKeystore != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.nuvio.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = releaseAppVersionCode
        versionName = releaseAppVersionName
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
        }
        create("playstore") {
            dimension = "distribution"
        }
    }
    sourceSets.getByName("full") {
        manifest.srcFile("src/androidFull/AndroidManifest.xml")
        java.srcDir(fullCommonSourceDir)
    }
    splits {
        abi {
            isEnable = !isAndroidAppBundleBuild
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = false
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            pickFirsts += listOf(
                "lib/*/libc++_shared.so",
                "lib/*/libavcodec.so",
                "lib/*/libavutil.so",
                "lib/*/libswscale.so",
                "lib/*/libswresample.so"
            )
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

compose.desktop {
    application {
        mainClass = "com.nuvio.app.MainKt"
        // VLCJ's ByteBufferFactory uses sun.misc.Unsafe for native video buffer allocation.
        // --add-opens alone isn't enough; jdk.unsupported must be included so the module
        // system exports sun.misc (including Unsafe) to unnamed modules.
        jvmArgs("--add-opens=java.base/sun.misc=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            // Bundles desktop-resources/<os-arch>/* into the app image; at runtime the
            // bundled files live under the dir named by the compose.application.resources.dir
            // system property (how P2pStreamingEngine.desktop locates the torrserver binary).
            appResourcesRootDir.set(project.layout.projectDirectory.dir("desktop-resources"))
            packageName = "nuvio"
            packageVersion = project.findProperty("packageVersion") as String? ?: releaseAppVersionName
            description = "Modern media hub with Stremio addon ecosystem support"
            copyright = "GPL-3.0"
            vendor = "NuvioForLinux"
            modules("java.net.http", "jdk.crypto.ec", "java.naming", "java.prefs", "jdk.unsupported")
            linux {
                iconFile.set(rootProject.file("nuvio-icon.png"))
                packageName = "nuvio"
                debMaintainer = "blarns"
                menuGroup = "AudioVideo"
                appCategory = "AudioVideo"
                shortcut = true
            }
        }
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}

// Pinned TorrServer release (YouROK/TorrServer) bundled for the desktop P2P engine.
val torrServerVersion = "MatriX.141.5"
val torrServerSha256 = "770233787f6020fc5a8ad225626e3102fada71a48522e9131de17033d1bbe8f1"

val fetchTorrServer = tasks.register<FetchTorrServerTask>("fetchTorrServer") {
    description = "Downloads the pinned TorrServer Linux binary into desktop-resources."
    downloadUrl.set(
        "https://github.com/YouROK/TorrServer/releases/download/$torrServerVersion/TorrServer-linux-amd64"
    )
    sha256.set(torrServerSha256)
    target.set(layout.projectDirectory.file("desktop-resources/linux-x64/torrserver"))
}

// Compose's prepareAppResources copies appResourcesRootDir into the app image; ensure the
// binary is present (and verified) before that runs.
tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(fetchTorrServer)
}

val patchDebRecommends = tasks.register<PatchDebRecommendsTask>("patchDebRecommends") {
    debDirectory.set(layout.buildDirectory.dir("compose/binaries/main/deb"))
    // ⚠ libmpv is a RECOMMENDS, not a Depends. The mpv engine is still opt-in behind
    // NUVIO_MPV=1 and VLCJ is the default, so making every user install libmpv for a disabled
    // feature would be wrong — this graduates to Depends if and when mpv becomes the default.
    // `libmpv2` specifically: 22.04 and Debian 12 ship libmpv1 (client API 1.x), which this
    // binding refuses, so an alternative dep on it would install something unusable.
    recommends.set("fonts-noto-color-emoji, libmpv2")
    extraDepends.set("vlc-plugin-base | vlc")
    executables.set("torrserver")
}

tasks.matching { it.name == "packageDeb" }.configureEach {
    finalizedBy(patchDebRecommends)
}
