import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "animelovers.rebornhd"
    compileSdk = 34

    defaultConfig {
        // Nama paket diputuskan Faygo (§12 poin 3 PRD).
        applicationId = "animelovers.rebornhd"
        minSdk = 21
        targetSdk = 34
        // ATURAN TETAP: setiap rilis baru WAJIB menaikkan versionName (sama dengan tag GitHub)
        // dan versionCode (+1) — halaman Info aplikasi Android menampilkan versionName ini.
        versionCode = 5
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Keystore rilis diisi Deploy lewat GitHub Secrets (lihat .github/workflows/android-release.yml).
            // Bila file keystore.properties ada (lokal), pakai isinya; bila tidak ada, build release
            // tetap jalan unsigned di CI dan Deploy yang menandatangani.
            val keystorePropsFile = rootProject.file("keystore.properties")
            if (keystorePropsFile.exists()) {
                // JANGAN tulis java.util.Properties / java.io.FileInputStream di sini:
                // di skrip build Gradle, `java` dibaca sebagai ekstensi plugin Java,
                // bukan paket java — jadi `util`/`io` tidak ditemukan (error CI run #4).
                val props = Properties()
                props.load(FileInputStream(keystorePropsFile))
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(props.getProperty("storeFile"))
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.coil)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.shimmer)
    ksp(libs.room.compiler)
}
