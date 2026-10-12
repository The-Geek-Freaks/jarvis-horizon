plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.ktlint)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "ai.openclaw.wear.shared"
  compileSdk = 37

  defaultConfig {
    // This module only carries Kotlin wire protocol/serialization code and is also
    // linked by the phone app. Keep it compatible with the LG V30's API 28 ROM.
    minSdk = 27
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  lint {
    warningsAsErrors = true
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    allWarningsAsErrors.set(true)
  }
}

ktlint {
  version.set(libs.versions.ktlint.cli)
  android.set(true)
  ignoreFailures.set(false)
  filter {
    exclude("**/build/**")
  }
}

dependencies {
  api(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
}
