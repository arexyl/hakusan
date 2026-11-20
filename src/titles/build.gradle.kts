import app.hakusan.build.VerifyRoomSchema

plugins {
  id("hakusan.android.library")
  alias(libs.plugins.ksp)
  alias(libs.plugins.room)
}

// Room exports into a build-owned path. A later version that needs an
// auto-migration seeds this path from the reviewed history before KSP runs.
val exportedRoomSchema = layout.buildDirectory.dir("schema")

val reviewedRoomSchema = layout.settingsDirectory.dir("share/titles/schema")

// The copy task declares no producer of its inputs, so name the KSP tasks of
// both variants that export the schema.
val roomSchemaExport =
  listOf("kspDebugKotlin", "kspReleaseKotlin", "copyRoomSchemas")

room3 {
  schemaDirectory(exportedRoomSchema)
}

android {
  namespace = "app.hakusan.titles"

  defaultConfig {
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    ndk {
      abiFilters += setOf("arm64-v8a", "x86_64")
    }
  }

  testOptions {
    targetSdk = libs.versions.sdk.target.get().toInt()
  }

  testFixtures {
    enable = true
  }
}

tasks.register<VerifyRoomSchema>("verifyRoomSchema") {
  group = "verification"
  description = "Fails when the exported Room schema differs from share/."
  dependsOn(roomSchemaExport)
  exported = exportedRoomSchema
  reviewed = reviewedRoomSchema
}

tasks.register<Copy>("promoteRoomSchema") {
  group = "room"
  description = "Copies the reviewed Room schema export into share/."
  dependsOn(roomSchemaExport)
  from(exportedRoomSchema)
  into(reviewedRoomSchema)
}

dependencies {
  api(libs.kotlinx.coroutines.core)
  implementation(libs.room.runtime)
  implementation(libs.sqlite.framework)
  ksp(libs.room.compiler)

  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)

  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.junit)
  androidTestRuntimeOnly(libs.androidx.test.runner)
}
