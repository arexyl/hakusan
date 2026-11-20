package app.hakusan.build

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Applies the Android application plugin with the settings that every Android
 * project shares.
 */
public class AndroidApplicationPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    target.pluginManager.apply("com.android.application")

    target.configureAndroidProject(
      target.extensions.getByType(ApplicationExtension::class.java)
    )
  }
}
