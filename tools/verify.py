#!/usr/bin/env python3
"""Verifikasi statis proyek AnimLovers (tanpa Android SDK / tanpa compile).

Memeriksa:
 1. Semua file XML di res/ + AndroidManifest.xml well-formed.
 2. Setiap referensi @string/@drawable/@mipmap/@color/@style/@layout/@id
    dan ?attr/ di XML merujuk resource yang ada.
 3. Setiap referensi R.* di Kotlin merujuk resource yang ada.
 4. Setiap *Binding yang dipakai di Kotlin punya layout XML.
 5. Setiap activity di AndroidManifest punya kelas Kotlin.
 6. Keseimbangan kurung di file Gradle Kotlin DSL (smoke check).
 7. Layout/menu yang tidak dipakai di mana pun (info saja).
 8. Referensi kelas *Activity/*Fragment/*Adapter/*Repository/*Api.
 9. prefs.* dan Constants.* merujuk properti yang ada.
10. Field binding.* cocok dengan id layout.
11. return@X hanya memakai label penerima lambda yang dikenal
    (allowlist RETURN_LABEL_ALLOWLIST) — penjaga bug CI v2 run 44081138181.

Keluar 0 bila tidak ada ERROR, 1 bila ada.
Referensi library (media3-ui) di-allowlist: exo_icon_*.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
JAVA = ROOT / "app" / "src" / "main" / "java"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"

errors: list[str] = []
warnings: list[str] = []

# Referensi yang disediakan library, bukan res/ proyek.
LIB_DRAWABLE_PREFIXES = ("exo_icon_",)
# Atribut tema yang didefinisikan library AppCompat (bukan attrs.xml proyek).
LIB_ATTRS = {
    "colorControlNormal", "colorControlActivated", "colorControlHighlight",
    "colorPrimary", "colorPrimaryDark", "colorAccent", "colorButtonNormal",
    "windowBackground", "textColorPrimary", "textColorSecondary",
    "actionBarSize", "selectableItemBackground", "selectableItemBackgroundBorderless",
}

# ---------- 1. Kumpulkan resource yang didefinisikan ----------

defined: dict[str, set[str]] = {
    "string": set(), "color": set(), "style": set(), "attr": set(),
    "drawable": set(), "mipmap": set(), "layout": set(), "menu": set(),
    "id": set(), "xml": set(),
}

# values/*.xml
for f in (RES / "values").glob("*.xml"):
    try:
        tree = ET.parse(f)
    except ET.ParseError as e:
        errors.append(f"XML tidak well-formed: {f.relative_to(ROOT)}: {e}")
        continue
    for el in tree.getroot():
        name = el.get("name")
        if not name:
            continue
        if el.tag == "string":
            defined["string"].add(name)
        elif el.tag == "color":
            defined["color"].add(name)
        elif el.tag == "style":
            defined["style"].add(name)
        elif el.tag == "attr":
            defined["attr"].add(name)

# file-based resources
for kind in ("drawable", "mipmap", "layout", "menu", "xml"):
    for d in RES.glob(f"{kind}*"):
        if not d.is_dir():
            continue
        for f in d.iterdir():
            if f.is_file():
                defined[kind].add(f.stem)

# id dari semua layout + menu + manifest (android:id="@+id/..." / "@+id/...")
id_pattern = re.compile(r"@\+id/([A-Za-z0-9_]+)")
for f in list(RES.rglob("*.xml")) + [MANIFEST]:
    text = f.read_text(encoding="utf-8", errors="replace")
    for m in id_pattern.finditer(text):
        defined["id"].add(m.group(1))

# ---------- 2. XML well-formed + referensi ----------

ref_pattern = re.compile(r"@([a-z]+)/([A-Za-z0-9_.]+)")
attr_pattern = re.compile(r"\?attr/([A-Za-z0-9_]+)")

xml_files = list(RES.rglob("*.xml")) + [MANIFEST]
for f in xml_files:
    rel = f.relative_to(ROOT)
    try:
        ET.parse(f)
    except ET.ParseError as e:
        errors.append(f"XML tidak well-formed: {rel}: {e}")
        continue
    text = f.read_text(encoding="utf-8", errors="replace")
    for kind, name in ref_pattern.findall(text):
        if kind == "android":
            continue  # resource framework
        if kind not in defined:
            continue  # mis. @null, @color/... sudah dicakup
        if kind == "drawable" and name.startswith(LIB_DRAWABLE_PREFIXES):
            continue
        # style dengan titik: "ExoMediaButton.Play" didefinisikan utuh
        if name not in defined[kind]:
            errors.append(f"{rel}: referensi @{kind}/{name} tidak ada")
    for name in attr_pattern.findall(text):
        if name not in defined["attr"] and name not in LIB_ATTRS:
            errors.append(f"{rel}: referensi ?attr/{name} tidak ada")

# ---------- 3. Referensi R.* di Kotlin ----------

r_pattern = re.compile(r"R\.(string|drawable|mipmap|color|style|layout|menu|id)\.([A-Za-z0-9_]+)")
binding_pattern = re.compile(r"([A-Z][A-Za-z0-9]+Binding)\b")
kt_files = list(JAVA.rglob("*.kt"))
used_layouts: set[str] = set()
used_bindings: set[str] = set()
for f in kt_files:
    rel = f.relative_to(ROOT)
    text = f.read_text(encoding="utf-8")
    for kind, name in r_pattern.findall(text):
        if kind == "drawable" and name.startswith(LIB_DRAWABLE_PREFIXES):
            continue
        if kind == "layout":
            used_layouts.add(name)
        if name not in defined[kind]:
            errors.append(f"{rel}: referensi R.{kind}.{name} tidak ada")
    for b in binding_pattern.findall(text):
        used_bindings.add(b)

# Binding -> layout: ActivityDetailBinding <- activity_detail.xml
def binding_to_layout(binding: str) -> str:
    base = binding[: -len("Binding")]
    s1 = re.sub(r"(.)([A-Z][a-z]+)", r"\1_\2", base)
    return re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", s1).lower()

for b in sorted(used_bindings):
    layout = binding_to_layout(b)
    if layout not in defined["layout"]:
        errors.append(f"Binding {b} dipakai tapi layout {layout}.xml tidak ada")

# ---------- 4. Activity manifest vs kelas ----------

manifest_text = MANIFEST.read_text(encoding="utf-8")
activity_names = re.findall(
    r'<activity[^>]*android:name="([^"]+)"', manifest_text, re.S
)
kt_classes = set()
for f in kt_files:
    kt_classes.add(f.stem)
for name in activity_names:
    short = name.split(".")[-1]
    if short not in kt_classes:
        errors.append(f"Manifest: activity {name} tidak punya kelas Kotlin")

# ---------- 5. Keseimbangan kurung file Gradle ----------

for f in [ROOT / "settings.gradle.kts", ROOT / "build.gradle.kts",
          ROOT / "app" / "build.gradle.kts"]:
    text = f.read_text(encoding="utf-8")
    # buang string literal sederhana supaya kurung di string tidak dihitung
    text = re.sub(r'"(?:\\.|[^"\\])*"', '""', text)
    text = re.sub(r"'(?:\\.|[^'\\])*'", "''", text)
    for open_c, close_c in (("{", "}"), ("(", ")"), ("[", "]")):
        if text.count(open_c) != text.count(close_c):
            errors.append(
                f"{f.relative_to(ROOT)}: kurung {open_c}{close_c} tidak seimbang "
                f"({text.count(open_c)} vs {text.count(close_c)})"
            )

# ---------- 6. Info: layout/menu yang tidak dipakai ----------

# Layout terpakai bila: dirujuk R.layout.*, dirujuk @layout/* di XML,
# ATAU Binding-nya dipakai di Kotlin.
for b in used_bindings:
    used_layouts.add(binding_to_layout(b))

for layout in sorted(defined["layout"] - used_layouts):
    # dipakai lewat controller_layout_id / include di XML?
    used_in_xml = False
    for f in RES.rglob("*.xml"):
        if f"@layout/{layout}" in f.read_text(encoding="utf-8", errors="replace"):
            used_in_xml = True
            break
    if not used_in_xml:
        warnings.append(f"Layout tidak dipakai di Kotlin/XML: {layout}.xml")

# ---------- 7. Kotlin: keseimbangan kurung (string/komentar dibuang) ----------

def strip_kotlin(text: str) -> str:
    text = re.sub(r'"(?:\\.|[^"\\])*"(?:\s*\n\s*"(?:\\.|[^"\\])*")*', '""', text)
    text = re.sub(r"'''(?:.|\n)*?'''", "''", text)
    text = re.sub(r"//[^\n]*", "", text)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return text

for f in kt_files:
    rel = f.relative_to(ROOT)
    text = strip_kotlin(f.read_text(encoding="utf-8"))
    for open_c, close_c in (("{", "}"), ("(", ")"), ("[", "]")):
        if text.count(open_c) != text.count(close_c):
            errors.append(
                f"{rel}: kurung {open_c}{close_c} tidak seimbang "
                f"({text.count(open_c)} vs {text.count(close_c)})"
            )

# ---------- 8. Referensi kelas *Activity/*Fragment/*Adapter/*Repository/*Api ----------

defined_classes: set[str] = set()
for f in kt_files:
    text = f.read_text(encoding="utf-8")
    for m in re.finditer(
        r"(?:^|\n)\s*(?:public\s+|internal\s+|private\s+|protected\s+|open\s+|"
        r"abstract\s+|data\s+|sealed\s+|inner\s+)*"
        r"(?:class|object|interface)\s+(\w+)",
        text,
    ):
        defined_classes.add(m.group(1))

LIB_CLASSES = {
    "AppCompatActivity", "Fragment", "FragmentStateAdapter", "ConcatAdapter",
    "ArrayAdapter", "RecyclerView", "ListAdapter", "ViewModel", "UnstableApi",
}
class_ref = re.compile(r"\b([A-Z][A-Za-z0-9]*?(?:Activity|Fragment|Adapter|Repository|Api))\b")
for f in kt_files:
    rel = f.relative_to(ROOT)
    text = f.read_text(encoding="utf-8")
    for name in set(class_ref.findall(text)):
        if name not in defined_classes and name not in LIB_CLASSES:
            errors.append(f"{rel}: kelas {name} dirujuk tapi tidak didefinisikan")

# ---------- 9. prefs.* dan Constants.* ----------

prefs_file = JAVA / "animelovers" / "rebornhd" / "util" / "Prefs.kt"
prefs_text = prefs_file.read_text(encoding="utf-8")
prefs_props = set(re.findall(r"^\s*(?:var|val)\s+(\w+)", prefs_text, re.M))
prefs_props |= set(re.findall(r"fun\s+(clear\w+)\s*\(", prefs_text))
constants_file = JAVA / "animelovers" / "rebornhd" / "util" / "Constants.kt"
constants_text = constants_file.read_text(encoding="utf-8")
constants_names = set(re.findall(r"^\s*(?:const\s+)?val\s+(\w+)", constants_text, re.M))
# Catatan: LOCKED_GENRES adalah `val` biasa (Set tidak bisa `const`) —
# regex lama yang hanya mengenali `const val` salah melaporkannya hilang.

for f in kt_files:
    rel = f.relative_to(ROOT)
    text = f.read_text(encoding="utf-8")
    # Prefs.kt sendiri memakai field SharedPreferences bernama `prefs` — lewati.
    if f.name != "Prefs.kt":
        for prop in set(re.findall(r"prefs\.(\w+)", text)):
            if prop not in prefs_props:
                errors.append(f"{rel}: prefs.{prop} tidak ada di Prefs.kt")
    for name in set(re.findall(r"Constants\.(\w+)", text)):
        if name not in constants_names:
            errors.append(f"{rel}: Constants.{name} tidak ada di Constants.kt")

# ---------- 10. Field binding vs id layout ----------

def camelize(resource_id: str) -> str:
    parts = resource_id.split("_")
    out = parts[0]
    for part in parts[1:]:
        out += part[:1].upper() + part[1:] if part else ""
    return out[:1].lower() + out[1:] if out else out

all_binding_fields: set[str] = {"root"}
for f in RES.rglob("*.xml"):
    text = f.read_text(encoding="utf-8", errors="replace")
    for m in re.finditer(r"android:id=\"@\+id/([A-Za-z0-9_]+)\"", text):
        all_binding_fields.add(camelize(m.group(1)))

binding_field_ref = re.compile(r"(?:\bbinding|\bholder\.binding|\bb)\.([A-Za-z][A-Za-z0-9_]*)")
for f in kt_files:
    rel = f.relative_to(ROOT)
    text = strip_kotlin(f.read_text(encoding="utf-8"))
    for field in set(binding_field_ref.findall(text)):
        if field not in all_binding_fields:
            errors.append(f"{rel}: binding.{field} tidak cocok dengan id layout mana pun")

# ---------- 11. return@X: label harus nama penerima lambda yang dikenal ----------
#
# Pelajaran CI v2 (run 44081138181, commit 66de29a): `return@BrowseFragment`
# di properti lambda BrowseFragment.kt:86 tidak valid — label itu hanya ada
# bila lambda dioper LANGSUNG ke fungsi/konstruktor bernama X (mis.
# `FavoritesHeaderAdapter { ... return@FavoritesHeaderAdapter }`).
# Lambda yang di-assign ke properti (bukan dioper ke fungsi bernama X)
# TIDAK punya label itu, dan kotlinc gagal dengan "Unresolved reference".
# Cek ini menangkap bug kelas itu di verify.py sebelum push, bukan di CI.
# ATURAN: label baru WAJIB didaftarkan di RETURN_LABEL_ALLOWLIST di bawah.
RETURN_LABEL_ALLOWLIST = {
    # fungsi standar Kotlin / coroutine
    "withContext", "launch",
    # listener Android
    "setOnClickListener", "setOnCheckedChangeListener",
    # konstruktor adapter yang menerima lambda onClick
    "FavoritesHeaderAdapter", "BrowseGridAdapter",
}
return_label_pattern = re.compile(r"return@([A-Za-z][A-Za-z0-9_]*)")
for f in kt_files:
    rel = f.relative_to(ROOT)
    text = strip_kotlin(f.read_text(encoding="utf-8"))
    for label in set(return_label_pattern.findall(text)):
        if label not in RETURN_LABEL_ALLOWLIST:
            errors.append(
                f"{rel}: return@{label} tidak valid — label '{label}' bukan "
                f"penerima lambda yang dikenal; daftarkan di "
                f"RETURN_LABEL_ALLOWLIST bila memang valid, atau perbaiki "
                f"dengan .let / label lambda"
            )

# ---------- 12. Gson/TypeToken tahan R8 (v2.0.4) ----------
# Subclass TypeToken anonim DILARANG di kode produksi: R8 Full Mode
# menggabungkannya + menghapus generic Signature → constructor TypeToken
# melempar IllegalStateException di APK Release (bug v2.0.0–v2.0.3:
# Beranda + Jelajahi "Gagal memuat" padahal server sehat). Tipe Gson
# WAJIB dibangun via TypeToken.getParameterized.
for f in kt_files:
    rel = f.relative_to(ROOT)
    text = f.read_text(encoding="utf-8")
    if re.search(r"object\s*:\s*TypeToken", text):
        errors.append(
            f"{rel}: subclass TypeToken anonim dilarang — pakai "
            f"TypeToken.getParameterized (R8 Full Mode menghapus generic "
            f"Signature subclass anonim → IllegalStateException di Release)"
        )
_repo_file = JAVA / "animelovers/rebornhd/data/repo/SankaRepository.kt"
if "getParameterized" not in _repo_file.read_text(encoding="utf-8"):
    errors.append(
        "SankaRepository.kt: parseEnvelope wajib memakai "
        "TypeToken.getParameterized (bukan subclass TypeToken anonim)"
    )
# Class anotasi Gson WAJIB ditahan di proguard-rules.pro (v2.0.4, temuan
# Cari Bug): -keepattributes + -keepclassmembers @SerializedName saja
# TIDAK cukup — bila class com.google.gson.annotations.SerializedName
# ikut ter-shake, semua @SerializedName terbuang dan Gson fallback ke
# nama field Java (nomor episode → 0, next/prev hilang, gerbang Ecchi
# di daftar episode jebol).
_proguard = ROOT / "app" / "proguard-rules.pro"
if not re.search(
    r"-keep\s+class\s+com\.google\.gson\.annotations",
    _proguard.read_text(encoding="utf-8"),
):
    errors.append(
        "app/proguard-rules.pro: wajib ada keep rule untuk "
        "com.google.gson.annotations (class anotasi @SerializedName "
        "harus ditahan — tanpa ini R8 menghapus semua @SerializedName "
        "dari dex Release)"
    )

# ---------- 13. Gerbang v2.0.5 ----------
# 13a. Dialog mode DIHAPUS (D1: Unduh pindah ke tombol di kontrol player)
#      — tidak boleh ada lagi `showModeDialog` di Kotlin mana pun.
for f in kt_files:
    rel = f.relative_to(ROOT)
    text = f.read_text(encoding="utf-8")
    if "showModeDialog" in text:
        errors.append(
            f"{rel}: showModeDialog masih ada — dialog mode dihapus di "
            f"v2.0.5 (D1: Unduh pindah ke tombol di kontrol player)"
        )

# 13b. DarkAlert WAJIB bertema dialog gelap penuh (v2.0.5 #1): parent
#      BUKAN Light (strip putih di kanan dialog berasal dari latar
#      window/decor parent Light) + windowBackground gelap penuh.
_themes = RES / "values" / "themes.xml"
_themes_tree = ET.parse(_themes)
for _style in _themes_tree.getroot().iter("style"):
    if _style.get("name") != "DarkAlert":
        continue
    _parent = _style.get("parent") or ""
    if "Light" in _parent:
        errors.append(
            "themes.xml: parent DarkAlert masih Light "
            f"({_parent}) — strip putih di kanan dialog kembali; "
            "pakai Theme.AppCompat.Dialog.Alert"
        )
    _window_bg = [
        _item.text.strip() if _item.text else ""
        for _item in _style
        if _item.get("name") == "android:windowBackground"
    ]
    if not _window_bg or _window_bg[0] not in ("@color/darktheme", "#2b2a2a"):
        errors.append(
            "themes.xml: DarkAlert wajib punya android:windowBackground "
            "gelap penuh (@color/darktheme) — android:background saja "
            "hanya menggelapkan isi dialog, bukan jendela dialognya"
        )

# 13c. Skeleton shimmer Detail TERPISAH dari konten (v2.0.5 #3):
#      posterDetailView TIDAK BOLEH jadi keturunan ShimmerFrameLayout
#      (dulu shimmer membungkus seluruh konten → gelap/redup setelah
#      load), dan DetailActivity WAJIB menyembunyikan shimmer (GONE).
_detail_xml = RES / "layout" / "activity_detail.xml"
_detail_tree = ET.parse(_detail_xml)
_ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
for _shimmer in _detail_tree.getroot().iter("com.facebook.shimmer.ShimmerFrameLayout"):
    for _desc in _shimmer.iter():
        if _desc.get(f"{_ANDROID_NS}id") == "@+id/posterDetailView":
            errors.append(
                "activity_detail.xml: posterDetailView masih di dalam "
                "ShimmerFrameLayout — konten harus di luar shimmer, "
                "shimmer hanya overlay skeleton saat loading (v2.0.5 #3)"
            )
_detail_kt = (
    JAVA / "animelovers" / "rebornhd" / "ui" / "detail" / "DetailActivity.kt"
).read_text(encoding="utf-8")
if "shimmerViewContainer.visibility = View.GONE" not in _detail_kt:
    errors.append(
        "DetailActivity.kt: shimmer harus disembunyikan (View.GONE) "
        "setelah load — stopShimmer() saja tidak mematikan shimmer "
        "secara visual (v2.0.5 #3)"
    )

# 13d. Throttle Sanka TIDAK BOLEH berubah (3 pelanggaran = BAN PERMANEN)
#      + delay Splash 0 (D2) + versi rilis 2.0.5 / versionCode 10.
_constants = (
    JAVA / "animelovers" / "rebornhd" / "util" / "Constants.kt"
).read_text(encoding="utf-8")
if not re.search(r"SANKA_MIN_INTERVAL_MS\s*=\s*1_400L", _constants):
    errors.append(
        "Constants.kt: SANKA_MIN_INTERVAL_MS wajib tetap 1_400L "
        "(batas Sanka 45 req/menit — mengecilkan = risiko BAN PERMANEN)"
    )
if not re.search(r"SPLASH_DELAY_MS\s*=\s*0L", _constants):
    errors.append(
        "Constants.kt: SPLASH_DELAY_MS wajib 0L di v2.0.5 (D2 — delay "
        "Splash 2 detik dipotong)"
    )
_app_gradle = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
if not re.search(r"versionCode\s*=\s*10\b", _app_gradle):
    errors.append("app/build.gradle.kts: versionCode wajib 10 untuk v2.0.5")
if '"2.0.5"' not in _app_gradle:
    errors.append('app/build.gradle.kts: versionName wajib "2.0.5"')

# ---------- 14. Gerbang v2.0.5 — state error Detail (temuan Minor Cari Bug) ----------
# Detail gagal (belum ada data) WAJIB menampilkan state error MENETAP +
# tombol "Coba lagi" yang memanggil load() — bukan cuma toast + harap
# pengguna pull-to-refresh.
_detail_xml_text = _detail_xml.read_text(encoding="utf-8")
if "@+id/error_detail_container" not in _detail_xml_text:
    errors.append(
        "activity_detail.xml: wajib ada wadah state error Detail "
        "(@+id/error_detail_container) — Detail gagal tidak boleh "
        "hanya toast (v2.0.5, temuan Minor Cari Bug)"
    )
if "@+id/coba_lagi_button" not in _detail_xml_text:
    errors.append(
        "activity_detail.xml: wajib ada tombol Coba lagi "
        "(@+id/coba_lagi_button) di state error Detail"
    )
if "cobaLagiButton.setOnClickListener" not in _detail_kt:
    errors.append(
        "DetailActivity.kt: tombol Coba lagi wajib di-wire ke load() "
        "(cobaLagiButton.setOnClickListener)"
    )
if "errorDetailContainer.visibility = View.VISIBLE" not in _detail_kt:
    errors.append(
        "DetailActivity.kt: state error Detail wajib ditampilkan "
        "(errorDetailContainer VISIBLE) saat load gagal tanpa data"
    )
if "errorDetailContainer.visibility = View.GONE" not in _detail_kt:
    errors.append(
        "DetailActivity.kt: state error Detail wajib disembunyikan "
        "(errorDetailContainer GONE) saat load dimulai/berhasil"
    )

# ---------- 15. Gerbang v2.0.5 — tema mengikuti sistem HP (item 5) ----------
# Tema WAJIB dibaca dari uiMode sistem (HP dark → app dark, HP terang →
# app terang), BUKAN pref manual lagi; AppCompat WAJIB FOLLOW_SYSTEM;
# kedua tema + kedua style dialog WAJIB tetap ada dan terpilih lewat
# isDarkTheme yang sama (4 perbaikan #1–#4 tetap lolos di kedua tema).
_prefs_kt = (
    JAVA / "animelovers" / "rebornhd" / "util" / "Prefs.kt"
).read_text(encoding="utf-8")
if "UI_MODE_NIGHT_MASK" not in _prefs_kt or "UI_MODE_NIGHT_YES" not in _prefs_kt:
    errors.append(
        "Prefs.kt: isDarkTheme wajib dibaca dari uiMode sistem "
        "(UI_MODE_NIGHT_MASK / UI_MODE_NIGHT_YES) — tema mengikuti "
        "sistem HP (v2.0.5 item 5)"
    )
if 'theme == "1"' in _prefs_kt:
    errors.append(
        "Prefs.kt: isDarkTheme tidak boleh lagi dibaca dari pref manual "
        '"theme" ("1"/"0") — tema mengikuti sistem HP (v2.0.5 item 5)'
    )
if "val isDarkTheme" not in _prefs_kt:
    errors.append("Prefs.kt: properti isDarkTheme wajib tetap ada")
_app_kt = (
    JAVA / "animelovers" / "rebornhd" / "AnimeloversApp.kt"
).read_text(encoding="utf-8")
if "setDefaultNightMode" not in _app_kt or "MODE_NIGHT_FOLLOW_SYSTEM" not in _app_kt:
    errors.append(
        "AnimeloversApp.kt: wajib memanggil "
        "AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_FOLLOW_SYSTEM) "
        "di onCreate (v2.0.5 item 5)"
    )
_setting_kt = (
    JAVA / "animelovers" / "rebornhd" / "ui" / "settings" / "SettingActivity.kt"
).read_text(encoding="utf-8")
if "themeSwitch" in _setting_kt or "prefs.theme" in _setting_kt:
    errors.append(
        "SettingActivity.kt: switch tema manual wajib dihapus — tema "
        "mengikuti sistem HP, bukan pref manual (v2.0.5 item 5)"
    )
_base_kt = (JAVA / "animelovers" / "rebornhd" / "ui" / "BaseActivity.kt").read_text(
    encoding="utf-8"
)
for _style_ref in (
    "R.style.DarkTheme", "R.style.LightTheme",
    "R.style.DarkAlert", "R.style.LightAlert",
):
    if _style_ref not in _base_kt:
        errors.append(
            f"BaseActivity.kt: {_style_ref} wajib tetap dipakai — kedua "
            "tema + kedua dialog harus tetap ada (v2.0.5 item 5)"
        )
if "DarkTheme" not in defined["style"] or "LightTheme" not in defined["style"]:
    errors.append(
        "themes.xml: style DarkTheme + LightTheme wajib tetap ada "
        "(v2.0.5 item 5)"
    )
_manifest_text = MANIFEST.read_text(encoding="utf-8")
for _cc in re.findall(r'android:configChanges="([^"]*)"', _manifest_text):
    if "uiMode" in _cc.split("|"):
        errors.append(
            "AndroidManifest.xml: tidak boleh ada configChanges uiMode — "
            "saat tema HP berubah activity harus dibuat ulang sistem "
            "supaya tema baru diterapkan (v2.0.5 item 5)"
        )

# ---------- Laporan ----------

print(f"Resource terdefinisi: "
      f"{len(defined['string'])} string, {len(defined['color'])} color, "
      f"{len(defined['style'])} style, {len(defined['attr'])} attr, "
      f"{len(defined['drawable'])} drawable, {len(defined['mipmap'])} mipmap, "
      f"{len(defined['layout'])} layout, {len(defined['id'])} id, "
      f"{len(kt_files)} file Kotlin, {len(activity_names)} activity manifest.")
print(f"Binding dipakai: {len(used_bindings)}")
for w in warnings:
    print(f"PERINGATAN: {w}")
if errors:
    print(f"\n{len(errors)} ERROR:")
    for e in errors:
        print(f"  ERROR: {e}")
    sys.exit(1)
print("\nOK — tidak ada error.")
