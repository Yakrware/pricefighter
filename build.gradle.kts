// Top-level build file. Plugin versions are declared in gradle/libs.versions.toml
// and applied (without versions) in the module build files.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
