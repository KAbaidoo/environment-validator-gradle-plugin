package org.example

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
*   The EnvValidatorExtension class defines configuration options for the environment variable
*   validation plugin. It allows users to specify additional directories to scan for environment
*   variable references, which variables to ignore during validation, and whether to ignore
*   variables that have default values defined in Spring configuration files.
 *
 *   envValidator {
 *       directoriesToScan.from(`config/`)
 *       ignoreVariables.add("OPTIONAL_VAR")
 *       ignoreSpringDefaults.set(true)
 *   }
*/

abstract class EnvValidatorExtension @Inject constructor(objects: ObjectFactory) {
    // Files/Directories to scan (e.g., src/main/resources, src/main/java)
     val directoriesToScan: ConfigurableFileCollection = objects.fileCollection()

    // Variables to ignore (e.g. false positives or optional ones)
     val ignoreVariables: ListProperty<String> = objects.listProperty(String::class.java)

    // If true, variables with default values in Spring (e.g. ${VAR:default}) are considered optional
     val ignoreSpringDefaults: Property<Boolean> = objects.property(Boolean::class.java)
}