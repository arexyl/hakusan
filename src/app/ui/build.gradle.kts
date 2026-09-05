import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.serialization)
}

layout.buildDirectory = layout.settingsDirectory.dir("build/work/app-ui")

android {
  namespace = "app.hakusan.ui"
  buildToolsVersion = "36.0.0"
  compileSdk = 37

  defaultConfig {
    minSdk = 33
  }

  buildFeatures {
    compose = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
  }
}

kotlin {
  jvmToolchain(25)

  compilerOptions {
    apiVersion = KotlinVersion.KOTLIN_2_4
    jvmTarget = JvmTarget.JVM_25
    languageVersion = KotlinVersion.KOTLIN_2_4
  }
}

dependencyLocking {
  lockAllConfigurations()
}

dependencies {
  implementation(platform(libs.compose.bom))
  implementation(project(":app:sdk"))
  implementation(project(":reader"))
  implementation(libs.activity.compose)
  implementation(libs.compose.animation)
  implementation(libs.compose.foundation)
  implementation(libs.compose.material3)
  implementation(libs.compose.runtime)
  implementation(libs.compose.ui)
  implementation(libs.kotlinx.serialization.core)
  implementation(libs.navigation3.runtime)
  implementation(libs.navigation3.ui)

  debugImplementation(libs.compose.ui.tooling)
  debugImplementation(libs.compose.ui.tooling.preview)
}
