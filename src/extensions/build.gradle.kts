plugins {
  id("hakusan.android.library")
}

android {
  namespace = "app.hakusan.extensions"
}

dependencies {
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.kotlin.reflect)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}
