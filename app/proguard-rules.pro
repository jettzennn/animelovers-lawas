# ProGuard / R8 untuk AnimeLovers Revival.
# Media3 + Retrofit + Room + Coil: aturan standar library (sudah dibundel di AAR masing-masing).
# Yang ditahan di sini hanya yang dipakai lewat refleksi / serialisasi nama.

# Model API (Gson membaca nama field) — jangan obfuscate nama field model.
-keepclassmembers class animelovers.rebornhd.data.model.** { *; }
-keepclassmembers class animelovers.rebornhd.data.api.** { *; }

# Room: entity + DAO harus utuh.
-keep class animelovers.rebornhd.data.local.entity.** { *; }

# Media3: jangan hapus kelas yang dipakai lewat refleksi di DefaultRenderersFactory.
-keep class androidx.media3.exoplayer.** { *; }
