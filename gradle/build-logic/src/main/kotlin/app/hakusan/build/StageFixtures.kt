package app.hakusan.build

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Copies named files of the fixture corpus into a directory that a project
 * packages, each at its path in the corpus.
 *
 * The task fails when a named file is missing, where a copy specification would
 * skip it. It checks no hash: the consumer of the staged files checks them
 * against the manifest that it receives beside them.
 */
public abstract class StageFixtures : DefaultTask() {
  /** The root of the corpus. Only the files that [paths] names are inputs. */
  @get:Internal public abstract val corpus: DirectoryProperty

  /** The staged files, as paths relative to [corpus]. */
  @get:Input public abstract val paths: ListProperty<String>

  /** The directory that the task replaces. */
  @get:OutputDirectory public abstract val staged: DirectoryProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  protected abstract val sources: ConfigurableFileCollection

  @get:Inject protected abstract val files: FileSystemOperations

  init {
    sources.from(paths.map { names -> names.map { corpus.file(it) } })
  }

  @TaskAction
  public fun stage() {
    val root = corpus.get().asFile
    val target = staged.get().asFile

    files.delete { it.delete(target) }

    for (path in paths.get()) {
      File(root, path).copyTo(File(target, path))
    }
  }
}
