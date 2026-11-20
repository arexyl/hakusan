plugins {
  id("hakusan.android.application")
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.metro)
}

android {
  namespace = "app.hakusan"

  defaultConfig {
    applicationId = "app.hakusan"
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
}
