import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a hot composable is not `restartable skippable` in the Compose compiler report (ORIGINAL).
 *
 * Reads the `*-composables.txt` files in the reports directory, written by a compile run with `-PcomposeReports`. A declaration looks like
 * `restartable skippable scheme("...") fun dev.rovsti.design.ios.component.IosToggle(`; the parameters follow on the
 * next lines. [hot] holds simple names; every overload of a name must be restartable and skippable, and a name that
 * is not in the report at all fails too (so a rename cannot silently drop a composable from the gate).
 */
abstract class CheckComposeStabilityTask : DefaultTask() {
    init {
        outputs.upToDateWhen { false }
    }

    // Internal, not an input directory: the directory may legitimately be missing (no -PcomposeReports compile yet),
    // and check() then fails with an actionable message instead of a configuration-time validation error.
    @get:Internal
    abstract val reports: DirectoryProperty

    @get:Input
    abstract val hot: ListProperty<String>

    @TaskAction
    fun check() {
        val files =
            reports
                .get()
                .asFile
                .listFiles { f -> f.name.endsWith("-composables.txt") }
                .orEmpty()
        if (files.isEmpty()) {
            throw GradleException(
                "No compose report in ${reports.get().asFile}. Run with -PcomposeReports=true " +
                    "(scripts/kotlin-ui-check.sh does).",
            )
        }
        val decl = Regex("""^((?:[a-z]+ )*)(?:scheme\(".*?"\) )?fun ([\w.]+)\(""", RegexOption.MULTILINE)
        val seen = mutableMapOf<String, MutableList<String>>()
        val bad = mutableListOf<String>()
        for (file in files) {
            for (m in decl.findAll(file.readText())) {
                val flags =
                    m.groupValues[1]
                        .trim()
                        .split(' ')
                        .toSet()
                val full = m.groupValues[2]
                val simple = full.substringAfterLast('.')
                if (simple !in hot.get()) continue
                seen.getOrPut(simple) { mutableListOf() }.add(full)
                if ("restartable" !in flags || "skippable" !in flags) {
                    bad += "$full is not restartable skippable (flags: ${flags.filter { it.isNotEmpty() }})"
                }
            }
        }
        val missing = hot.get().filter { it !in seen }
        if (missing.isNotEmpty()) bad += "not found in the compose report (renamed or removed?): $missing"
        if (bad.isNotEmpty()) {
            throw GradleException(
                "Compose stability gate failed:\n  " + bad.joinToString("\n  ") +
                    "\nFix with @Immutable/@Stable models, ImmutableList, or android/compose_stability.conf.",
            )
        }
        logger.lifecycle(
            "checkComposeStability: ${seen.values.sumOf {
                it.size
            }} declarations of ${seen.size} hot composables are skippable",
        )
    }
}
