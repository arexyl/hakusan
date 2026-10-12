import groovy.json.JsonSlurper
import java.io.OutputStream
import java.nio.file.Files
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import javax.inject.Inject
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
}

layout.buildDirectory = layout.settingsDirectory.dir("build/work/reader")

android {
  namespace = "app.hakusan.reader"
  compileSdk = libs.versions.sdk.compile.get().toInt()
  buildToolsVersion = libs.versions.build.tools.get()

  defaultConfig {
    minSdk = libs.versions.sdk.min.get().toInt()
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

dependencyLocking {
  lockAllConfigurations()
}

dependencies {
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.kotlinx.coroutines.core)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * Checks the reviewed fixture corpus and its pinned tool scripts against the
 * sizes and SHA-256 digests that the corpus manifest records.
 *
 * The check runs no fixture tool. It fails on a manifest that it cannot read
 * and on a missing, unlisted, or changed artifact or tool script.
 */
abstract class VerifyFixtures : DefaultTask() {
  /** The corpus directory, with `manifest.json` and `README.md` at its root. */
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val corpus: DirectoryProperty

  /** The tool scripts that the manifest pins by file name. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val tools: ConfigurableFileCollection

  /**
   * A summary of the verified corpus. The task deletes it before checking and
   * writes it again only when every check passes.
   */
  @get:OutputFile abstract val report: RegularFileProperty

  /** The recorded size, when the manifest records one, and digest of a file. */
  private class Pin(val bytes: Long?, val sha256: String)

  /** The pins of the corpus artifacts by path and of the tools by name. */
  private class Manifest(
    val artifacts: Map<String, Pin>,
    val scripts: Map<String, Pin>,
  )

  @TaskAction
  fun verify() {
    report.get().asFile.delete()

    val manifest = readManifest(corpus.get().file(MANIFEST).asFile)

    val corpusFiles = corpusFiles()

    val toolFiles = tools.files.associateBy { it.name }

    val problems =
      compare("artifact", manifest.artifacts, corpusFiles) +
        compare("tool script", manifest.scripts, toolFiles)

    if (problems.isNotEmpty()) {
      throw GradleException(
        problems.joinToString("\n", "Fixture verification failed:\n")
      )
    }

    val bytes = manifest.artifacts.values.sumOf { it.bytes ?: 0L }

    val scripts = manifest.scripts.keys.sorted().joinToString(", ")

    report
      .get()
      .asFile
      .writeText(
        "Verified ${manifest.artifacts.size} artifacts ($bytes bytes) and " +
          "the tool scripts $scripts.\n"
      )
  }

  private fun readManifest(file: File): Manifest {
    val root = JsonSlurper().parse(file).asObject("manifest")

    if (root["schema_version"] != 1) {
      throw GradleException("Unknown fixture manifest schema")
    }

    val artifacts = mutableMapOf<String, Pin>()

    for (entry in root["artifacts"].asList("artifacts")) {
      val artifact = entry.asObject("artifact")

      val path = artifact["path"].asText("artifact path")

      if ('\\' in path || path.split('/').any { it in INVALID_SEGMENTS }) {
        throw GradleException("Unsafe fixture path: $path")
      }

      val pin =
        Pin(
          artifact["bytes"].asCount("size of $path"),
          artifact["sha256"].asDigest("digest of $path"),
        )

      if (artifacts.put(path, pin) != null) {
        throw GradleException("Duplicate fixture path: $path")
      }
    }

    val provenance = root["provenance"].asObject("provenance")

    val sources = provenance["source_sha256"].asObject("tool digests")

    val scripts =
      sources.entries.associate { (key, value) ->
        val name = key.asText("tool script name")

        if ('/' in name || '\\' in name || name in INVALID_SEGMENTS) {
          throw GradleException("Unsafe tool script name: $name")
        }

        name to Pin(null, value.asDigest("digest of $name"))
      }

    return Manifest(artifacts, scripts)
  }

  private fun corpusFiles(): Map<String, File> {
    val files = mutableMapOf<String, File>()

    corpus.get().asFileTree.visit {
      if (Files.isSymbolicLink(file.toPath())) {
        throw GradleException("Fixture symbolic link: $path")
      }

      if (!isDirectory && path !in CORPUS_METADATA) {
        files[path] = file
      }
    }

    return files
  }

  private fun compare(
    kind: String,
    pins: Map<String, Pin>,
    files: Map<String, File>,
  ): List<String> {
    val missing = (pins.keys - files.keys).sorted().map { "Missing $kind: $it" }

    val unlisted =
      (files.keys - pins.keys).sorted().map { "Unlisted $kind: $it" }

    val changed =
      (pins.keys intersect files.keys).sorted().mapNotNull { name ->
        mismatch(kind, name, pins.getValue(name), files.getValue(name))
      }

    return missing + unlisted + changed
  }

  private fun mismatch(
    kind: String,
    name: String,
    pin: Pin,
    file: File,
  ): String? {
    val bytes = file.length()

    val sha256 = sha256(file)

    if ((pin.bytes == null || pin.bytes == bytes) && pin.sha256 == sha256) {
      return null
    }

    val expected = pin.bytes?.let { "$it bytes, " }.orEmpty() + pin.sha256

    return "Changed $kind: $name (expected $expected; found $bytes bytes, " +
      "$sha256)"
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")

    DigestInputStream(file.inputStream(), digest).use {
      it.transferTo(OutputStream.nullOutputStream())
    }

    return HexFormat.of().formatHex(digest.digest())
  }

  private fun Any?.asObject(name: String): Map<*, *> =
    this as? Map<*, *>
      ?: throw GradleException("Manifest $name is not an object")

  private fun Any?.asList(name: String): List<*> =
    this as? List<*> ?: throw GradleException("Manifest $name is not a list")

  private fun Any?.asText(name: String): String =
    this as? String ?: throw GradleException("Manifest $name is not text")

  private fun Any?.asCount(name: String): Long {
    val count =
      when (this) {
        is Int -> toLong()
        is Long -> this
        else -> null
      }

    if (count == null || count < 0) {
      throw GradleException("Manifest $name is not a nonnegative integer")
    }

    return count
  }

  private fun Any?.asDigest(name: String): String {
    val digest = asText(name)

    if (!SHA256.matches(digest)) {
      throw GradleException("Manifest $name is not a SHA-256 digest")
    }

    return digest
  }

  private companion object {
    const val MANIFEST = "manifest.json"
    val CORPUS_METADATA = setOf(MANIFEST, "README.md")
    val INVALID_SEGMENTS = setOf("", ".", "..")
    val SHA256 = Regex("[0-9a-f]{64}")
  }
}

tasks.register<VerifyFixtures>("verifyFixtures") {
  group = "verification"
  description = "Checks the fixture corpus and its tools against the manifest."
  corpus = layout.settingsDirectory.dir("share/reader")
  tools.from(fileTree("fixtures") { include("*.py") })
  report = layout.buildDirectory.file("verifyFixtures/report.txt")
}

/**
 * Replaces the reviewed fixture corpus with a generated corpus.
 *
 * Validation fails when the generated corpus or its manifest is missing, so an
 * incomplete generation never reaches the reviewed corpus. The corpus README
 * stays, because the generator does not write it.
 */
abstract class PromoteFixtures : DefaultTask() {
  /** The generated corpus that replaces the reviewed one. */
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val generated: DirectoryProperty

  /** The manifest that a completed generation writes last. */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val manifest: RegularFileProperty

  /** The reviewed corpus. */
  @get:OutputDirectory abstract val corpus: DirectoryProperty

  @get:Inject abstract val files: FileSystemOperations

  @TaskAction
  fun promote() {
    files.sync {
      from(generated)
      into(corpus)
      preserve { include("README.md") }
    }
  }
}

tasks.register<PromoteFixtures>("promoteFixtures") {
  group = "fixtures"
  description = "Replaces the reviewed corpus with the generated corpus."
  generated = layout.buildDirectory.dir("fixtures/corpus")
  manifest = generated.file("manifest.json")
  corpus = layout.settingsDirectory.dir("share/reader")
  finalizedBy("verifyFixtures")
}
