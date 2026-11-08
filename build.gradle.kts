plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 Kotlin desteğini kendisi sağlar; bu satır yalnızca kullanılacak Kotlin sürümünü sabitler.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
