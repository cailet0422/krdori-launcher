import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.krdori.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.krdori.launcher"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            // Python 3.12 only ships 64-bit builds. x86_64 is for emulators.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // Commit of the ../krdori-v2 submodule (NatsuFlatWhite/krdori-v2). Update when bumping it.
        buildConfigField("String", "SERVER_REVISION", "\"7e6dc22\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload-only app: sign release builds with the debug key so they install directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    androidResources {
        // The bundled server archive is already deflated.
        noCompress += "zip"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            install("flask")
            install("websockets")
            install("protobuf>=6.33.5")
            install("pycryptodome")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("com.google.android.material:material:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

/**
 * Packs ../krdori-v2 (the upstream server source) into assets/krdori-server.zip.
 * The app extracts it to internal storage on first launch and after each app update.
 */
abstract class PackServerTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val serverFiles: ConfigurableFileCollection

    @get:Internal
    abstract val serverDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun pack() {
        val root = serverDir.get().asFile
        val outDir = outputDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()
        val files = serverFiles.files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        require(files.any { it.relativeTo(root).invariantSeparatorsPath == "server/servers/game_server.py" }) {
            "krdori-v2 server source not found in $root. Run: git submodule update --init"
        }
        ZipOutputStream(File(outDir, "krdori-server.zip").outputStream().buffered()).use { zip ->
            zip.setLevel(9)
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).apply { time = 0 })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}

val packServerAssets = tasks.register<PackServerTask>("packServerAssets") {
    val dir = rootProject.layout.projectDirectory.dir("krdori-v2")
    serverDir.set(dir)
    serverFiles.from(fileTree(dir) {
        // In a submodule checkout ".git" is a file, not a directory.
        exclude(".git", ".git/**", "state/**", "env/**", "**/__pycache__/**", "**/*.pyc")
    })
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(packServerAssets, PackServerTask::outputDir)
    }
}
