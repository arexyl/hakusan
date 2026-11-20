package app.hakusan.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Fails when the exported Room schema differs from the reviewed history. */
@DisableCachingByDefault(because = "Verification produces no output")
public abstract class VerifyRoomSchema : DefaultTask() {
  /** The schema that Room exported in this build. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val exported: DirectoryProperty

  /** The reviewed schema history. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val reviewed: DirectoryProperty

  @TaskAction
  public fun verify() {
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

    directory.asFileTree.visit { details ->
      if (!details.isDirectory) {
        files[details.relativePath.pathString] = details.file.readBytes()
      }
    }

    return files
  }
}
