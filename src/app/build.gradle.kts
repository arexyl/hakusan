import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.metro)
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
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

  buildFeatures {
    compose = true
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

tasks.withType<Test>().configureEach { useJUnitPlatform() }

dependencyLocking {
  lockAllConfigurations()
}

dependencies {
  implementation(platform(libs.compose.bom))
  implementation(project(":app:sdk"))
  implementation(project(":app:ui"))
  implementation(project(":extensions"))
  implementation(project(":reader"))
  implementation(project(":titles"))
  implementation(libs.activity.compose)
  implementation(libs.compose.runtime)
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.junit.jupiter.api)
  testImplementation(testFixtures(project(":titles")))
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)

  androidTestImplementation(platform(libs.compose.bom))
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.junit)
  androidTestImplementation(libs.compose.ui.test.junit4)
  androidTestImplementation(libs.lifecycle.common)
  androidTestImplementation(libs.lifecycle.viewmodel)
  androidTestRuntimeOnly(libs.androidx.test.runner)

  constraints {
    androidTestImplementation(libs.espresso.core) {
      because("Compose UI Test brings Espresso 3.5.0, which fails on API 37")
    }
  }
}
