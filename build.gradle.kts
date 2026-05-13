plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library")     version "8.5.2" apply false
    // Kotlin 2.0.21 + matching Compose Compiler plugin. We were on
    // 1.9.24 with `composeOptions { kotlinCompilerExtensionVersion =
    // "1.5.14" }`, but Haze (and most modern Compose libs) ship class
    // files compiled with Kotlin 2.x metadata, which 1.9.x KSP refuses
    // to read. Bumping to 2.0.21 (stable, K2) is the smallest possible
    // change that unblocks that.
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
    id("com.google.devtools.ksp") version "2.1.21-2.0.2" apply false
}
