import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
}

layout.buildDirectory = layout.settingsDirectory.dir("build/work/app-sdk")

android {
  namespace = "app.hakusan.sdk"
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

tasks.withType<Test>().configureEach { useJUnitPlatform() }

dependencyLocking {
  lockAllConfigurations()
}

dependencies {
  api(libs.kotlinx.coroutines.core)

  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}
