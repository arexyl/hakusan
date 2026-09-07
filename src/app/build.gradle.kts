import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.metro)
}

layout.buildDirectory = layout.settingsDirectory.dir("build/work/app")

android {
  namespace = "app.hakusan"
  buildToolsVersion = "36.0.0"
  compileSdk = 37

  defaultConfig {
    applicationId = "app.hakusan"
    minSdk = 33
    targetSdk = 37
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    versionCode = 1
    versionName = "0.0.0"
  }

  buildTypes {
    release {
      optimization {
        enable = true
      }
    }
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

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}

dependencyLocking {
  lockAllConfigurations()
}

dependencies {
  implementation(platform(libs.compose.bom))
  implementation(project(":app:ui"))
  implementation(project(":app:sdk"))
  implementation(project(":titles"))
  implementation(project(":extensions"))
  implementation(libs.activity.compose)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.lifecycle.viewmodel)

  androidTestImplementation(platform(libs.compose.bom))
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.junit)
  androidTestImplementation(libs.compose.ui.test.junit4)
  androidTestRuntimeOnly(libs.androidx.test.runner)
  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)

  constraints {
    androidTestImplementation(libs.espresso.core) {
      because("Compose UI Test's Espresso 3.5.0 fails on API 37")
    }
  }
}
