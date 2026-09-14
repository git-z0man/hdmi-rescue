plugins {
    id("com.android.application") version "9.4.0"
}

// Deliberately no Compose, no tv-material, no dependency at all. This is a handful of buttons on
// a dark screen that have to work when nothing else on the television does; a library would be
// weight without return. Plain `Button`s are focusable out of the box, and a D-pad needs no more.
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

    buildTypes {
        release {
            // Signed with the debug key on purpose: none of this goes to a store, and an unsigned
            // build could not be installed at all.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
