import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
}

layout.buildDirectory = layout.settingsDirectory.dir("build/work/titles")

android {
  namespace = "app.hakusan.titles"
  compileSdk = libs.versions.sdk.compile.get().toInt()
  buildToolsVersion = libs.versions.build.tools.get()

  defaultConfig {
    minSdk = libs.versions.sdk.min.get().toInt()

    ndk {
      abiFilters += setOf("arm64-v8a", "x86_64")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
  }
}

kotlin {
  explicitApi()
  jvmToolchain(25)

  compilerOptions {
    jvmTarget = JvmTarget.JVM_25
  }
}

dependencyLocking {
  lockAllConfigurations()
}
