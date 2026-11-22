import app.hakusan.build.StageFixtures

plugins {
  id("hakusan.android.application")
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "app.hakusan.bench"

  defaultConfig {
    applicationId = "app.hakusan.bench"
    targetSdk = libs.versions.sdk.target.get().toInt()
    versionCode = 1
    versionName = "0.0.0"
  }

  buildTypes {
    release {
      // The harness is never published. The debug key only lets the measured
      // build install.
      signingConfig = signingConfigs.getByName("debug")

      optimization {
        enable = true
      }
    }
  }

  buildFeatures {
    compose = true
  }
}

// The fixtures that the harness decodes, with the manifest that states their
// hashes. The harness checks each file against it before it decodes. Each
// variant has a task of its own, because the plugin gives the staged
// directory of a task to one variant.
androidComponents {
  onVariants { variant ->
    val name = variant.name.replaceFirstChar { it.uppercase() }

    val stageFixtures =
      tasks.register<StageFixtures>("stage${name}Fixtures") {
        group = "build"
        description = "Stages the timed fixtures as assets of the harness."
        corpus = layout.settingsDirectory.dir("share/reader")

        paths =
          listOf(
            "manifest.json",
            "journey/ordinary.png",
            "journey/long.png",
            "qualification/timed/large.png",
          )
      }

    variant.sources.assets?.addGeneratedSourceDirectory(
      stageFixtures,
      StageFixtures::staged,
    )
  }
}

dependencies {
  implementation(project(":reader"))
  implementation(platform(libs.compose.bom))
  implementation(libs.activity.compose)
  implementation(libs.compose.foundation)
  implementation(libs.compose.runtime)
  implementation(libs.compose.ui)
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}
