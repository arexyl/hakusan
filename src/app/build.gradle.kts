import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
}

layout.buildDirectory = layout.settingsDirectory.dir("build/work/app")

android {
  namespace = "app.hakusan"
  compileSdk = libs.versions.sdk.compile.get().toInt()
  buildToolsVersion = libs.versions.build.tools.get()

  defaultConfig {
    applicationId = "app.hakusan"
    minSdk = libs.versions.sdk.min.get().toInt()
    targetSdk = libs.versions.sdk.target.get().toInt()
    versionCode = 1
    versionName = "0.0.0"
  }

  buildTypes {
    debug {
      ndk {
        abiFilters += setOf("arm64-v8a", "x86_64")
      }
    }

    release {
      ndk {
        abiFilters += "arm64-v8a"
      }

      optimization {
        enable = true
      }
    }
  }

  lint {
    fatal += "Aligned16KB"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
  }
}

kotlin {
  jvmToolchain(25)

  compilerOptions {
    jvmTarget = JvmTarget.JVM_25
  }
}

dependencyLocking {
  lockAllConfigurations()
}

dependencies {
  implementation(project(":app:sdk"))
  implementation(project(":app:ui"))
  implementation(project(":extensions"))
  implementation(project(":reader"))
  implementation(project(":titles"))
}
