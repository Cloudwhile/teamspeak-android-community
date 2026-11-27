import com.android.build.api.variant.FilterConfiguration
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val keystoreProps = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) {
        val raw = propsFile.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        load(raw.reader())
    }
}

fun signingProp(propKey: String, vararg envKeys: String): String? {
    keystoreProps.getProperty(propKey)?.takeIf { it.isNotBlank() }?.let { return it }
    for (envKey in envKeys) {
        System.getenv(envKey)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}

val resolvedVersionName: String = (findProperty("versionName") as? String)
    ?.trim()
    ?.removePrefix("v")
    ?.takeIf { it.isNotBlank() }
    ?: "0.1.0-alpha.2"

val resolvedVersionCode: Int = (findProperty("versionCode") as? String)
    ?.trim()
    ?.toIntOrNull()
    ?.takeIf { it > 0 }
    ?: 10002

val configuredTargetAbis: List<String> = ((findProperty("targetAbis") as? String)
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.takeIf { it.isNotEmpty() })
    ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")

val customReleaseKsFile: File? = signingProp("storeFile", "SIGNING_KEYSTORE_FILE", "TSCC_KEYSTORE_FILE")
    ?.let { path ->
        val direct = File(path)
        if (direct.isAbsolute) direct else rootProject.file(path)
    }
    ?.takeIf { it.exists() }

android {
    namespace = "com.cloudwhile.tscc"
    compileSdk = 34
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.cloudwhile.tscc"
        minSdk = 26
        targetSdk = 34
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        setProperty("archivesBaseName", "TSCC-v$resolvedVersionName")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*configuredTargetAbis.toTypedArray())
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (customReleaseKsFile != null) {
            create("release") {
                storeFile = customReleaseKsFile
                storePassword = signingProp("storePassword", "SIGNING_STORE_PASSWORD", "TSCC_KEYSTORE_PASSWORD")
                keyAlias = signingProp("keyAlias", "SIGNING_KEY_ALIAS", "TSCC_KEY_ALIAS")
                keyPassword = signingProp("keyPassword", "SIGNING_KEY_PASSWORD", "TSCC_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isDefault = true
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (customReleaseKsFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val abiVersionRanks = mapOf(
    "armeabi-v7a" to 1,
    "x86_64" to 2,
    "arm64-v8a" to 3
)

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abiName = output.filters
                .find { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
            val rank = if (abiName == null) 0 else (abiVersionRanks[abiName] ?: 0)
            output.versionCode.set(resolvedVersionCode * 10 + rank)
        }
    }
}

val buildRustJniLibs by tasks.registering(Exec::class) {
    val jniOutDir = layout.projectDirectory.dir("src/main/jniLibs").asFile
    val rustDir = rootProject.layout.projectDirectory.dir("rust-core").asFile
    val rustManifest = rootProject.layout.projectDirectory.file("rust-core/Cargo.toml").asFile
    val sdkDir = android.sdkDirectory
    val sdkCmakeBin = sdkDir.resolve("cmake/3.22.1/bin")
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val cmakeExe = sdkCmakeBin.resolve(if (isWindows) "cmake.exe" else "cmake")

    val arm64Toolchain = rustDir.resolve("cmake/android-arm64.cmake").absolutePath.replace('\\', '/')
    val armv7Toolchain = rustDir.resolve("cmake/android-armv7.cmake").absolutePath.replace('\\', '/')
    val x64Toolchain = rustDir.resolve("cmake/android-x86_64.cmake").absolutePath.replace('\\', '/')

    workingDir = rustDir
    inputs.property("targetAbis", configuredTargetAbis.joinToString(","))
    inputs.dir(rootProject.layout.projectDirectory.dir("rust-core/src"))
    inputs.dir(rootProject.layout.projectDirectory.dir("rust-core/vendor"))
    inputs.dir(rootProject.layout.projectDirectory.dir("rust-core/cmake"))
    inputs.file(rustManifest)
    inputs.file(rootProject.layout.projectDirectory.file("rust-core/Cargo.lock"))
    outputs.dir(jniOutDir)

    environment("OPUS_STATIC", "1")
    environment("LIBOPUS_STATIC", "1")
    environment("OPUS_NO_PKG", "1")
    environment("LIBOPUS_NO_PKG", "1")
    environment("CMAKE_POLICY_VERSION_MINIMUM", "3.5")
    environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath.replace('\\', '/'))
    environment("ANDROID_NDK", android.ndkDirectory.absolutePath.replace('\\', '/'))
    environment("CMAKE_GENERATOR", "Ninja")
    if (cmakeExe.exists()) {
        environment("CMAKE", cmakeExe.absolutePath)
        environment("PATH", "${sdkCmakeBin.absolutePath}${File.pathSeparator}${System.getenv("PATH") ?: ""}")
    }
    environment("CMAKE_TOOLCHAIN_FILE_aarch64_linux_android", arm64Toolchain)
    environment("CMAKE_TOOLCHAIN_FILE_aarch64-linux-android", arm64Toolchain)
    environment("CMAKE_TOOLCHAIN_FILE_armv7_linux_androideabi", armv7Toolchain)
    environment("CMAKE_TOOLCHAIN_FILE_armv7-linux-androideabi", armv7Toolchain)
    environment("CMAKE_TOOLCHAIN_FILE_x86_64_linux_android", x64Toolchain)
    environment("CMAKE_TOOLCHAIN_FILE_x86_64-linux-android", x64Toolchain)

    val cargoArgs = mutableListOf("cargo", "ndk")
    configuredTargetAbis.forEach { abi ->
        cargoArgs.add("-t")
        cargoArgs.add(abi)
    }
    cargoArgs.addAll(
        listOf(
            "--platform", "26",
            "-o", jniOutDir.absolutePath,
            "build",
            "--release"
        )
    )
    commandLine(cargoArgs)
}

tasks.matching { it.name == "mergeDebugJniLibFolders" || it.name == "mergeReleaseJniLibFolders" }
    .configureEach {
        dependsOn(buildRustJniLibs)
    }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.stream.webrtc.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.ui.tooling)
}
