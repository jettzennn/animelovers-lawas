# ProGuard / R8 untuk AnimeLovers Revival.
# Media3 + Retrofit + Room + Coil: aturan standar library (sudah dibundel di AAR masing-masing).
# Yang ditahan di sini hanya yang dipakai lewat refleksi / serialisasi nama.

# Model API (Gson membaca nama field) — jangan obfuscate nama field model.
# Model Samehadaku (v2.0.7 item 16: SamehadakuSearchData,
# SamehadakuSearchItem, SamehadakuEpisodeData, SamehadakuEpisodeServersItem
# di data/model) tercakup wildcard data.** di bawah — parse-nya memakai
# TypeToken.getParameterized (bukan subclass TypeToken anonim), sama
# seperti SankaRepository (lapis kedua v2.0.4).
-keepclassmembers class animelovers.rebornhd.data.model.** { *; }
-keepclassmembers class animelovers.rebornhd.data.api.** { *; }

# Room: entity + DAO harus utuh.
-keep class animelovers.rebornhd.data.local.entity.** { *; }

# Media3: jangan hapus kelas yang dipakai lewat refleksi di DefaultRenderersFactory.
-keep class androidx.media3.exoplayer.** { *; }

# Gson + R8 (v2.0.4 — PERBAIKAN root cause "Gagal memuat" v2.0.0–v2.0.3):
# R8 Full Mode menggabungkan subclass TypeToken anonim + menghapus generic
# Signature, sehingga constructor TypeToken melempar IllegalStateException
# di APK Release (parse SankaEnvelope gagal total padahal server sehat).
# Aturan di bawah menahan Signature + class TypeToken + seluruh model data.
# Lapis kedua: SankaRepository membangun tipe via TypeToken.getParameterized
# (tanpa anonymous class), jadi parse kebal R8 meski aturan ini terlewat.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class animelovers.rebornhd.data.** { *; }
# -keepattributes *Annotation* + -keepclassmembers @SerializedName di
# bawah menahan FIELD-nya, tapi BUKAN class anotasi itu sendiri: begitu
# class com.google.gson.annotations.SerializedName ikut ter-shake oleh
# R8, semua pemakaian @SerializedName ikut terbuang dan Gson fallback
# ke nama field Java (bug Cari Bug v2.0.4: nomor episode → 0, tombol
# next/prev hilang, gerbang Ecchi di daftar episode jebol). Karena itu
# class anotasi Gson WAJIB ditahan beserta isinya.
-keep class com.google.gson.annotations.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclasseswithmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
