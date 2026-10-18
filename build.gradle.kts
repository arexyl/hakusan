import org.gradle.buildconfiguration.tasks.UpdateDaemonJvm

buildscript {
  configurations.classpath {
    resolutionStrategy.activateDependencyLocking()
  }

  dependencies {
    classpath(libs.kotlin.gradle)
  }
}

plugins {
  base
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.metro) apply false
  alias(libs.plugins.room) apply false
}

layout.buildDirectory = layout.projectDirectory.dir("build/work/root")

dependencyLocking {
  lockAllConfigurations()
}

val ktfmt = configurations.dependencyScope("ktfmt")

val ktfmtClasspath =
  configurations.resolvable("ktfmtClasspath") {
    extendsFrom(ktfmt.get())

    attributes {
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
      attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))

      attribute(
        LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
        objects.named(LibraryElements.JAR),
      )

      attribute(
        TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
        objects.named(TargetJvmEnvironment.STANDARD_JVM),
      )
    }
  }

dependencies {
  ktfmt(libs.ktfmt)
}

val ktfmtSources =
  fileTree("src") { include("**/*.kt", "**/*.kts") } +
    fileTree("share/bindings/kotlin") { include("**/*.kt") } +
    files("build.gradle.kts", "settings.gradle.kts")

fun JavaExec.runKtfmt(vararg mode: String) {
  val sources = ktfmtSources

  val options = listOf("--google-style", "--enable-editorconfig", *mode)

  classpath(ktfmtClasspath)
  mainClass = "com.facebook.ktfmt.cli.Main"
  jvmArgs("--sun-misc-unsafe-memory-access=allow")
  inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
  inputs.file(".editorconfig").withPathSensitivity(PathSensitivity.RELATIVE)

  inputs
    .file("share/bindings/.editorconfig")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  dependsOn(":reader:checkBindings")

  argumentProviders.add(
    CommandLineArgumentProvider {
      options + sources.files.map { it.path }.sorted()
    }
  )
}

tasks.register<JavaExec>("ktfmtCheck") {
  group = "verification"
  description = "Fails when a Kotlin or Kotlin DSL file is not formatted."
  runKtfmt("--dry-run", "--set-exit-if-changed")
}

tasks.register<JavaExec>("ktfmtFormat") {
  group = "formatting"
  description = "Formats every Kotlin and Kotlin DSL file."
  runKtfmt()
}

val hostMatrixProjects =
  listOf(":app", ":app:sdk", ":app:ui", ":titles", ":extensions", ":reader")

val hostMatrixTasks =
  listOf(
    "testDebugUnitTest",
    "lintDebug",
    "lintRelease",
    "assembleDebug",
    "assembleRelease",
  )

val hostMatrixAndroidTestProjects = listOf(":titles", ":app")

tasks.register("hostMatrix") {
  group = "verification"
  description = "Runs the host verification matrix of every project."
  dependsOn(":ktfmtCheck", ":reader:verifyFixtures", ":titles:verifyRoomSchema")

  dependsOn(
    hostMatrixProjects.flatMap { project ->
      hostMatrixTasks.map { task -> "$project:$task" }
    }
  )

  dependsOn(
    hostMatrixAndroidTestProjects.map { project ->
      "$project:assembleDebugAndroidTest"
    }
  )
}

tasks.named<Delete>("clean") {
  delete(
    layout.projectDirectory.dir("build/work"),
    layout.projectDirectory.dir("build/reports"),
  )
}

tasks.named<UpdateDaemonJvm>("updateDaemonJvm") {
  toolchainPlatforms.set(emptySet())
}
