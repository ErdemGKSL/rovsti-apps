import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Runs `cargo` for the `rovsti_ffi` crate (architecture 6.6). Typed inputs (every Rust source, manifest and
 * lock file) and one output directory make a second run UP-TO-DATE; the task is configuration-cache safe
 * because it holds no `Project` reference and gets `ExecOperations` injected.
 */
abstract class CargoBuildTask
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        /** Rust sources, `Cargo.toml` files and `Cargo.lock` of the workspace. */
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val rustSources: ConfigurableFileCollection

        /** Whole command after `cargo`, for example `ndk --platform 26 ... build -p rovsti_ffi`. */
        @get:Input
        abstract val cargoArgs: ListProperty<String>

        /** Workspace root (where the root `Cargo.toml` lives). Not an input: [rustSources] covers its contents. */
        @get:Input
        abstract val workspaceDir: Property<String>

        @get:Input
        @get:Optional
        abstract val ndkHome: Property<String>

        @get:Input
        @get:Optional
        abstract val androidHome: Property<String>

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        /** Host build only: the `.so` to copy into [outputDir] after the build. */
        @get:Input
        @get:Optional
        abstract val copyFrom: Property<String>

        @TaskAction
        fun run() {
            val root = File(workspaceDir.get())
            val cargoBin = File(System.getProperty("user.home"), ".cargo/bin")
            exec.exec {
                workingDir = root
                commandLine(listOf("cargo") + cargoArgs.get())
                environment("PATH", "${cargoBin.absolutePath}${File.pathSeparator}${System.getenv("PATH").orEmpty()}")
                ndkHome.orNull?.let { environment("ANDROID_NDK_HOME", it) }
                androidHome.orNull?.let {
                    environment("ANDROID_HOME", it)
                    environment("ANDROID_SDK_ROOT", it)
                }
            }
            copyFrom.orNull?.let { relative ->
                val source = File(root, relative)
                val target = File(outputDir.get().asFile, source.name)
                target.parentFile.mkdirs()
                source.copyTo(target, overwrite = true)
            }
        }
    }

private fun Project.rustSourceTree(root: File): ConfigurableFileCollection =
    files(
        fileTree(File(root, "crates")) {
            include("**/*.rs", "**/Cargo.toml", "**/*.json")
        },
        File(root, "Cargo.toml"),
        File(root, "Cargo.lock"),
    )

private fun Project.androidHomeDir(): String? =
    providers.environmentVariable("ANDROID_HOME").orNull
        ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
        ?: "/opt/android-sdk".takeIf { File(it).isDirectory }

private fun Project.ndkHomeDir(): String? =
    providers.environmentVariable("ANDROID_NDK_HOME").orNull?.takeIf { File(it).isDirectory }
        ?: androidHomeDir()?.let { sdk ->
            File(sdk, "ndk").listFiles { f -> f.isDirectory }?.maxByOrNull { it.name }?.absolutePath
        }

/**
 * `rovsti.cargo`: `cargoBuildAndroid` (debug) and `cargoBuildAndroid<Variant>` per variant build `rovsti_ffi` for
 * arm64-v8a (plus x86_64 for debug) with cargo-ndk and feed the result to the variant's `jniLibs`; `cargoBuildHost` builds the
 * host library (with the `test-ops` feature) for the plain-JVM `testRustBridge` task.
 *
 * Platform 26 and not [RovstiSdk.MIN]: a library linked against a newer platform refuses to load on older devices,
 * and `scripts/android-env.sh` links for 26 too (docs/kotlin-ui/api-notes.md).
 */
class CargoConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val root = rootProject.projectDir.parentFile
            val sources = rustSourceTree(root)

            val host =
                tasks.register("cargoBuildHost", CargoBuildTask::class.java) {
                    group = "rust"
                    description =
                        "cargo build -p rovsti_ffi --features test-ops, copies the host .so to build/rustHostLib."
                    rustSources.from(sources)
                    workspaceDir.set(root.absolutePath)
                    cargoArgs.set(listOf("build", "-p", "rovsti_ffi", "--features", "test-ops"))
                    copyFrom.set("target/debug/librovsti_ffi.so")
                    outputDir.set(layout.buildDirectory.dir("rustHostLib"))
                }
            extensions.extraProperties["rustHostLibDir"] = host.flatMap { it.outputDir }

            extensions.getByType(AndroidComponentsExtension::class.java).let { components ->
                (components as LibraryAndroidComponentsExtension).onVariants { variant ->
                    val release = variant.buildType == "release"
                    val suffix = variant.name.replaceFirstChar { it.uppercase() }
                    val name = if (variant.name == "debug") "cargoBuildAndroid" else "cargoBuildAndroid$suffix"
                    val task =
                        tasks.register(name, CargoBuildTask::class.java) {
                            group = "rust"
                            description =
                                "cargo ndk build of rovsti_ffi for arm64-v8a${if (release) "" else " and x86_64"} (${variant.name})."
                            rustSources.from(sources)
                            workspaceDir.set(root.absolutePath)
                            androidHome.set(provider { androidHomeDir() })
                            ndkHome.set(provider { ndkHomeDir() })
                            val out = layout.buildDirectory.dir("rustJniLibs/${variant.name}")
                            outputDir.set(out)
                            cargoArgs.set(
                                listOf("ndk", "--platform", "26", "-t", "arm64-v8a") +
                                    (if (release) emptyList() else listOf("-t", "x86_64")) +
                                    listOf("-o", out.get().asFile.absolutePath, "build", "-p", "rovsti_ffi") +
                                    if (release) listOf("--release") else emptyList(),
                            )
                        }
                    variant.sources.jniLibs?.addGeneratedSourceDirectory(task, CargoBuildTask::outputDir)
                }
            }
        }
    }
}
