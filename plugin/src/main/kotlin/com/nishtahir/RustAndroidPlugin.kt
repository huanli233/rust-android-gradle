package com.nishtahir

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.internal.tasks.factory.dependsOn
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import java.io.File
import java.io.Serializable
import java.util.*

const val RUST_TASK_GROUP = "rust"

// ... [Toolchain, Ndk, and other data classes remain unchanged] ...
// I've omitted the data classes like Toolchain and Ndk for brevity,
// as they don't require changes. Please keep them in your file.
data class Ndk(val path: File, val version: String) : Serializable {
    val versionMajor: Int
        get() = version.split(".").first().toInt()
}

data class Toolchain(val platform: String,
                     val type: ToolchainType,
                     val target: String,
                     val compilerTriple: String,
                     val binutilsTriple: String,
                     val folder: String) : java.io.Serializable {

    fun cc(apiLevel: Int): File =
        if (System.getProperty("os.name").startsWith("Windows")) {
            if (type == ToolchainType.ANDROID_PREBUILT) {
                File("bin", "$compilerTriple$apiLevel-clang.cmd")
            } else {
                File("$platform-$apiLevel/bin", "$compilerTriple-clang.cmd")
            }
        } else {
            if (type == ToolchainType.ANDROID_PREBUILT) {
                File("bin", "$compilerTriple$apiLevel-clang")
            } else {
                File("$platform-$apiLevel/bin", "$compilerTriple-clang")
            }
        }

    fun cxx(apiLevel: Int): File =
        if (System.getProperty("os.name").startsWith("Windows")) {
            if (type == ToolchainType.ANDROID_PREBUILT) {
                File("bin", "$compilerTriple$apiLevel-clang++.cmd")
            } else {
                File("$platform-$apiLevel/bin", "$compilerTriple-clang++.cmd")
            }
        } else {
            if (type == ToolchainType.ANDROID_PREBUILT) {
                File("bin", "$compilerTriple$apiLevel-clang++")
            } else {
                File("$platform-$apiLevel/bin", "$compilerTriple-clang++")
            }
        }

    fun ar(apiLevel: Int, ndkVersionMajor: Int): File =
        if (ndkVersionMajor >= 23) {
            File("bin", "llvm-ar")
        } else if (type == ToolchainType.ANDROID_PREBUILT) {
            File("bin", "$binutilsTriple-ar")
        } else {
            File("$platform-$apiLevel/bin", "$binutilsTriple-ar")
        }
}

enum class ToolchainType {
    ANDROID_PREBUILT,
    ANDROID_GENERATED,
    DESKTOP,
}

// See https://forge.rust-lang.org/platform-support.html.
val toolchains = listOf(
    Toolchain("linux-x86-64",
        ToolchainType.DESKTOP,
        "x86_64-unknown-linux-gnu",
        "<compilerTriple>",
        "<binutilsTriple>",
        "desktop/linux-x86-64"),
    // This should eventually go away: the darwin-x86-64 target will supersede it.
    // https://github.com/mozilla/rust-android-gradle/issues/77
    Toolchain("darwin",
        ToolchainType.DESKTOP,
        "x86_64-apple-darwin",
        "<compilerTriple>",
        "<binutilsTriple>",
        "desktop/darwin"),
    Toolchain("darwin-x86-64",
        ToolchainType.DESKTOP,
        "x86_64-apple-darwin",
        "<compilerTriple>",
        "<binutilsTriple>",
        "desktop/darwin-x86-64"),
    Toolchain("darwin-aarch64",
        ToolchainType.DESKTOP,
        "aarch64-apple-darwin",
        "<compilerTriple>",
        "<binutilsTriple>",
        "desktop/darwin-aarch64"),
    Toolchain("win32-x86-64-msvc",
        ToolchainType.DESKTOP,
        "x86_64-pc-windows-msvc",
        "<compilerTriple>",
        "<binutilsTriple>",
        "desktop/win32-x86-64"),
    Toolchain("win32-x86-64-gnu",
        ToolchainType.DESKTOP,
        "x86_64-pc-windows-gnu",
        "<compilerTriple>",
        "<binutilsTriple>",
        "desktop/win32-x86-64"),
    Toolchain("arm",
        ToolchainType.ANDROID_GENERATED,
        "armv7-linux-androideabi",
        "arm-linux-androideabi",
        "arm-linux-androideabi",
        "android/armeabi-v7a"),
    Toolchain("arm64",
        ToolchainType.ANDROID_GENERATED,
        "aarch64-linux-android",
        "aarch64-linux-android",
        "aarch64-linux-android",
        "android/arm64-v8a"),
    Toolchain("x86",
        ToolchainType.ANDROID_GENERATED,
        "i686-linux-android",
        "i686-linux-android",
        "i686-linux-android",
        "android/x86"),
    Toolchain("x86_64",
        ToolchainType.ANDROID_GENERATED,
        "x86_64-linux-android",
        "x86_64-linux-android",
        "x86_64-linux-android",
        "android/x86_64"),
    Toolchain("arm",
        ToolchainType.ANDROID_PREBUILT,
        "armv7-linux-androideabi",  // This is correct.  "Note: For 32-bit ARM, the compiler is prefixed with
        "armv7a-linux-androideabi", // armv7a-linux-androideabi, but the binutils tools are prefixed with
        "arm-linux-androideabi",    // arm-linux-androideabi. For other architectures, the prefixes are the same
        "android/armeabi-v7a"),     // for all tools."  (Ref: https://developer.android.com/ndk/guides/other_build_systems#overview )
    Toolchain("arm64",
        ToolchainType.ANDROID_PREBUILT,
        "aarch64-linux-android",
        "aarch64-linux-android",
        "aarch64-linux-android",
        "android/arm64-v8a"),
    Toolchain("x86",
        ToolchainType.ANDROID_PREBUILT,
        "i686-linux-android",
        "i686-linux-android",
        "i686-linux-android",
        "android/x86"),
    Toolchain("x86_64",
        ToolchainType.ANDROID_PREBUILT,
        "x86_64-linux-android",
        "x86_64-linux-android",
        "x86_64-linux-android",
        "android/x86_64")
)


