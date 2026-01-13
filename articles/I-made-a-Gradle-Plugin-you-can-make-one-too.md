# I made a Gradle Plugin, you can make one too!

You know that feeling when you've spent hours debugging your app, only to realize it was a missing environment variable? Yeah, me too. That's exactly what pushed me to build my first Gradle plugin—and honestly? It wasn't as scary as I thought it'd be.

Let me walk you through how I created an environment validator plugin for Gradle, and more importantly, show you that building your own plugin isn't some mystical art reserved for framework wizards. It's actually pretty accessible once you break it down.

## Why I Even Bothered

Here's the thing: I kept running into the same annoying problem. My Spring Boot applications would compile just fine, pass all their tests, and then—boom—crash in production because `DATABASE_URL` wasn't set. Or `API_KEY` was missing. You get the picture.

Sure, I could've just been more careful. Maybe kept a checklist somewhere. But that's boring, and humans are terrible at remembering checklists. So I thought, "What if Gradle could just... check this stuff for me?"

That's when I realized I needed to learn how to make a Gradle plugin.

## What Even Is a Gradle Plugin?

Before we go further, let's level-set. A Gradle plugin is basically a reusable piece of build logic. Think of it like a recipe you can share with other projects. Instead of copying and pasting the same build configuration everywhere, you package it up into a plugin.

Gradle already comes with tons of plugins—the Java plugin, the Kotlin plugin, the Android plugin. They're all just code that hooks into Gradle's build lifecycle and does specific things. Your plugin can do the same.

The beauty? You write it once, and every project that needs it can just apply it with a single line in their `build.gradle` file.

## Setting Up: Not as Painful as You'd Think

I started with Gradle's built-in plugin template. If you've got Gradle installed (and let's be real, if you're reading this, you probably do), you can generate a plugin project skeleton with:

```bash
gradle init
```

