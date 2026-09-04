pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "hakusan"

include(
  ":app",
  ":app:sdk",
  ":app:ui",
  ":titles",
  ":extensions",
  ":reader",
)

project(":app").projectDir = file("src/app")
project(":app:sdk").projectDir = file("src/app/sdk")
project(":app:ui").projectDir = file("src/app/ui")
project(":titles").projectDir = file("src/titles")
project(":extensions").projectDir = file("src/extensions")
project(":reader").projectDir = file("src/reader")
