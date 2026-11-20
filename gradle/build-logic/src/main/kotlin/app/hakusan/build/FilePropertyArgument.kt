package app.hakusan.build

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/**
 * Passes a reviewed file to the host tests as a runtime argument.
 *
 * The file is an input with a relative path sensitivity, so its absolute path
 * never enters the cache key. The tests read the path from the system property
 * that [property] names.
 */
public abstract class FilePropertyArgument : CommandLineArgumentProvider {
  /** The name of the system property that receives the path. */
  @get:Input public abstract val property: Property<String>

  /** The file that the tests read. */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val file: RegularFileProperty

  override fun asArguments(): Iterable<String> =
    listOf("-D${property.get()}=${file.get().asFile.absolutePath}")
}
