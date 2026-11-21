// Top-level build file untuk AnimeLovers Revival (animelovers-lawas).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
    // v2.0.11: plugin google-services (memproses google-services.json
    // jadi resource Firebase). Versi 4.4.0 (lihat libs.versions.toml).
    alias(libs.plugins.google.services) apply false
}
