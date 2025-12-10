package org.example

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import javax.inject.Inject

abstract class ValidateEnvTask @Inject constructor(
    private val providers: ProviderFactory
) : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val directoriesToScan: Property<FileCollection>

    @get:Input
    abstract val ignoreVariables: ListProperty<String>

    @get:Input
    abstract val ignoreSpringDefaults: Property<Boolean>

    // Regex for Spring: Matches ${VAR_NAME} and captures VAR_NAME.
    // Group 1: Name, Group 2: (Optional) Default value part including colon
    private val springRegex = "\\$\\{([A-Z0-9_]+)(:.*?)?\\}".toRegex()

    // Regex for Java/Kotlin: Matches System.getenv("VAR_NAME")
    private val codeRegex = "System\\.getenv\\(\\s*[\"']([A-Z0-9_]+)[\"']\\s*\\)".toRegex()

    @TaskAction
    fun validate() {
        val foundVariables = mutableSetOf<String>()
        val ignoredVars = ignoreVariables.get().toSet()
        val shouldIgnoreDefaults = ignoreSpringDefaults.get()

        // 1. SCANNING PHASE
        directoriesToScan.get().asFileTree.forEach { file ->
            if (file.extension in listOf("yml", "yaml", "properties")) {
                scanConfigFile(file, foundVariables, shouldIgnoreDefaults)
            } else if (file.extension in listOf("java", "kt")) {
                scanCodeFile(file, foundVariables)
            }
        }

        // Remove ignored variables
        val varsToCheck = foundVariables.filter { !ignoredVars.contains(it) }

        // 2. VALIDATION PHASE
        val missingVars = mutableListOf<String>()

        varsToCheck.forEach { varName ->
            val envVar = providers.environmentVariable(varName).orNull
            if (envVar.isNullOrBlank()) {
                missingVars.add(varName)
            }
        }

        // 3. REPORTING PHASE
        if (missingVars.isNotEmpty()) {
            throw GradleException(
                """
                |❌ Environment Validation Failed!
                |The following variables were detected in your project but are missing from your environment:
                |${missingVars.joinToString("\n") { " - $it" }}
                """.trimMargin()
            )
        } else {
            println("✅ Environment validated. Scanned ${varsToCheck.size} variables.")
        }
    }

    private fun scanConfigFile(file: File, foundVariables: MutableSet<String>, ignoreDefaults: Boolean) {
        val text = file.readText()
        springRegex.findAll(text).forEach { match ->
            val varName = match.groupValues[1]
            val hasDefault = match.groupValues[2].isNotEmpty()

            // Only add if we aren't ignoring defaults, or if it doesn't have a default
            if (!ignoreDefaults || !hasDefault) {
                foundVariables.add(varName)
            }
        }
    }

    private fun scanCodeFile(file: File, foundVariables: MutableSet<String>) {
        val text = file.readText()
        codeRegex.findAll(text).forEach { match ->
            foundVariables.add(match.groupValues[1])
        }
    }
}