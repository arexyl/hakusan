plugins {
  id("hakusan.android.library")
}

android {
  namespace = "app.hakusan.sdk"
}

dependencies {
  api(libs.kotlinx.coroutines.core)

  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}
