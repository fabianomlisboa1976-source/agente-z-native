// Top-level build file. Plugins are applied in module-level build.gradle.kts.
// Note: AGP 9.0+ ships with built-in Kotlin support — kotlin.android plugin is
// not needed. Only the Compose Compiler plugin is required for Compose code gen.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
