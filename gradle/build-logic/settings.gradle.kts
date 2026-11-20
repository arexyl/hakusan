pluginManagement {
  repositories {
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS

  repositories {
    exclusiveContent {
      forRepository { google() }

      filter {
        includeGroupAndSubgroups("com.android")
      }
    }

    mavenCentral()
  }

  versionCatalogs {
    create("libs") {
      from(files("../libs.versions.toml"))
    }
  }
}

rootProject.name = "build-logic"
