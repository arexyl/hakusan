package app.hakusan.build

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidExtension

/**
 * Applies the settings that every Android project shares to a project whose
 * Android plugin registered [android].
 *
 * The project build file runs afterward, so it can extend these settings. The
 * build directory is `build/work/` and the project path, with `-` between path
 * segments. The `libs` version catalog owns the SDK levels, the Build Tools
 * version, and the JDK version, which is also the JVM bytecode version.
 *
 * @throws GradleException when the catalog lacks a version that the settings
 *   read.
 */
internal fun Project.configureAndroidProject(android: CommonExtension) {
  val directory = path.removePrefix(":").replace(':', '-')

  val buildDirectory = layout.settingsDirectory.dir("build/work/$directory")

  val versions =
    extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

  val compileLevel = versions.requiredVersion("sdk-compile").toInt()

  val compileMinorLevel = versions.requiredVersion("sdk-compile-minor").toInt()

  val jdk = versions.requiredVersion("jdk")

  val javaVersion = JavaVersion.toVersion(jdk)

  val kotlin = extensions.getByType(KotlinAndroidExtension::class.java)

  layout.buildDirectory.set(buildDirectory)

  android.compileSdk {
    version = release(compileLevel) { minorApiLevel = compileMinorLevel }
  }

  android.buildToolsVersion = versions.requiredVersion("build-tools")
  android.defaultConfig.minSdk = versions.requiredVersion("sdk-min").toInt()
  android.compileOptions.sourceCompatibility = javaVersion
  android.compileOptions.targetCompatibility = javaVersion
  kotlin.jvmToolchain(jdk.toInt())
  kotlin.compilerOptions.jvmTarget.set(JvmTarget.fromTarget(jdk))
  tasks.withType(Test::class.java).configureEach { it.useJUnitPlatform() }
  dependencyLocking.lockAllConfigurations()
}

private fun VersionCatalog.requiredVersion(alias: String): String =
  findVersion(alias)
    .orElseThrow { GradleException("The libs catalog lacks version $alias.") }
    .requiredVersion
