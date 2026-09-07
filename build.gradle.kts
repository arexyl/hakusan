import org.gradle.buildconfiguration.tasks.UpdateDaemonJvm

buildscript {
  configurations.classpath {
    resolutionStrategy.activateDependencyLocking()
  }

  dependencies {
    classpath(libs.kotlin.gradle)
  }
}

plugins {
  base
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.metro) apply false
  alias(libs.plugins.room) apply false
  alias(libs.plugins.serialization) apply false
}

layout.buildDirectory = layout.projectDirectory.dir("build/work/root")

tasks.named<Delete>("clean") {
  delete(
    layout.projectDirectory.dir("build/work"),
    layout.projectDirectory.dir("build/reports"),
  )
}

tasks.named<UpdateDaemonJvm>("updateDaemonJvm") {
  toolchainPlatforms.set(emptySet())
}
