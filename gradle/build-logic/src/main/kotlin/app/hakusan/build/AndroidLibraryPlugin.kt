package app.hakusan.build

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidExtension

/**
 * Applies the Android library plugin with the settings that every Android
 * project shares, and Kotlin explicit API mode.
 */
public class AndroidLibraryPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    target.pluginManager.apply("com.android.library")

    target.configureAndroidProject(
      target.extensions.getByType(LibraryExtension::class.java)
    )

    target.extensions
      .getByType(KotlinAndroidExtension::class.java)
      .explicitApi()
  }
}
