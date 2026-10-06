plugins {
  alias(libs.plugins.kotlin.jvm)
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
  compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okhttp)
  implementation(libs.bouncycastle)
  // Android ships org.json in the framework; the app build drops this copy.
  implementation(libs.orgjson)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
  testLogging {
    events("failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}
