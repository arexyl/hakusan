plugins {
  id("hakusan.android.library")
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.serialization)
}

android {
  namespace = "app.hakusan.ui"

  buildFeatures {
    compose = true
  }
}

dependencies {
  implementation(platform(libs.compose.bom))
  implementation(project(":app:sdk"))
  implementation(libs.compose.animation)
  implementation(libs.compose.foundation)
  implementation(libs.compose.material3)
  implementation(libs.compose.runtime)
  implementation(libs.compose.runtime.saveable)
  implementation(libs.compose.ui)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.core)
  implementation(libs.lifecycle.runtime.compose)
  implementation(libs.lifecycle.viewmodel)
  implementation(libs.lifecycle.viewmodel.compose)
  implementation(libs.lifecycle.viewmodel.navigation3)
  implementation(libs.lifecycle.viewmodel.savedstate)
  implementation(libs.navigation3.runtime)
  implementation(libs.navigation3.ui)
  implementation(libs.savedstate)

  debugImplementation(libs.compose.ui.tooling)
  debugImplementation(libs.compose.ui.tooling.preview)

  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.kotlinx.serialization.json)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}
