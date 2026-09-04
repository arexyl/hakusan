import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
  alias(libs.plugins.android.library)
}

layout.buildDirectory = layout.settingsDirectory.dir("build/work/titles")

android {
  namespace = "app.hakusan.titles"
  buildToolsVersion = "36.0.0"
  compileSdk = 37

  defaultConfig {
    minSdk = 33
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
