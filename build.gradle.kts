plugins {
    // AGP 9 несе підтримку Kotlin у собі — окремий плагін kotlin-android не потрібен.
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
