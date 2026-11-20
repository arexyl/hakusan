package app.hakusan.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when the Bindings checkout lacks a file of the native ABI.
 *
 * An uninitialized submodule leaves `share/bindings` empty, and the compiler
 * would then compile nothing from it. The task reads the checkout when it runs,
 * so a reused Configuration Cache entry never hides a missing file.
 */
public abstract class CheckBindings : DefaultTask() {
  /** The Bindings checkout. The failure names missing files relative to it. */
  @get:Internal public abstract val checkout: DirectoryProperty

  /** The files of the native ABI. A missing file is a recorded input state. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val required: ConfigurableFileCollection

  /** A summary that the task writes only when every file exists. */
  @get:OutputFile public abstract val report: RegularFileProperty

  @TaskAction
  public fun check() {
    report.get().asFile.delete()

    val root = checkout.get().asFile

    val missing =
      required.files
        .filterNot { it.isFile }
        .map { it.relativeTo(root).invariantSeparatorsPath }
        .sorted()

    if (missing.isNotEmpty()) {
      throw GradleException(
        "share/bindings lacks the native ABI files " +
          "${missing.joinToString(", ")}. Check out its recorded commit " +
          "with: git submodule update --init --checkout share/bindings"
      )
    }

    report.get().asFile.writeText("Found ${required.files.size} ABI files.\n")
  }
}
