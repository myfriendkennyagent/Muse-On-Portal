plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.myfriendkennyagent.museportal"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.myfriendkennyagent.museportal"
    // Portal 2nd gen runs Android 10; 28 keeps 1st-gen Portals working too.
    minSdk = 28
    // Portal runs older AOSP: Meta recommends targeting 29.
    targetSdk = 29
    versionCode = 1
    versionName = "0.1.0"
    ndk { abiFilters += "arm64-v8a" }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      // Sideloaded only; signed with the debug key so `adb install` just works.
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  lint {
    // Portal tops out at Android 10, so the target stays at 29 on purpose.
    disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    abortOnError = false
  }

  packaging {
    resources {
      excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
  }
}

kotlin {
  compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
  implementation(project(":protocol"))
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.okhttp)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.service)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  debugImplementation(libs.androidx.compose.ui.tooling)

  testImplementation(libs.junit)
}
