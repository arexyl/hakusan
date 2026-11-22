package app.hakusan.build

import java.util.Properties
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider

/** The setting that points the build at a local San build. */
public const val LOCAL_SAN_PROPERTY: String = "hakusan.san.local"

/**
 * Returns the staged set of a local San build, as a path that may be relative
 * to the root. The provider has no value in a build without one.
 *
 * A local build serves rehearsal and diagnosis only. The ignored
 * `local.properties` of the root holds the setting. A Gradle property of the
 * same name replaces it for one invocation, and an empty one switches the local
 * build off.
 */
public fun Project.localSan(): Provider<String> =
  providers
    .gradleProperty(LOCAL_SAN_PROPERTY)
    .orElse(
      providers
        .fileContents(layout.settingsDirectory.file("local.properties"))
        .asText
        .map { text ->
          Properties()
            .apply { load(text.reader()) }
            .getProperty(LOCAL_SAN_PROPERTY)
        }
    )
    .map { path -> path.ifEmpty { null } }

/**
 * Makes every Release build of this project fail while a local San build is
 * set, because a Release build of the application takes the San library of a
 * pinned release only.
 */
public fun Project.refuseLocalSanInRelease() {
  if (!localSan().isPresent) {
    return
  }

  tasks
    .matching { it.name == "preReleaseBuild" }
    .configureEach { it.doFirst(RefuseLocalSan) }
}

/** An object, because the Configuration Cache stores no lambda of this code. */
private object RefuseLocalSan : Action<Task> {
  override fun execute(task: Task) {
    throw GradleException(
      "${task.path} starts a Release build, which takes no local San " +
        "build. Switch it off for this invocation with -P$LOCAL_SAN_PROPERTY="
    )
  }
}
