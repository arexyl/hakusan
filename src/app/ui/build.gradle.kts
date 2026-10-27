import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.serialization)
}

layout.buildDirectory = layout.settingsDirectory.dir("build/work/app-ui")

android {
  namespace = "app.hakusan.ui"
  compileSdk = libs.versions.sdk.compile.get().toInt()
  buildToolsVersion = libs.versions.build.tools.get()

  defaultConfig {
    minSdk = libs.versions.sdk.min.get().toInt()
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
  explicitApi()
  jvmToolchain(25)

  compilerOptions {
    jvmTarget = JvmTarget.JVM_25
  }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

dependencyLocking {
  lockAllConfigurations()
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
