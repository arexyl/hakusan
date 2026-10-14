import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.ksp)
  alias(libs.plugins.room)
}

layout.buildDirectory = layout.settingsDirectory.dir("build/work/titles")

// Room exports into a build-owned path. A later version that needs an
// auto-migration seeds this path from the reviewed history before KSP runs.
val exportedRoomSchema = layout.buildDirectory.dir("schema")

val reviewedRoomSchema = layout.settingsDirectory.dir("share/titles/schema")

// The copy task declares no producer of its inputs, so name the KSP tasks of
// both variants that export the schema.
val roomSchemaExport =
  listOf("kspDebugKotlin", "kspReleaseKotlin", "copyRoomSchemas")

room3 {
  schemaDirectory(exportedRoomSchema)
}

android {
  namespace = "app.hakusan.titles"
  compileSdk = libs.versions.sdk.compile.get().toInt()
  buildToolsVersion = libs.versions.build.tools.get()

  defaultConfig {
    minSdk = libs.versions.sdk.min.get().toInt()
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    ndk {
      abiFilters += setOf("arm64-v8a", "x86_64")
    }
  }

  testOptions {
    targetSdk = libs.versions.sdk.target.get().toInt()
  }

  testFixtures {
    enable = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
  }
}

kotlin {
  explicitApi()
  jvmToolchain(25)

  compilerOptions {
    jvmTarget = JvmTarget.JVM_25
  }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

/** Fails when the exported Room schema differs from the reviewed history. */
@DisableCachingByDefault(because = "Verification produces no output")
abstract class VerifyRoomSchema : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val exported: DirectoryProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val reviewed: DirectoryProperty

  @TaskAction
  fun verify() {
    val exportedFiles = contents(exported.get())

    val reviewedFiles = contents(reviewed.get())

    if (exportedFiles.isEmpty()) {
      throw GradleException("Room exported no schema.")
    }

    val differences =
      (exportedFiles.keys + reviewedFiles.keys).sorted().filterNot { path ->
        exportedFiles[path] contentEquals reviewedFiles[path]
      }

    if (differences.isNotEmpty()) {
      throw GradleException(
        "The exported Room schema differs from the reviewed history in " +
          "${differences.joinToString()}. Review the export and run " +
          ":titles:promoteRoomSchema, or clean a stale export."
      )
    }
  }

  private fun contents(directory: Directory): Map<String, ByteArray> {
    val files = mutableMapOf<String, ByteArray>()

    directory.asFileTree.visit {
      if (!isDirectory) {
        files[relativePath.pathString] = file.readBytes()
      }
    }

    return files
  }
}

tasks.register<VerifyRoomSchema>("verifyRoomSchema") {
  group = "verification"
  description = "Fails when the exported Room schema differs from share/."
  dependsOn(roomSchemaExport)
  exported = exportedRoomSchema
  reviewed = reviewedRoomSchema
}

tasks.register<Copy>("promoteRoomSchema") {
  group = "room"
  description = "Copies the reviewed Room schema export into share/."
  dependsOn(roomSchemaExport)
  from(exportedRoomSchema)
  into(reviewedRoomSchema)
}

dependencyLocking {
  lockAllConfigurations()
}

dependencies {
  api(libs.kotlinx.coroutines.core)
  implementation(libs.room.runtime)
  implementation(libs.sqlite.framework)
  ksp(libs.room.compiler)

  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)

  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.junit)
  androidTestRuntimeOnly(libs.androidx.test.runner)
}
