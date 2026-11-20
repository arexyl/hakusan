package app.hakusan.build

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Replaces the reviewed fixture corpus with a generated corpus.
 *
 * Validation fails when the generated corpus or its manifest is missing, so an
 * incomplete generation never reaches the reviewed corpus. The corpus README
 * stays, because the generator does not write it.
 */
public abstract class PromoteFixtures : DefaultTask() {
  /** The generated corpus that replaces the reviewed one. */
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val generated: DirectoryProperty

  /** The manifest that a completed generation writes last. */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val manifest: RegularFileProperty

  /** The reviewed corpus. */
  @get:OutputDirectory public abstract val corpus: DirectoryProperty

  @get:Inject public abstract val files: FileSystemOperations

  @TaskAction
  public fun promote() {
    files.sync { spec ->
      spec.from(generated)
      spec.into(corpus)
      spec.preserve { it.include("README.md") }
    }
  }
}
