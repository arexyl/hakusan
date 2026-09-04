import org.gradle.buildconfiguration.tasks.UpdateDaemonJvm

plugins {
  base
}

layout.buildDirectory = layout.projectDirectory.dir("build/work/root")

tasks.named<Delete>("clean") {
  delete(layout.projectDirectory.dir("build/work"))
}

tasks.named<UpdateDaemonJvm>("updateDaemonJvm") {
  toolchainPlatforms.set(emptySet())
}
