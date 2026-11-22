package app.hakusan.build

import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Stages the San library of a local San build as a `jniLibs` directory, and its
 * manifest as an asset.
 *
 * A local build serves rehearsal and diagnosis only. The task takes it only
 * when its manifest names the builder `local`, when every library has the size
 * and the SHA-256 that the manifest records, and when the manifest's Bindings
 * commit equals the commit checked out at `share/bindings` and the pointer that
 * the index records, with no uncommitted change there.
 */
public abstract class StageLocalSan : DefaultTask() {
  /** The staged set of a San build: `manifest.json` and `<abi>/libsan.so`. */
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val localSet: DirectoryProperty

  /** The root of the repository. Git reads the Bindings pointer there. */
  @get:Internal public abstract val repository: DirectoryProperty

  /** The `jniLibs` directory that the task replaces. */
  @get:OutputDirectory public abstract val jniLibs: DirectoryProperty

  /**
   * The assets directory that the task replaces. It receives the manifest as
   * `san/manifest.json`, so that a package states which build its library is.
   */
  @get:OutputDirectory public abstract val assets: DirectoryProperty

  @get:Inject protected abstract val exec: ExecOperations

  @get:Inject protected abstract val files: FileSystemOperations

  init {
    // The Bindings checkout is no declared input, so the check always runs.
    outputs.upToDateWhen { false }
  }

  @TaskAction
  public fun stage() {
    val set = localSet.get().asFile
    val target = jniLibs.get().asFile
    val stated = assets.get().asFile

    // A failed check leaves nothing behind, of this run or an earlier one.
    files.delete { it.delete(target, stated) }

    try {
      stage(set, target, File(stated, MANIFEST_ASSET))
    } catch (failure: Exception) {
      files.delete { it.delete(target, stated) }

      throw failure
    }
  }

  private fun stage(set: File, target: File, stated: File) {
    // The checks read the copy, which is the manifest that is packaged.
    File(set, "manifest.json").copyTo(stated)

    val manifest = JsonSlurper().parse(stated) as? Map<*, *>
    val format = manifest.field<Any>("format")

    if (format != FORMAT) {
      throw GradleException("$set has the unknown manifest format $format")
    }

    val builder = manifest.field<Map<*, *>>("builder").field<String>("kind")

    if (builder != "local") {
      throw GradleException("$set is no local San build: builder $builder")
    }

    requireBindings(
      manifest.field<Map<*, *>>("bindings").field<String>("commit")
    )

    val libraries =
      manifest
        .field<List<*>>("files")
        .map { it as? Map<*, *> }
        .filter { LIBRARY.matches(it.field<String>("path")) }

    if (libraries.isEmpty()) {
      throw GradleException("The manifest of $set names no San library")
    }

    for (library in libraries) {
      val path = library.field<String>("path")
      val staged = File(target, path)

      // The check reads the copy, so it covers the bytes that are packaged.
      File(set, path).copyTo(staged)

      val sha256 = sha256(staged)

      if (
        staged.length() != library.field<Number>("size").toLong() ||
          sha256 != library.field<String>("sha256")
      ) {
        throw GradleException(
          "${File(set, path)} differs from its manifest: " +
            "${staged.length()} bytes, $sha256"
        )
      }
    }

    val commit = manifest.field<Map<*, *>>("san").field<String>("commit")

    logger.lifecycle(
      "Staged the San library of the local build {}, for rehearsal and " +
        "diagnosis only: {}",
      commit,
      libraries.joinToString { "${it?.get("path")} ${it?.get("sha256")}" },
    )
  }

  /** Returns the field [name] of a manifest object, or fails with its name. */
  private inline fun <reified T : Any> Map<*, *>?.field(name: String): T =
    this?.get(name) as? T
      ?: throw GradleException("The San manifest lacks a valid field $name")

  /** Fails unless the checkout, the index, and [commit] name one commit. */
  private fun requireBindings(commit: String) {
    val checkedOut = git("-C", BINDINGS, "rev-parse", "HEAD")
    val recorded = git("rev-parse", ":$BINDINGS")
    val changes = git("-C", BINDINGS, "status", "--porcelain")

    if (checkedOut != commit || recorded != commit || changes.isNotEmpty()) {
      throw GradleException(
        "The San build was made for the Bindings commit $commit, but " +
          "$BINDINGS is at $checkedOut, the index records $recorded, and " +
          "its uncommitted changes are: ${changes.ifEmpty { "none" }}"
      )
    }
  }

  private fun git(vararg arguments: String): String {
    val output = ByteArrayOutputStream()

    exec.exec {
      it.workingDir = repository.get().asFile
      it.commandLine(listOf("git") + arguments)
      it.standardOutput = output
    }

    return output.toString(Charsets.UTF_8).trim()
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")

    file.inputStream().use { input ->
      val buffer = ByteArray(1 shl 16)

      while (true) {
        val read = input.read(buffer)

        if (read < 0) break

        digest.update(buffer, 0, read)
      }
    }

    return HexFormat.of().formatHex(digest.digest())
  }

  private companion object {
    const val FORMAT = 1
    const val BINDINGS = "share/bindings"
    const val MANIFEST_ASSET = "san/manifest.json"

    /** A library at its path inside a set, which is also its staged path. */
    val LIBRARY = Regex("[A-Za-z0-9_-]+/libsan\\.so")
  }
}
