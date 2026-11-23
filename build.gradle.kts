plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 compiles Kotlin itself; declaring KGP here only pins its version on the classpath.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
