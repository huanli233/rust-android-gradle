package com.nishtahir

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.process.ExecSpec
import java.io.File
import java.util.*
import javax.inject.Inject

sealed class Features {
    class All() : Features()

    data class DefaultAnd(val featureSet: Set<String>) : Features()

    data class NoDefaultBut(val featureSet: Set<String>) : Features()
}

data class FeatureSpec(var features: Features? = null) {
    fun all() {
        this.features = Features.All()
    }

    fun defaultAnd(featureSet: Array<String>) {
        this.features = Features.DefaultAnd(featureSet.toSet())
    }

    fun noDefaultBut(featureSet: Array<String>) {
        this.features = Features.NoDefaultBut(featureSet.toSet())
    }
}

// `CargoExtension` is documented in README.md.
open class CargoExtension @Inject constructor(private val project: Project) {
    lateinit var localProperties: Properties

    var module: String? = null
    var libname: String? = null
    var targets: List<String>? = null
    var prebuiltToolchains: Boolean? = null
    var verbose: Boolean? = null
    var targetDirectory: String? = null
    var targetIncludes: Array<String>? = null
    // ... other properties like `profile`, `verbose`, etc.
    // The `profile` property is now set automatically from the variant,
    // so its value here acts as a default for non-Android tasks.
    var profile: String = "debug"
    var extraCargoBuildArguments: List<String>? = null
    var apiLevel: Int? = null
    var apiLevels: Map<String, Int> = mapOf()
    var generateBuildId: Boolean = false

    // It would be nice to use a receiver here, but there are problems interoperating with Groovy
    // and Kotlin that are just not worth working out.  Another JVM language, yet another dynamic
    // invoke solution :(
    var exec: ((ExecSpec, Toolchain) -> Unit)? = null

    var featureSpec: FeatureSpec = FeatureSpec()

    fun features(action: Action<FeatureSpec>) {
        action.execute(featureSpec)
    }

    val toolchainDirectory: File
        get() {
            // Share a single toolchain directory, if one is configured.  Prefer "local.properties"
            // to "ANDROID_NDK_TOOLCHAIN_DIR" to "$TMP/rust-android-ndk-toolchains".
            val local: String? = localProperties.getProperty("rust.androidNdkToolchainDir")
            if (local != null) {
                return File(local).absoluteFile
            }

            val globalDir: String? = System.getenv("ANDROID_NDK_TOOLCHAIN_DIR")
            if (globalDir != null) {
                return File(globalDir).absoluteFile
            }

            var defaultDir = File(System.getProperty("java.io.tmpdir"), "rust-android-ndk-toolchains")
            return defaultDir.absoluteFile
        }

    var cargoCommand: String = ""
        get() {
            return if (!field.isEmpty()) {
                field
            } else {
                getProperty("rust.cargoCommand", "RUST_ANDROID_GRADLE_CARGO_COMMAND") ?: "cargo"
            }
        }

    var rustupChannel: String = ""
        get() {
            return if (!field.isEmpty()) {
                field
            } else {
                getProperty("rust.rustupChannel", "RUST_ANDROID_GRADLE_RUSTUP_CHANNEL") ?: ""
            }
        }

    var pythonCommand: String = ""
        get() {
            return if (!field.isEmpty()) {
                field
            } else {
                getProperty("rust.pythonCommand", "RUST_ANDROID_GRADLE_PYTHON_COMMAND") ?: "python"
            }
        }

    // Required so that we can parse the default triple out of `rustc --version --verbose`. Sadly,
    // there seems to be no way to get this information out of cargo directly. Failure to locate
    // this isn't fatal, however.
    var rustcCommand: String = ""
        get() {
            return if (!field.isEmpty()) {
                field
            } else {
                getProperty("rust.rustcCommand", "RUST_ANDROID_GRADLE_RUSTC_COMMAND") ?: "rustc"
            }
        }

    fun getFlagProperty(camelCaseName: String, snakeCaseName: String, ifUnset: Boolean): Boolean {
        val propVal = getProperty(camelCaseName, snakeCaseName)
        if (propVal == "1" || propVal == "true") {
            return true
        }
        if (propVal == "0" || propVal == "false") {
            return false
        }
        if (propVal == null || propVal == "") {
            return ifUnset
        }
        throw GradleException("Illegal value for property \"$camelCaseName\" / \"$snakeCaseName\". Must be 0/1/true/false if set")
    }

    internal fun getProperty(camelCaseName: String, snakeCaseName: String): String? {
        val local: String? = localProperties.getProperty(camelCaseName)
        if (local != null) {
            return local
        }
        val global: String? = System.getenv(snakeCaseName)
        if (global != null) {
            return global
        }
        return null
    }
}