When prompted, I selected:
- Type of project: Gradle plugin
- Language: Kotlin (because I'm more comfortable with it than Groovy)
- Build script DSL: Kotlin

Boom. Gradle generated everything I needed: a basic plugin class, a test structure, even example code. It's like having training wheels, but the good kind that actually help you learn.

## The Core Pieces (It's Just Three Files, Really)

My plugin ended up having three main components. Let me break them down:

### 1. The Plugin Class

This is the entry point—where Gradle hooks in when someone applies your plugin. Mine is called `EnvValidatorPlugin`, and it does three things:

- Creates an extension (more on that in a sec)
- Registers a task that does the actual validation
- Wires everything together so the task runs at the right time

Here's the skeleton:

```kotlin
class EnvValidatorPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Create configuration
        val extension = project.extensions.create("envValidator", EnvValidatorExtension::class.java)
        
        // Register the task
        project.tasks.register("validateEnvironment", ValidateEnvTask::class.java) {
            // Configure task properties
        }
        
        // Hook into build lifecycle
        project.afterEvaluate {
            // Make sure validation runs before compilation
        }
    }
}
```

The `Plugin<Project>` interface requires just one method: `apply()`. That's where all the magic happens.

### 2. The Extension (Configuration for Users)

An extension is how users configure your plugin in their `build.gradle` file. It's basically a data holder with properties that users can set.

For my environment validator, I needed users to tell me:
- Which directories to scan for environment variable references
- Which variables to ignore (maybe some are truly optional)
- Whether to be lenient with Spring's default values (like `${VAR:default}`)

So I created `EnvValidatorExtension`:

```kotlin
abstract class EnvValidatorExtension @Inject constructor(objects: ObjectFactory) {
    val directoriesToScan: ConfigurableFileCollection = objects.fileCollection()
    val ignoreVariables: ListProperty<String> = objects.listProperty(String::class.java)
    val ignoreSpringDefaults: Property<Boolean> = objects.property(Boolean::class.java)
}
```

Now users can configure my plugin like this:

```groovy
envValidator {
    directoriesToScan.from('config/')
    ignoreVariables.add('OPTIONAL_VAR')
    ignoreSpringDefaults.set(true)
}
```

Clean, right?

### 3. The Task (Where the Work Happens)

Tasks are the workhorses of Gradle. They're where your actual logic lives. My `ValidateEnvTask` does the heavy lifting:

1. Scans specified directories for environment variable references
2. Checks if those variables are actually set
3. Fails the build if anything's missing

I used regex patterns to find environment variables in different file types:
- Spring config files (`.yml`, `.properties`): `${VAR_NAME}` or `${VAR_NAME:default}`
- Java/Kotlin code: `System.getenv("VAR_NAME")`

The task extends `DefaultTask` and has a method annotated with `@TaskAction` that runs when the task executes:

```kotlin
@TaskAction
fun validate() {
    val foundVariables = mutableSetOf<String>()
    
    // Scan files
    directoriesToScan.get().asFileTree.forEach { file ->
        if (file.extension in listOf("yml", "yaml", "properties")) {
            scanConfigFile(file, foundVariables)
        } else if (file.extension in listOf("java", "kt")) {
            scanCodeFile(file, foundVariables)
        }
    }
    
    // Check environment
    val missingVars = foundVariables.filter { 
        providers.environmentVariable(it).orNull.isNullOrBlank() 
    }
    
    // Report
    if (missingVars.isNotEmpty()) {
        throw GradleException("Missing variables: ${missingVars.joinToString()}")
    }
}
```

## Hooking Into the Build Lifecycle

Here's where things get interesting. I wanted my validation to run automatically—before compilation starts. Otherwise, what's the point?

Gradle has this concept of task dependencies. You can say "Task B depends on Task A," which means A must run before B. Perfect for my needs.

In my plugin's `apply()` method, I used `project.afterEvaluate` to set up dependencies after the project configuration is complete:

```kotlin
project.afterEvaluate {
    val validateTask = it.tasks.named("validateEnvironment")
    
    it.tasks.findByName("processResources")?.dependsOn(validateTask)
    it.tasks.findByName("compileKotlin")?.dependsOn(validateTask)
    it.tasks.findByName("compileJava")?.dependsOn(validateTask)
}
```

Now when someone runs `gradle build`, my validation happens first. If environment variables are missing, the build fails fast—before wasting time compiling anything.

## Testing: Easier Than You'd Expect

Gradle provides `GradleRunner` for testing plugins. You create a temporary project, apply your plugin, run a build, and assert the results.

My functional test looked like this:

```kotlin
@Test 
fun `can run task`() {
    // Create test project
    settingsFile.writeText("")
    buildFile.writeText("""
        plugins {
            id('java')
            id('com.example.env-validator')
        }
    """.trimIndent())
    
    // Run build
    val result = GradleRunner.create()
        .withPluginClasspath()
        .withArguments("build")
        .withProjectDir(projectDir)
        .build()
    
    // Verify
    assertTrue(result.output.contains("✅ Environment validated"))
}
```

The test sets up a minimal Gradle project, applies my plugin, runs a build, and checks the output. Simple, but effective.

## The Mistakes I Made (So You Don't Have To)

Let me be real with you—I messed up a few times along the way:

**Forgetting `@Input` annotations**: Gradle uses these to determine when tasks need to re-run. Without them, your task might not execute when it should, or worse, execute when it doesn't need to. Always annotate your task properties.

**Not setting defaults**: Users shouldn't have to configure everything. My plugin now defaults to scanning `src/main/resources` and `src/main/java`, which covers most use cases. Users can override if needed, but sensible defaults make adoption way easier.

**Ignoring the Provider API**: Gradle has this thing called the Provider API for lazy configuration. It lets you defer reading values until they're actually needed. I initially tried to read environment variables immediately, which broke configuration caching. Switching to `providers.environmentVariable()` fixed it.

**Making the plugin too opinionated**: My first version forced everyone to use my exact rules. That annoyed people. Adding configuration options (like `ignoreVariables` and `ignoreSpringDefaults`) made the plugin way more flexible and useful.

## Publishing (The Part I Was Most Scared Of)

I haven't published my plugin to the Gradle Plugin Portal yet, but here's what I learned about the process:

You need to:
1. Create an account at plugins.gradle.org
2. Add some metadata to your `build.gradle.kts` (plugin ID, description, website, etc.)
3. Set up API keys for authentication
4. Run `./gradlew publishPlugins`

That's it. Gradle handles the rest. No dealing with Maven Central or complex repository setups. The Plugin Portal is genuinely developer-friendly.

For now, I'm using my plugin locally with `includeBuild()` in my projects, which lets me test it in real scenarios before releasing it to the world.

## What I'd Do Differently Next Time

If I were starting over, I'd:

**Start with the tests**: I wrote tests after the implementation, which meant I had to retrofit testability. Writing tests first would've guided my design better.

**Read more existing plugins**: The Gradle codebase has tons of plugins you can learn from. Reading how they structure tasks and extensions taught me patterns I wouldn't have discovered on my own.

**Embrace Kotlin more**: I was initially hesitant about using Kotlin DSL for the build script, but it's way nicer than Groovy once you get used to it. Type safety catches so many silly mistakes.

**Document as I build**: I added documentation at the end, which meant I'd already forgotten some of my reasoning. Documenting decisions while fresh would've made better docs.

## You Can Actually Do This

Here's what surprised me most: building a Gradle plugin isn't rocket science. If you can write regular application code, you can write a plugin. It's just a different kind of code—one that manipulates build configuration instead of handling user requests or processing data.

The Gradle documentation is solid. The community is helpful. And the tooling (like `gradle init` and `GradleRunner`) removes most of the boilerplate pain.

My environment validator plugin solved a real problem I had. Yours can too. Maybe you're tired of copying deployment scripts between projects. Or you want to enforce code quality rules automatically. Or you have some custom build step that every project needs.

Whatever it is, you can make a plugin for it.

## Getting Started (Your Turn)

If you're thinking about building a Gradle plugin, here's my advice:

Start small. Pick one annoying thing in your build process and automate it. Don't try to build the next Android Gradle Plugin on your first attempt.

Use Kotlin. The type safety helps, and the DSL is cleaner than Groovy.

Test early. The `GradleRunner` makes testing easy—use it.

Read other plugins' source code. Seriously. You'll learn patterns and idioms way faster than reading documentation alone.

Don't be afraid to publish. Even if your plugin is simple, someone else might find it useful. And the feedback you'll get will make you a better developer.

## Wrapping Up

Building my first Gradle plugin taught me more about Gradle than years of just using it. I understand the build lifecycle better. I appreciate how plugins compose together. And I've got a tool that saves me from those facepalm moments when I forget to set environment variables.

You can do this. Pick a problem. Break it down. Write some code. Test it. Share it.

I made a Gradle plugin. You can make one too.

---

*Want to see the full source code for my environment validator plugin? Check out the repository at [github.com/KAbaidoo/environment-validator-gradle-plugin](https://github.com/KAbaidoo/environment-validator-gradle-plugin). Feel free to fork it, improve it, or use it as a starting point for your own plugin journey.*
