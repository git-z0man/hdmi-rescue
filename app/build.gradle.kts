plugins {
    id("com.android.application") version "9.4.0"
}

// No Compose, no tv-material: this is a handful of buttons on a dark screen that have to work when
// nothing else on the television does, and plain `Button`s are focusable out of the box. The one
// dependency is dadb, for the repair itself — speaking ADB by hand means an RSA handshake and a
// packet format, which is a great deal more code than a library.
android {
    // Renamed from the author-named "braviafix" id it first shipped under. An install of this
    // build does not upgrade that copy: uninstall the old one on the television first.
    namespace = "tv.hdmirescue.app"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "tv.hdmirescue.app"
        // The Bravia is 31; 28 keeps a Fire TV Stick in range should it ever show the same
        // illness.
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
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
