import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
  alias(libs.plugins.android.application)
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
  implementation(project(":app:ui"))
  implementation(project(":app:sdk"))
  implementation(project(":titles"))
  implementation(project(":extensions"))
  implementation(project(":reader"))

  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}
