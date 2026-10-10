import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
}

layout.buildDirectory = layout.settingsDirectory.dir("build/work/app-ui")

android {
  namespace = "app.hakusan.ui"
  compileSdk = libs.versions.sdk.compile.get().toInt()
  buildToolsVersion = libs.versions.build.tools.get()

  defaultConfig {
    minSdk = libs.versions.sdk.min.get().toInt()
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

dependencies {
  implementation(project(":app:sdk"))
}
