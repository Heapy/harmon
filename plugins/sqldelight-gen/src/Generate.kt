package dev.yoda.harmon.sqldelight

import app.cash.sqldelight.core.SqlDelightCompilationUnit
import app.cash.sqldelight.core.SqlDelightDatabaseName
import app.cash.sqldelight.core.SqlDelightDatabaseProperties
import app.cash.sqldelight.core.SqlDelightEnvironment
import app.cash.sqldelight.core.SqlDelightSourceFolder
import app.cash.sqldelight.dialect.api.SqlDelightDialect
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.io.File
import java.nio.file.Path
import java.util.ServiceLoader

/**
 * Kotlin Toolchain task action that runs SQLDelight's code generation over the module's `.sq`
 * files and writes typed Kotlin into [generatedSourceDir], which the toolchain then compiles into
 * the enabling module via `generated.sources` (see plugin.yaml).
 *
 * SQLDelight only ships a Gradle plugin, and the Kotlin Toolchain has no Gradle plugins — so the
 * compiler is driven directly. The heavy lifting (mocking a headless IntelliJ environment to parse
 * `.sq` PSI and invoking `SqlDelightCompiler`) lives in the vendored, Gradle-free
 * [SqlDelightEnvironment]. Here we only build the input model Gradle would otherwise derive from
 * the project, and hand it the `.sq` source folder and the output directory.
 *
 * [deriveSchemaFromMigrations] is off and `verifyMigrations` is false, so `.sqm` files never
 * contribute and the generated `Schema.migrate()` stays empty. Schema evolution is hand-rolled in
 * the store instead; see docs/history.md.
 */
@TaskAction
fun generateDatabase(
    @Input sqDir: Path,
    @Output generatedSourceDir: Path,
) {
    // The task output dir is reused across builds, so clear it first: a removed or renamed .sq
    // must not leave an orphaned generated file behind to be compiled.
    val outDir = generatedSourceDir.toFile()
    outDir.deleteRecursively()
    outDir.mkdirs()

    val sqRoot = sqDir.toFile()
    val hasSqFiles = sqRoot.isDirectory &&
        sqRoot.walkTopDown().any { it.isFile && it.extension == "sq" }
    // Nothing to generate yet — a no-op beats failing the build.
    if (!hasSqFiles) return

    val compilationUnit = CompilationUnit(
        name = "main",
        sourceFolders = setOf(SourceFolder(folder = sqRoot, dependency = false)),
        outputDirectoryFile = outDir,
    )
    val properties = DatabaseProperties(
        packageName = DATABASE_PACKAGE,
        compilationUnits = listOf(compilationUnit),
        className = DATABASE_CLASS_NAME,
        dependencies = emptyList(),
        deriveSchemaFromMigrations = false,
        treatNullAsUnknownForEquality = false,
        rootDirectory = sqRoot,
        generateAsync = false,
        expandSelectStar = true,
    )

    // Dialects are discovered through ServiceLoader; sqlite-3-38-dialect registers SqliteDialect.
    val dialect: SqlDelightDialect = ServiceLoader
        .load(SqlDelightDialect::class.java, SqlDelightDialect::class.java.classLoader)
        .firstOrNull()
        ?: error(
            "No SqlDelightDialect found on the classpath " +
                "(expected app.cash.sqldelight:sqlite-3-38-dialect)",
        )

    val environment = SqlDelightEnvironment(
        properties = properties,
        compilationUnit = compilationUnit,
        verifyMigrations = false,
        dialect = dialect,
        moduleName = MODULE_NAME,
    )

    when (
        val status = environment.generateSqlDelightFiles { line ->
            println("[sqldelight-gen] $line")
        }
    ) {
        is SqlDelightEnvironment.CompilationStatus.Failure ->
            error("SQLDelight code generation failed:\n" + status.errors.joinToString("\n"))
        SqlDelightEnvironment.CompilationStatus.Success -> Unit
    }
}

/** Package of the generated database class and schema. */
private const val DATABASE_PACKAGE = "dev.yoda.harmon.db"

/** Generated database interface; its companion carries `Schema` and the `invoke` factory. */
private const val DATABASE_CLASS_NAME = "HarmonDatabase"

/** Sanitized module name SQLDelight uses in generated symbols. */
private const val MODULE_NAME = "harmon"

private data class SourceFolder(
    override val folder: File,
    override val dependency: Boolean,
) : SqlDelightSourceFolder

private data class CompilationUnit(
    override val name: String,
    override val sourceFolders: Set<SqlDelightSourceFolder>,
    override val outputDirectoryFile: File,
) : SqlDelightCompilationUnit

private data class DatabaseProperties(
    override val packageName: String,
    override val compilationUnits: List<SqlDelightCompilationUnit>,
    override val className: String,
    override val dependencies: List<SqlDelightDatabaseName>,
    override val deriveSchemaFromMigrations: Boolean,
    override val treatNullAsUnknownForEquality: Boolean,
    override val rootDirectory: File,
    override val generateAsync: Boolean,
    override val expandSelectStar: Boolean,
) : SqlDelightDatabaseProperties
