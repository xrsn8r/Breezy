import java.net.URI
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------------------------
// Rust integration
//
//  * cargoNdkBuild     – cross-compiles rust/breezy-core into libbreezy_core.so for each ABI.
//  * fetchFilterLists  – downloads EasyList, EasyPrivacy, uBO filters, Brave unbreak + resources.
//  * compileFilterEngine – runs the host `breezy-compile` binary to turn those lists into a
//    serialized adblock-rust engine that ships in assets, so first launch blocks ads instantly.
// ---------------------------------------------------------------------------------------------

val rustDir: File = rootProject.file("rust")
val rustAbis = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "x86_64" to "x86_64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
)
val minSdkVersion = 26

// Release builds target arm64-v8a only (virtually every Android phone in use); debug builds also
// include x86_64 (emulators) and armeabi-v7a. Override with -PbreezyAbis=arm64-v8a,x86_64.
val releaseAbis = listOf("arm64-v8a")
val debugAbis = rustAbis.keys.toList()
val abiOverride: List<String>? = providers.gradleProperty("breezyAbis").orNull?.split(',')?.map { it.trim() }
fun abisFor(release: Boolean) = abiOverride ?: if (release) releaseAbis else debugAbis

val filterLists = mapOf(
    "easylist.txt" to "https://easylist.to/easylist/easylist.txt",
    "easyprivacy.txt" to "https://easylist.to/easylist/easyprivacy.txt",
    "ublock-filters.txt" to "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
    "ublock-privacy.txt" to "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt",
    "brave-unbreak.txt" to "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-unbreak.txt",
    "hide-yt-shorts.txt" to "https://raw.githubusercontent.com/gijsdev/ublock-hide-yt-shorts/master/list.txt",
)
// uBlock Origin's and Brave's own lists may use "trusted" scriptlets (e.g. YouTube ad removal).
fun isTrustedList(name: String) = name.startsWith("ublock-") || name.startsWith("brave-")
val resourcesUrl = "https://raw.githubusercontent.com/brave/adblock-resources/master/dist/resources.json"

abstract class CargoNdkTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustSources: ConfigurableFileCollection
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val platform: Property<Int>
    @get:Input abstract val release: Property<Boolean>
    @get:Internal abstract val workingDir: DirectoryProperty
    @get:Internal abstract val ndkDir: DirectoryProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty

    @TaskAction
    fun build() {
        val out = outDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val args = mutableListOf("ndk")
        abis.get().forEach { args += listOf("-t", it) }
        args += listOf("-P", platform.get().toString(), "-o", out.absolutePath, "build", "-p", "breezy-core", "--lib")
        if (release.get()) args += "--release"
        exec.exec {
            workingDir(this@CargoNdkTask.workingDir)
            environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath)
            commandLine(listOf("cargo") + args)
        }
    }
}

abstract class FetchFilterListsTask : DefaultTask() {
    @get:Input abstract val urls: MapProperty<String, String>
    @get:Input abstract val refresh: Property<Boolean>
    @get:OutputDirectory abstract val outDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val dir = outDir.get().asFile.apply { mkdirs() }
        urls.get().forEach { (name, url) ->
            val target = File(dir, name)
            if (target.exists() && target.length() > 0 && !refresh.get()) return@forEach
            logger.lifecycle("Downloading $url")
            val conn = URI(url).toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "Breezy-Build")
            }
            val tmp = File(dir, "$name.tmp")
            conn.getInputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(target)
        }
    }
}

/**
 * Fetches uBlock Origin's scriptlet modules and redirect resources (plain files over HTTPS) so
 * `breezy-resources` can extract them. Brave's resources.json alone lacks uBO's core scriptlets
 * (json-prune, set-constant, trusted-replace-*), which the uBO lists, including YouTube's ad rules,
 * depend on. Nothing downloaded here is executed; it is parsed as text.
 */
abstract class FetchUboResourcesTask : DefaultTask() {
    @get:Input abstract val ref: Property<String>
    @get:Input abstract val refresh: Property<Boolean>
    @get:OutputDirectory abstract val outDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val root = outDir.get().asFile
        val marker = File(root, ".complete-${ref.get()}")
        if (marker.exists() && !refresh.get()) return
        root.deleteRecursively()
        val base = "https://raw.githubusercontent.com/gorhill/uBlock/${ref.get()}/"
        fun get(path: String): ByteArray {
            val conn = URI(base + path).toURL().openConnection().apply {
                connectTimeout = 30_000; readTimeout = 60_000
                setRequestProperty("User-Agent", "Breezy-Build")
            }
            return conn.getInputStream().use { it.readBytes() }
        }
        fun save(path: String, data: ByteArray) = File(root, path).apply { parentFile.mkdirs() }.writeBytes(data)

        val importRe = Regex("""(?:from\s+|import\s+)'(\.\.?/[\w./-]+\.js)'""")
        val queue = ArrayDeque(listOf("src/js/resources/scriptlets.js"))
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            if (!seen.add(path)) continue
            val data = get(path)
            save(path, data)
            val dir = path.substringBeforeLast('/')
            importRe.findAll(String(data)).forEach { m ->
                val target = File("/$dir/${m.groupValues[1]}").normalize().path.removePrefix("/")
                // Only modules inside src/js (the resources dir and its direct parent helpers).
                if (target.startsWith("src/js/")) queue.add(target)
            }
        }
        val redirects = get("src/js/redirect-resources.js")
        save("src/js/redirect-resources.js", redirects)
        Regex("""\[\s*'([\w.-]+)'\s*,\s*\{""").findAll(String(redirects)).forEach { m ->
            val name = m.groupValues[1]
            runCatching { save("src/web_accessible_resources/$name", get("src/web_accessible_resources/$name")) }
        }
        logger.lifecycle("Fetched ${seen.size} uBO scriptlet modules")
        marker.writeText("ok")
    }
}

