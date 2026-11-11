import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Literal KEY=value files: values are never evaluated as shell code or unescaped.
fun readLiteralProperties(path: String, allowedKeys: Set<String>): Map<String, String> {
    val values = linkedMapOf<String, String>()
    rootProject.file(path).readLines().forEachIndexed { index, line ->
        if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
        val key = line.substringBefore('=')
        require('=' in line && key in allowedKeys && key !in values) {
            "$path:${index + 1}: invalid or duplicate property"
        }
        values[key] = line.substringAfter('=')
    }
    return values
}

val releaseVersion = readLiteralProperties("version.properties", setOf("VERSION_NAME", "VERSION_CODE"))
val configuredVersionName = requireNotNull(releaseVersion["VERSION_NAME"]) { "VERSION_NAME is required" }
val configuredVersionCodeText = requireNotNull(releaseVersion["VERSION_CODE"]) { "VERSION_CODE is required" }
require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(configuredVersionName)) {
    "VERSION_NAME must be a numeric major.minor.patch version"
}
val configuredVersionCode = configuredVersionCodeText.toIntOrNull()
require(Regex("[1-9][0-9]*").matches(configuredVersionCodeText) &&
    configuredVersionCode != null && configuredVersionCode in 1..2_100_000_000) {
    "VERSION_CODE must be an integer from 1 to 2100000000"
}
listOf("NAME" to configuredVersionName, "CODE" to configuredVersionCodeText).forEach { (key, value) ->
    require(providers.environmentVariable("NATSUKO_VERSION_$key").orNull.let { it == null || it == value }) {
        "NATSUKO_VERSION_$key must match version.properties; update that file to change the version"
    }
}

val unsignedRelease = providers.gradleProperty("natsukoUnsignedRelease").orNull.let {
    require(it == null || it == "true" || it == "false") { "natsukoUnsignedRelease must be true or false" }
    it == "true"
}
val signingKeys = setOf("NATSUKO_KEYSTORE", "NATSUKO_STORE_PASSWORD", "NATSUKO_KEY_ALIAS", "NATSUKO_KEY_PASSWORD")
val localSigning = if (!unsignedRelease && rootProject.file("release.properties").isFile) {
    readLiteralProperties("release.properties", signingKeys)
} else {
    emptyMap()
}
val releaseSigning = signingKeys.associateWith { providers.environmentVariable(it).orNull ?: localSigning[it] }
val hasReleaseSigning = !unsignedRelease && releaseSigning.values.all { !it.isNullOrBlank() }
val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    doLast {
        check(unsignedRelease || hasReleaseSigning) {
            "Release signing is required. Configure release.properties or NATSUKO_* signing variables; " +
                "use -PnatsukoUnsignedRelease=true only for verification builds."
        }
        if (hasReleaseSigning) {
            check(rootProject.file(checkNotNull(releaseSigning["NATSUKO_KEYSTORE"])).isFile) {
                "Release keystore does not exist"
            }
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(validateReleaseSigning) }

val verifyEmbeddedFfmpeg = tasks.register("verifyEmbeddedFfmpeg") {
    val vendorRoot = rootProject.file("third_party/ffmpeg")
    val checksums = vendorRoot.resolve("SHA256SUMS")
    val binaries = listOf("arm64-v8a", "x86_64").flatMap { abi ->
        listOf("ffmpeg", "ffprobe").map { tool -> "jniLibs/$abi/libnatsuko_$tool.so" }
    }
    inputs.files(binaries.map(vendorRoot::resolve), checksums)
    doLast {
        check(checksums.isFile) { "Embedded FFmpeg checksum manifest is missing; see third_party/ffmpeg/README.md" }
        val expected = checksums.readLines().filter(String::isNotBlank).associate { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            check(parts.size == 2 && Regex("[0-9a-f]{64}").matches(parts[0])) { "Invalid FFmpeg checksum entry" }
            parts[1] to parts[0]
        }
        binaries.forEach { relativePath ->
            val binary = vendorRoot.resolve(relativePath)
            check(binary.isFile && binary.length() > 0) { "Embedded FFmpeg binary missing: $relativePath" }
            val digest = MessageDigest.getInstance("SHA-256")
            binary.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    digest.update(buffer, 0, size)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == expected[relativePath]) { "Embedded FFmpeg checksum mismatch: $relativePath" }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyEmbeddedFfmpeg) }

val verifyEmbeddedMetadata = tasks.register("verifyEmbeddedMetadata") {
    val vendorRoot = rootProject.file("third_party/metadata")
    val checksums = vendorRoot.resolve("SHA256SUMS")
    val packagedFiles = listOf("arm64-v8a", "x86_64").flatMap { abi ->
        listOf("jniLibs/$abi/libnatsuko_perl.so", "assets/metadata/perl-5.40.2-$abi.zip")
    } + "assets/metadata/exiftool-13.53.zip"
    inputs.files(packagedFiles.map(vendorRoot::resolve), checksums)
    doLast {
        check(checksums.isFile) { "Embedded metadata checksum manifest missing; see third_party/metadata/README.md" }
        val expected = checksums.readLines().filter(String::isNotBlank).associate { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            check(parts.size == 2 && Regex("[0-9a-f]{64}").matches(parts[0])) { "Invalid metadata checksum entry" }
            parts[1] to parts[0]
        }
        packagedFiles.forEach { relativePath ->
            val file = vendorRoot.resolve(relativePath)
            check(file.isFile && file.length() > 0) { "Embedded metadata asset missing: $relativePath" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == expected[relativePath]) {
                "Embedded metadata checksum mismatch: $relativePath"
            }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyEmbeddedMetadata) }

android {
    namespace = "com.poppinzhang.natsuko"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.poppinzhang.natsuko"
        minSdk = 36
        targetSdk = 36
        versionCode = checkNotNull(configuredVersionCode)
        versionName = configuredVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en", "zh", "b+zh+Hant", "ko")
        vectorDrawables.useSupportLibrary = true
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(checkNotNull(releaseSigning["NATSUKO_KEYSTORE"]))
                storePassword = releaseSigning["NATSUKO_STORE_PASSWORD"]
                keyAlias = releaseSigning["NATSUKO_KEY_ALIAS"]
                keyPassword = releaseSigning["NATSUKO_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // All supported languages remain available when switching without a network connection.
    bundle { language { enableSplit = false } }

    packaging {
        // Retain upstream license resources; readable copies also live in About assets.
        resources.merges += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE*", "META-INF/NOTICE*")
        jniLibs {
            // Android installs these embedded PIE programs in its executable native library directory.
            useLegacyPackaging = true
            keepDebugSymbols += "**/libnatsuko_*.so"
        }
    }

    sourceSets["main"].jniLibs.srcDir(rootProject.file("third_party/ffmpeg/jniLibs"))
    sourceSets["main"].assets.srcDir(rootProject.file("third_party/ffmpeg/notices"))
    sourceSets["main"].jniLibs.srcDir(rootProject.file("third_party/metadata/jniLibs"))
    sourceSets["main"].assets.srcDir(rootProject.file("third_party/metadata/assets"))
    sourceSets["main"].assets.srcDir(rootProject.file("third_party/metadata/notices"))

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Source-catalog tests must rerun even when only a translation value changes.
tasks.withType<Test>().configureEach {
    inputs.files(fileTree("src/main/res") {
        include("values*/**/*.xml", "xml/locale_config.xml")
    }).withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

// Offline license catalog generated from each variant's resolved runtime artifacts.
apply(from = "open-source.gradle")
