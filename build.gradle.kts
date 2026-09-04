import org.gradle.buildconfiguration.tasks.UpdateDaemonJvm

buildscript {
  dependencies {
    classpath(libs.kotlin.gradle)
  }
}

plugins {
  base
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
}

layout.buildDirectory = layout.projectDirectory.dir("build/work/root")

tasks.named<Delete>("clean") {
  delete(layout.projectDirectory.dir("build/work"))
}

tasks.named<UpdateDaemonJvm>("updateDaemonJvm") {
  toolchainPlatforms.set(emptySet())
}