abstract class CompileEngineTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustSources: ConfigurableFileCollection
    @get:InputDirectory abstract val listsDir: DirectoryProperty
    @get:InputDirectory abstract val uboDir: DirectoryProperty
    @get:Input abstract val listNames: ListProperty<String>
    @get:Input abstract val trustedNames: ListProperty<String>
    @get:Internal abstract val workingDir: DirectoryProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty

    @TaskAction
    fun compile() {
        val adblockDir = File(outDir.get().asFile, "adblock").apply { mkdirs() }
        val trusted = trustedNames.get().toSet()
        val lists = listNames.get().map { name ->
            (if (name in trusted) "+" else "") + File(listsDir.get().asFile, name).absolutePath
        }
        fun cargo(bin: String, args: List<String>) = exec.exec {
            workingDir(this@CompileEngineTask.workingDir)
            commandLine(listOf("cargo", "run", "--release", "-q", "-p", "breezy-core", "--bin", bin, "--") + args)
        }
        cargo("breezy-compile", listOf(File(adblockDir, "engine.dat").absolutePath) + lists)
        // Brave's resources + uBO scriptlets/redirects, extracted statically.
        cargo("breezy-resources", listOf(
            uboDir.get().asFile.absolutePath,
            File(listsDir.get().asFile, "resources.json").absolutePath,
            File(adblockDir, "resources.json").absolutePath,
        ))
    }
}

val rustSourceFiles = fileTree(rustDir) {
    include("Cargo.toml", "Cargo.lock", "breezy-core/Cargo.toml", "breezy-core/src/**")
}

val fetchFilterLists = tasks.register<FetchFilterListsTask>("fetchFilterLists") {
    urls.set(filterLists + ("resources.json" to resourcesUrl))
    refresh.set(providers.gradleProperty("refreshFilterLists").map { it.toBoolean() }.orElse(false))
    outDir.set(layout.buildDirectory.dir("filterlists"))
}

val fetchUboResources = tasks.register<FetchUboResourcesTask>("fetchUboResources") {
    ref.set(providers.gradleProperty("uboRef").orElse("master"))
    refresh.set(providers.gradleProperty("refreshFilterLists").map { it.toBoolean() }.orElse(false))
    outDir.set(layout.buildDirectory.dir("ubo"))
}

val compileFilterEngine = tasks.register<CompileEngineTask>("compileFilterEngine") {
    rustSources.from(rustSourceFiles)
    listsDir.set(fetchFilterLists.flatMap { it.outDir })
    uboDir.set(fetchUboResources.flatMap { it.outDir })
    listNames.set(filterLists.keys.toList())
    trustedNames.set(filterLists.keys.filter(::isTrustedList))
    workingDir.set(rustDir)
    outDir.set(layout.buildDirectory.dir("generated/breezyAssets"))
}

android {
    namespace = "dev.breezy.browser"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.breezy.browser"
        minSdk = minSdkVersion
        targetSdk = 36
        versionCode = 46
        versionName = "0.11.9"
    }

    // Optional release signing from the environment (CI secrets); falls back to the debug key.
    val keystorePath = System.getenv("BREEZY_KEYSTORE")
    val signingEnv = listOf("BREEZY_KEYSTORE_PASSWORD", "BREEZY_KEY_ALIAS", "BREEZY_KEY_PASSWORD")
    val missing = signingEnv.filter { System.getenv(it).isNullOrEmpty() }
    if (keystorePath != null && missing.isNotEmpty()) {
        logger.warn("Release keystore given but $missing not set; signing with the debug key instead.")
    }
    if (keystorePath != null && missing.isEmpty()) {
        signingConfigs.create("release") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("BREEZY_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("BREEZY_KEY_ALIAS")
            keyPassword = System.getenv("BREEZY_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            ndk { abiFilters += abisFor(release = false) }
        }
        release {
            ndk { abiFilters += abisFor(release = true) }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("robolectric.graphicsMode", "NATIVE"); it.maxHeapSize = "2g" }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Native libs stay uncompressed and page-aligned so they're mapped straight from the APK.
        jniLibs.useLegacyPackaging = false
        // Library license/metadata files and Kotlin reflection metadata the app never reads.
        resources.excludes += listOf(
            "META-INF/**/LICENSE*", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.version",
            "META-INF/**/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin", "kotlin-tooling-metadata.json",
            "META-INF/androidx/**", "META-INF/org/**",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val cargo = tasks.register<CargoNdkTask>("cargoNdkBuild$cap") {
            rustSources.from(rustSourceFiles)
            abis.set(abisFor(release = variant.buildType == "release"))
            platform.set(minSdkVersion)
            release.set(true) // debug Rust builds of adblock-rust are ~10x slower; always optimise.
            workingDir.set(rustDir)
            ndkDir.set(androidComponents.sdkComponents.ndkDirectory)
            outDir.set(layout.buildDirectory.dir("generated/rustJniLibs/${variant.name}"))
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargo, CargoNdkTask::outDir)
        variant.sources.assets?.addGeneratedSourceDirectory(compileFilterEngine, CompileEngineTask::outDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.androidx.media)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for JVM tests (Android's is a stub)
    // Screenshot tests: real Compose screens rendered on the JVM (Robolectric native graphics).
    testImplementation("org.robolectric:robolectric:4.18")
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
