plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "io.stride.spikes"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "io.stride.spikes"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        // Console is Android 8/9. API 26 is also the floor for TYPE_APPLICATION_OVERLAY.
        minSdk = 26

        // targetSdk is DELIBERATELY 28 and is load-bearing for the spike results - do NOT bump the
        // default to "modernise" it. Each step above 28 silently changes platform behaviour the
        // spikes are trying to measure on the real console:
        //   - 29+: scoped storage. Breaks reading the iFit APK / any /sdcard path work that S2
        //          (locating com.ifit.rivendell) depends on. Also restricts background activity
        //          starts, which changes overlay and app-launch behaviour (S3, S4).
        //   - 30+: package-visibility filtering. Breaks queryIntentActivities app enumeration - the
        //          launcher's single core feature (S4, the app grid). A <queries> block in the
        //          manifest restores the honest, policy-safe subset.
        //   - 31+: android:exported must be explicit on every component with an intent-filter, and
        //          every PendingIntent must declare mutability.
        //   - 34:  foreground services MUST declare a foregroundServiceType or the app is killed
        //          with MissingForegroundServiceTypeException.
        // The manifest below is already written to satisfy 30/31/34, so a high-targetSdk build is
        // valid; only the runtime *behaviour* differs, which is exactly why 28 stays the default.
        //
        // Override only for install-compatibility experiments on OEMs that block low targetSdk
        // (INSTALL_FAILED_DEPRECATED_SDK_VERSION), e.g. `-PstrideTargetSdk=35`. compileSdk must be
        // >= the chosen targetSdk (it tracks the Flutter SDK, currently well above 35).
        val strideTargetSdk = (project.findProperty("strideTargetSdk") as String?)?.toIntOrNull() ?: 28
        targetSdk = strideTargetSdk
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