@Suppress("unused")
open class RustAndroidPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val cargoExtension = project.extensions.create("cargo", CargoExtension::class.java, project)

        // Use the new AndroidComponentsExtension to configure variants
        val componentsExtension = project.extensions.findByType(AndroidComponentsExtension::class.java)
        if (componentsExtension == null) {
            project.logger.warn("RustAndroidPlugin: Android Gradle Plugin not found. Rust build will not be configured.")
            return
        }

        componentsExtension.onVariants { variant ->
            // A master task to build all targets for the current variant
            val cargoBuildVariantTask = project.tasks.register("cargoBuild${variant.name.capitalize()}") {
                it.group = RUST_TASK_GROUP
                it.description = "Builds all Rust targets for the ${variant.name} variant"
            }

            val cargo = project.extensions.getByType(CargoExtension::class.java)
            cargo.localProperties = Properties().apply {
                val localPropertiesFile = project.rootProject.file("local.properties")
                if (localPropertiesFile.exists()) {
                    load(localPropertiesFile.inputStream())
                }
            }

            // Validate mandatory properties
            if (cargo.module == null || cargo.libname == null) {
                throw GradleException("`cargo.module` and `cargo.libname` properties must be set.")
            }

            // Allow override of targets in local.properties
            val localTargets = cargo.localProperties.getProperty("rust.targets.${project.name}")
                ?: cargo.localProperties.getProperty("rust.targets")
            if (localTargets != null) {
                cargo.targets = localTargets.split(',').map { it.trim() }
            }
            if (cargo.targets == null) {
                throw GradleException("`cargo.targets` must be set.")
            }

            // Determine NDK info from the old extension for now
            val androidExtension = project.extensions.getByType(BaseExtension::class.java)
            val ndk = androidExtension.ndkDirectory.let {
                val ndkSourceProperties = Properties()
                val ndkSourcePropertiesFile = File(it, "source.properties")
                if (ndkSourcePropertiesFile.exists()) {
                    ndkSourceProperties.load(ndkSourcePropertiesFile.inputStream())
                }
                val ndkVersion = ndkSourceProperties.getProperty("Pkg.Revision", "0.0")
                Ndk(path = it, version = ndkVersion)
            }

            // Fish linker wrapper scripts from our Java resources.
            if (!project.rootProject.tasks.names.contains("generateLinkerWrapper")) {
                project.rootProject.tasks.register("generateLinkerWrapper", GenerateLinkerWrapperTask::class.java) { task ->
                    task.group = RUST_TASK_GROUP
                    task.description = "Generate shared linker wrapper script"
                    task.from(project.rootProject.zipTree(File(RustAndroidPlugin::class.java.protectionDomain.codeSource.location.toURI()).path))
                    task.include("**/linker-wrapper*")
                    task.into(File(project.rootProject.buildDir, "linker-wrapper"))
                    task.eachFile { it.path = it.path.replaceFirst("com/nishtahir/", "") }
                    task.fileMode = 493 // 0755
                    task.includeEmptyDirs = false
                    task.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                }
            }

            val generateLinkerWrapperProvider = project.rootProject.tasks.named("generateLinkerWrapper")

            val rustJniLibsDir = project.layout.buildDirectory.dir("intermediates/rustJniLibs/${variant.name}")

            cargo.targets!!.forEach { target ->
                val toolchain = toolchains.find { it.platform == target }
                    ?: throw GradleException("Target '$target' is not a recognized toolchain.")

                // Extract the pure ABI name (e.g., "arm64-v8a").
                val abi = toolchain.folder.substringAfterLast('/')

                // Give each task its own unique output directory.
                val taskOutDir = project.layout.buildDirectory.dir("intermediates/rustJniLibs/${variant.name}/${target}")

                val taskName = "cargoBuild${variant.name.replaceFirstChar { it.uppercase() }}${target.replaceFirstChar { it.uppercase() }}"

                val cargoBuildTask = project.tasks.register(taskName, CargoBuildTask::class.java) { task ->
                    task.group = RUST_TASK_GROUP
                    task.description = "Build Rust library for ${variant.name} variant, target $target"

                    // Pass the ABI name to the task.
                    task.abi.set(abi)
                    task.outputDirectory.set(taskOutDir)

                    // ... (set other properties like libname, module, profile, etc. as before) ...
                    task.libname.set(cargo.libname)
                    task.module.set(cargo.module)
                    task.extraCargoBuildArguments.set(cargo.extraCargoBuildArguments)
                    task.profile.set(if (variant.buildType == "release" || !variant.debuggable) "release" else "debug")
                    task.toolchain.set(toolchain)
                    task.ndk.set(ndk)

                    task.dependsOn(generateLinkerWrapperProvider)
                }

                // Add each task's output directory as a source. This now works because the
                // task itself creates the required ABI subfolder inside its output.
                variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuildTask, CargoBuildTask::outputDirectory)

                cargoBuildVariantTask.dependsOn(cargoBuildTask)
            }
        }
    }
}