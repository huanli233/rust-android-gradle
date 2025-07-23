package com.nishtahir

import com.android.build.gradle.BaseExtension
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.LogLevel
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.ByteArrayOutputStream
import java.io.File

abstract class CargoBuildTask : DefaultTask() {

    @get:Input
    abstract val abi: Property<String>

    @get:Input
    abstract val libname: Property<String>

    @get:Input
    abstract val module: Property<String>

    @get:Input
    abstract val profile: Property<String>

    @get:Input
    abstract val toolchain: Property<Toolchain>

    @get:Input
    abstract val ndk: Property<Ndk>

    @get:Input
    @get:Optional
    abstract val extraCargoBuildArguments: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        val cargoExtension = project.extensions.getByType(CargoExtension::class.java)
        val toolchain = this.toolchain.get()
        val ndk = this.ndk.get()
        val profile = this.profile.get()
        val libname = this.libname.get()
        val module = this.module.get()

        // Get the API level for the current toolchain
        val apiLevel = cargoExtension.apiLevels[toolchain.platform]
            ?: project.extensions.getByType(BaseExtension::class.java).defaultConfig.minSdk
            ?: throw GradleException("apiLevel for ${toolchain.platform} is not set in cargo.apiLevels")

        // ----------------------------------------------------------------
        // 1. Execute the `cargo build` command
        // ----------------------------------------------------------------
        project.exec { spec ->
            val moduleDir = project.file(module)
            spec.workingDir = moduleDir.canonicalFile
            spec.standardOutput = System.out
            spec.errorOutput = System.err

            val command = mutableListOf(cargoExtension.cargoCommand)

            if (cargoExtension.rustupChannel.isNotEmpty()) {
                command.add(if (cargoExtension.rustupChannel.startsWith("+")) "" else "+")
                command.add(cargoExtension.rustupChannel)
            }

            command.add("build")

            if (cargoExtension.verbose ?: project.logger.isEnabled(LogLevel.INFO)) {
                command.add("--verbose")
            }

            // Add features based on the extension configuration
            when (val features = cargoExtension.featureSpec.features) {
                is Features.All -> command.add("--all-features")
                is Features.DefaultAnd -> {
                    if (features.featureSet.isNotEmpty()) {
                        command.add("--features")
                        command.add(features.featureSet.joinToString(" "))
                    }
                }
                is Features.NoDefaultBut -> {
                    command.add("--no-default-features")
                    if (features.featureSet.isNotEmpty()) {
                        command.add("--features")
                        command.add(features.featureSet.joinToString(" "))
                    }
                }
                null -> Unit
            }


            // Use the profile set for this task ('release' or 'debug')
            if (profile == "release") {
                command.add("--release")
            }

            val defaultTargetTriple = getDefaultTargetTriple(project, cargoExtension.rustcCommand)
            if (toolchain.target != defaultTargetTriple) {
                command.add("--target=${toolchain.target}")
            }

            // Add any extra arguments
            this.extraCargoBuildArguments.getOrNull()?.let {
                command.addAll(it)
            }

            spec.commandLine = command

            // ----------------------------------------------------------------
            // Environment variable setup for cross-compilation
            // ----------------------------------------------------------------

            if (toolchain.type != ToolchainType.DESKTOP) {
                val toolchainTarget = toolchain.target.toUpperCase().replace('-', '_')
                val ndkPath = ndk.path
                val ndkVersionMajor = ndk.versionMajor

                // Find the NDK toolchain directory
                val hostTag = when {
                    Os.isFamily(Os.FAMILY_WINDOWS) && (Os.isArch("x86_64") || Os.isArch("amd64")) -> "windows-x86_64"
                    Os.isFamily(Os.FAMILY_WINDOWS) -> "windows"
                    Os.isFamily(Os.FAMILY_MAC) -> "darwin-x86_64"
                    else -> "linux-x86_64"
                }
                val toolchainDir = File(ndkPath, "toolchains/llvm/prebuilt/$hostTag")

                // Set up the linker wrapper script
                val linkerWrapper = if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                    project.rootProject.file("build/linker-wrapper/linker-wrapper.bat")
                } else {
                    project.rootProject.file("build/linker-wrapper/linker-wrapper.sh")
                }
                spec.environment("CARGO_TARGET_${toolchainTarget}_LINKER", linkerWrapper.absolutePath)

                // Set compiler and archiver paths for `cc-rs` build scripts
                val cc = File(toolchainDir, toolchain.cc(apiLevel).path).absolutePath
                val cxx = File(toolchainDir, toolchain.cxx(apiLevel).path).absolutePath
                val ar = File(toolchainDir, toolchain.ar(apiLevel, ndkVersionMajor).path).absolutePath

                spec.environment("CC_${toolchain.target}", cc)
                spec.environment("CXX_${toolchain.target}", cxx)
                spec.environment("AR_${toolchain.target}", ar)

                // Configure `clang-sys`
                spec.environment("CLANG_PATH", cc)

                // Configure the linker wrapper itself
                spec.environment("RUST_ANDROID_GRADLE_PYTHON_COMMAND", cargoExtension.pythonCommand)
                spec.environment("RUST_ANDROID_GRADLE_LINKER_WRAPPER_PY",
                    project.rootProject.file("build/linker-wrapper/linker-wrapper.py").absolutePath)
                spec.environment("RUST_ANDROID_GRADLE_CC", cc)
                val soname = "-Wl,-soname,lib$libname.so"
                val linkArg = if (cargoExtension.generateBuildId) "-Wl,--build-id,$soname" else soname
                spec.environment("RUST_ANDROID_GRADLE_CC_LINK_ARG", linkArg)
            }
        }.assertNormalExitValue()

        // ----------------------------------------------------------------
        // 2. Copy the final library to the AGP-visible output directory
        // ----------------------------------------------------------------

        // Determine where Cargo placed the built artifacts
        val cargoTargetRoot = cargoExtension.localProperties.getProperty("rust.cargoTargetDir")
            ?: System.getenv("CARGO_TARGET_DIR")
            ?: cargoExtension.targetDirectory
            ?: File(module, "target").path

        val defaultTargetTriple = getDefaultTargetTriple(project, cargoExtension.rustcCommand)
        val cargoBuildOutputDir = File(cargoTargetRoot,
            if (toolchain.target == defaultTargetTriple) {
                profile
            } else {
                "${toolchain.target}/$profile"
            }
        )

        project.copy { spec ->
            val abiName = this.abi.get()
            val finalDest = this.outputDirectory.get().asFile.resolve(abiName)

            spec.from(cargoBuildOutputDir)
            // Place the .so file inside the correct ABI subdirectory
            spec.into(finalDest)

            // Include the correct library files
            val targetIncludes = cargoExtension.targetIncludes
            if (targetIncludes != null && targetIncludes.isNotEmpty()) {
                spec.include(targetIncludes.asIterable())
            } else {
                spec.include("lib$libname.so", "lib$libname.dylib", "$libname.dll")
            }
        }
    }
}

private fun getDefaultTargetTriple(project: Project, rustc: String): String? {
    val stdout = ByteArrayOutputStream()
    val result = project.exec { spec ->
        spec.commandLine = listOf(rustc, "--version", "--verbose")
        spec.standardOutput = stdout
        spec.isIgnoreExitValue = true // Prevent build failure if rustc isn't found
    }

    if (result.exitValue != 0) {
        project.logger.warn("Could not determine default rust target triple. `rustc --version --verbose` returned ${result.exitValue}")
        return null
    }

    return stdout.toString().lines()
        .find { it.startsWith("host:") }
        ?.substringAfter("host:")
        ?.trim()
}