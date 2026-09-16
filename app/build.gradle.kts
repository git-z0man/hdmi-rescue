plugins {
    id("com.android.application") version "9.4.0"
}

// No Compose, no tv-material: this is a handful of buttons on a dark screen that have to work when
// nothing else on the television does, and plain `Button`s are focusable out of the box. The one
// dependency is dadb, for the repair itself — speaking ADB by hand means an RSA handshake and a
// packet format, which is a great deal more code than a library.
android {
    // The namespace and application id still say "braviafix" — the name this app shipped under
    // when it lived inside the zplayer-tv repository. Keeping it means an install upgrades the
    // copy already on the television instead of leaving a second, orphaned entry next to it.
    namespace = "com.steffenzimmermann.braviafix"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.steffenzimmermann.braviafix"
        // The Bravia is 31; 28 keeps a Fire TV Stick in range should it ever show the same
        // illness.
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    // dadb publishes `org.gradle.jvm.version: 17` from 1.2.10 on; without this the dependency
    // resolves as "requires at least a Java 17 JVM".
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            // Signed with the debug key on purpose: none of this goes to a store, and an unsigned
            // build could not be installed at all.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("dev.mobile:dadb:2.0.0")
}
