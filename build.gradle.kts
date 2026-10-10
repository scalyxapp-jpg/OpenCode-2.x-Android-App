// Top-level build file
plugins {
    // AGP 9 ships built-in Kotlin support, so the `org.jetbrains.kotlin.android`
    // plugin must NOT be applied any more.
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    // Dependency injection: makes the data layer swappable in tests.
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
