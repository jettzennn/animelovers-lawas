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
        elif el.tag == "item" and el.get("type") == "id":
            # ID non-layout (res/values/ids.xml) — kunci View.setTag,
            # mis. poster_retry_action (v2.0.7 #1). aapt tetap
            # membangkitkan R.id untuk <item type="id">.
            defined["id"].add(name)

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
    """Buang string/komentar/char literal Kotlin untuk cek §7/§10/§11.

    Scanner satu-pass (bukan regex berlapis): regex berlapis salah
    pasang bila string/komentar saling berisi — mis. `\"` di dalam
    komentar baris `//` dibuka sebagai string dan menelan kode asli
    sampai kutip berikutnya (bug yang menuduh PlayerRepository.kt
    tak seimbang di v2.0.7 item 14–16), atau `//` di dalam URL
    `https://…` memotong sisa baris. Aturan yang dipakai di sini:
    `//` sampai newline, `/* … */` (tidak bersarang), string `"…"`
    (escape backslash-x, tidak melewati newline), raw string (tiga
    kutip ganda pembuka/penutup; tutup di kemunculan tiga kutip
    pertama; isi boleh memuat kutip ganda tunggal), char literal
    (petik tunggal, escape backslash-x). Newline dipertahankan supaya
    nomor baris dan pola antar-baris tidak bergeser.
    """
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            if j < 0:
                break
            out.append(
                "".join("\n" if ch == "\n" else " " for ch in text[i:j + 2])
            )
            i = j + 2
            continue
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            if j < 0:
                break
            out.append('""')
            i = j + 3
            continue
        if text[i] == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"' or text[i] == "\n":
                    break
                i += 1
            if i < n and text[i] == '"':
                i += 1
            out.append('""')
            continue
        if text[i] == "'":
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == "'" or text[i] == "\n":
                    break
                i += 1
            if i < n and text[i] == "'":
                i += 1
            continue
        out.append(text[i])
        i += 1
    return "".join(out)

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
if not re.search(r"versionCode\s*=\s*12\b", _app_gradle):
    errors.append("app/build.gradle.kts: versionCode wajib 12 untuk v2.0.7")
if '"2.0.7"' not in _app_gradle:
    errors.append('app/build.gradle.kts: versionName wajib "2.0.7"')

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

# ---------- 16. Gerbang v2.0.6 — player direct-dulu + label jujur + sortir + poster ----------
# 16a. Default hdOnly WAJIB "1" (direct dulu) — D4: player mulai dari
#      video langsung di kontrol aplikasi sendiri; embed hanya fallback.
if 'getString("hd_only", "1")' not in _prefs_kt:
    errors.append(
        'Prefs.kt: default hdOnly wajib "1" (direct dulu, D4 v2.0.6) — '
        'default "0" memilih embed WebView dulu'
    )

# 16b. TIDAK BOLEH ada hardcode "480p" di VideoPlayActivity (label kualitas
#      bilah WebView wajib dari label sumber embed, bukan literal).
_video_kt = (
    JAVA / "animelovers" / "rebornhd" / "ui" / "player" / "VideoPlayActivity.kt"
).read_text(encoding="utf-8")
if '"480p"' in _video_kt:
    errors.append(
        'VideoPlayActivity.kt: hardcode "480p" dilarang — label kualitas '
        "bilah WebView wajib dari source.qualityLabel (v2.0.6 #2)"
    )

# 16c. Label "Movie" untuk film (type == "Movie") — string + pemakaian.
if "movie_label" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: label Movie wajib ada (movie_label) — "
        "film menampilkan \"Movie\", bukan \"Episode N\" (v2.0.6 #2)"
    )
if "EXTRA_MOVIE" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: flag Movie wajib dibawa lewat intent "
        "(EXTRA_MOVIE) dari Detail/Episode (v2.0.6 #2)"
    )

# 16d. Sortir pencarian client-side (v2.0.6 #3) — fungsi + pemakaian di search().
_repo_kt = (
    JAVA / "animelovers" / "rebornhd" / "data" / "repo" / "SankaRepository.kt"
).read_text(encoding="utf-8")
if "fun sortSearchResults" not in _repo_kt:
    errors.append(
        "SankaRepository.kt: fungsi sortSearchResults wajib ada "
        "(judul persis → TV → Special/Movie, v2.0.6 #3)"
    )
if "sortSearchResults(query" not in _repo_kt:
    errors.append(
        "SankaRepository.kt: search() wajib memakai sortSearchResults "
        "sebelum menyerahkan hasil ke UI (v2.0.6 #3)"
    )

# 16e. Poster (v2.0.6 #4): loadPoster WAJIB pakai size() + error BERBEDA
#      dari placeholder (chibi loading vs ikon netral gagal).
_adapters_kt = (
    JAVA / "animelovers" / "rebornhd" / "ui" / "adapter" / "AnimeAdapters.kt"
).read_text(encoding="utf-8")
if "size(widthPx, heightPx)" not in _adapters_kt:
    errors.append(
        "AnimeAdapters.kt: loadPoster wajib memakai size() (decode sebesar "
        "tampilan, bukan ukuran penuh — v2.0.6 #4)"
    )
if "error(R.drawable.ic_poster_error)" not in _adapters_kt:
    errors.append(
        "AnimeAdapters.kt: drawable error poster wajib ic_poster_error "
        "(BERBEDA dari placeholder chibi — v2.0.6 #4)"
    )
if "error(R.drawable.splashh)" in _adapters_kt:
    errors.append(
        "AnimeAdapters.kt: error poster tidak boleh lagi splashh (chibi) — "
        "loading vs gagal harus bisa dibedakan (v2.0.6 #4)"
    )
if "ic_poster_error" not in defined["drawable"]:
    errors.append(
        "res/drawable: ic_poster_error.xml wajib ada "
        "(ikon netral poster gagal — v2.0.6 #4)"
    )

# 16f. Throttle Sanka TIDAK BOLEH berubah (re-assert §13d — 3 pelanggaran
#      = BAN PERMANEN; patch ini tidak boleh menyentuhnya).
if not re.search(r"SANKA_MIN_INTERVAL_MS\s*=\s*1_400L", _constants):
    errors.append(
        "Constants.kt: SANKA_MIN_INTERVAL_MS wajib tetap 1_400L "
        "(v2.0.6 #7 tidak boleh mengecilkan throttle)"
    )

# 16g. Render Beranda dari cache dulu (v2.0.6 #7): homeCached() + pakai di Home.
if "fun homeCached()" not in _repo_kt:
    errors.append(
        "SankaRepository.kt: homeCached() wajib ada (baca cache home tanpa "
        "jaringan — v2.0.6 #7)"
    )
_home_kt = (
    JAVA / "animelovers" / "rebornhd" / "ui" / "main" / "home" / "HomeFragment.kt"
).read_text(encoding="utf-8")
if "homeCached()" not in _home_kt:
    errors.append(
        "HomeFragment.kt: load() wajib render dari homeCached() dulu "
        "sebelum fetch segar (v2.0.6 #7)"
    )

# 16h. Perilaku buffering ala asli (v2.0.6 #5) + label dari tinggi video
#      aktual (v2.0.6 #2, onVideoSizeChanged).
if "updateBufferingUi" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: updateBufferingUi wajib ada (overlay "
        "next/prev tampil, tombol + label sembunyi saat BUFFERING — v2.0.6 #5)"
    )
if "onVideoSizeChanged" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: onVideoSizeChanged wajib ada (label "
        '"v720p" dari tinggi video aktual — v2.0.6 #2)'
    )

# 16i. Timeout Coil lebih panjang (v2.0.6 #4): ImageLoaderFactory dengan
#      readTimeout 30 detik (konsisten dengan SANKA_READ_TIMEOUT_S).
if "ImageLoaderFactory" not in _app_kt or "readTimeout(30" not in _app_kt:
    errors.append(
        "AnimeloversApp.kt: wajib ImageLoaderFactory dengan readTimeout "
        "30 detik untuk Coil (v2.0.6 #4)"
    )

# 16j. Anti-flip (v2.0.6 #1b): retry load direct lebih sabar — policy
#      kustom 8× retry internal (default Media3: 3×) + dipasang di
#      MediaSourceFactory setupPlayer, supaya buffering lambat tidak
#      langsung jadi error lalu jatuh ke embed.
if "DirectLoadErrorHandlingPolicy" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: DirectLoadErrorHandlingPolicy wajib ada "
        "(retry load direct 8×, bukan default Media3 3× — v2.0.6 #1b)"
    )
if "setLoadErrorHandlingPolicy" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: policy retry wajib dipasang lewat "
        "DefaultMediaSourceFactory.setLoadErrorHandlingPolicy (v2.0.6 #1b)"
    )
if "MIN_DIRECT_LOAD_RETRIES = 8" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: MIN_DIRECT_LOAD_RETRIES wajib 8 "
        "(v2.0.6 #1b)"
    )

# 16k. Timeout HTTP video direct lebih panjang (v2.0.6 #1b): connect 15 dtk
#      / read 30 dtk (default Media3: 8/8 dtk — sekali read macet 8 dtk,
#      error langsung naik ke onPlayerError lalu tryNextCandidate jatuh
#      ke embed = flip yang dilaporkan Faygo).
if "setConnectTimeoutMs(15_000)" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: connectTimeout video direct wajib 15_000 ms "
        "(v2.0.6 #1b)"
    )
if "setReadTimeoutMs(30_000)" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: readTimeout video direct wajib 30_000 ms "
        "(sama seperti timeout transport Sanka — v2.0.6 #1b)"
    )

# 16l. TIDAK BOLEH ada flip otomatis Media3 → WebView (v2.0.6 #1b, D4b):
#      jalur kandidat-berikut WAJIB lewat playNextOrAsk — bila kandidat
#      berikut adalah embed, tampil dialog tanya dulu; hanya ketukan
#      eksplisit "Putar lewat website" yang boleh pindah ke website.
if "playNextOrAsk" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: playNextOrAsk wajib ada (gerbang "
        "kandidat-berikut — embed tidak boleh dimainkan langsung, v2.0.6 #1b)"
    )
if "showWebViewFallbackDialog" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: showWebViewFallbackDialog wajib ada "
        "(tanya pengguna sebelum pindah ke website — v2.0.6 #1b)"
    )
if "playCandidate(nextIndex" in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: playCandidate(nextIndex...) langsung "
        "dilarang — jalur tryNextCandidate wajib lewat playNextOrAsk "
        "(v2.0.6 #1b)"
    )
if "webview_fallback_judul" not in defined["string"]:
    errors.append(
        "strings.xml: webview_fallback_judul wajib ada (judul dialog "
        "tanya-fallback-ke-WebView — v2.0.6 #1b)"
    )
if "putar_lewat_website" not in defined["string"]:
    errors.append(
        "strings.xml: putar_lewat_website wajib ada (tombol eksplisit "
        "pindah ke website — v2.0.6 #1b)"
    )

# ---------- 17. Gerbang v2.0.7 ----------
# 17a. Poster v10→v11 (riset Q1): tulis ulang host HANYA di jalur poster —
#      model posterUrl (3 getter) + loadPoster + pemuatan langsung
#      (Riwayat/Detail/player). URL non-poster (token stream, dsb.) TIDAK
#      boleh lewat fungsi ini.
_model_kt = (
    JAVA / "animelovers" / "rebornhd" / "data" / "model" / "SankaAnime.kt"
).read_text(encoding="utf-8")
if "fun rewritePosterHost" not in _model_kt:
    errors.append(
        "SankaAnime.kt: fungsi rewritePosterHost wajib ada "
        "(tulis ulang v10.animekompi.sbs → v11 — v2.0.7 #1)"
    )
if _model_kt.count('rewritePosterHost(poster ?: image ?: cover ?: "")') != 3:
    errors.append(
        "SankaAnime.kt: ketiga getter posterUrl (SankaAnime / "
        "SankaEpisodeItem / SankaDetail) wajib memakai rewritePosterHost "
        "(v2.0.7 #1)"
    )
if '"v11.animekompi.sbs"' not in _model_kt:
    errors.append(
        "SankaAnime.kt: rewritePosterHost wajib menulis ulang ke "
        "v11.animekompi.sbs (v2.0.7 #1)"
    )
if "rewritePosterHost(url)" not in _adapters_kt:
    errors.append(
        "AnimeAdapters.kt: loadPoster wajib menulis ulang URL poster "
        "(lapis kedua — v2.0.7 #1)"
    )
_history_kt = (
    JAVA / "animelovers" / "rebornhd" / "ui" / "adapter" / "HistoryAdapters.kt"
).read_text(encoding="utf-8")
if "rewritePosterHost(item.idposter)" not in _history_kt:
    errors.append(
        "HistoryAdapters.kt: poster riwayat (baris lama di DB bisa v10) "
        "wajib ditulis ulang saat dimuat (v2.0.7 #1)"
    )
if "rewritePosterHost(animePoster)" not in _detail_kt:
    errors.append(
        "DetailActivity.kt: poster Detail wajib ditulis ulang "
        "(v2.0.7 #1)"
    )
if "rewritePosterHost(animePoster)" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: poster header player wajib ditulis ulang "
        "(v2.0.7 #1)"
    )

# 17b. Retry poster terbatas 1–2× + ketuk-coba-lagi (v2.0.7 #1).
if "PosterRetryInterceptor" not in _app_kt:
    errors.append(
        "AnimeloversApp.kt: PosterRetryInterceptor wajib ada "
        "(Coil 2.5.0 tidak retry otomatis — v2.0.7 #1)"
    )
if "MAX_POSTER_RETRIES = 1" not in _app_kt:
    errors.append(
        "AnimeloversApp.kt: retry poster wajib terbatas 1× "
        "(v2.0.7 #1 — jangan menahan antrean)"
    )
if "addInterceptor(PosterRetryInterceptor())" not in _app_kt:
    errors.append(
        "AnimeloversApp.kt: interceptor retry wajib dipasang di "
        "OkHttpClient Coil (v2.0.7 #1)"
    )
if "poster_retry_action" not in defined["id"]:
    errors.append(
        "res/values/ids.xml: id poster_retry_action wajib ada "
        "(kunci tag ketuk-coba-lagi — v2.0.7 #1)"
    )
if "onPosterClick" not in _adapters_kt:
    errors.append(
        "AnimeAdapters.kt: loadPoster wajib mendukung ketuk-coba-lagi "
        "(onPosterClick — v2.0.7 #1)"
    )

# 17c. Prioritas poster terlihat (v2.0.7 #1): maxRequestsPerHost Coil 2–3.
if "maxRequestsPerHost = 3" not in _app_kt:
    errors.append(
        "AnimeloversApp.kt: maxRequestsPerHost Coil wajib 3 "
        "(poster terlihat selesai dulu — v2.0.7 #1)"
    )

# 17d. Mode gelap sadar-tema (v2.0.7 #2): colorControlNormal putih di
#      DarkTheme + AppBarOverlay sadar-tema.
_themes_xml = (RES / "values" / "themes.xml").read_text(encoding="utf-8")
if 'name="colorControlNormal">@color/putih' not in _themes_xml:
    errors.append(
        "themes.xml: DarkTheme wajib menimpa colorControlNormal ke putih "
        "(akar ikon hitam di mode gelap — v2.0.7 #2)"
    )
if "AppTheme.AppBarOverlay.Dark" not in defined["style"]:
    errors.append(
        "themes.xml: style AppTheme.AppBarOverlay.Dark wajib ada "
        "(overlay AppBar gelap — v2.0.7 #2)"
    )
if "appBarOverlay" not in defined["attr"]:
    errors.append(
        "attrs.xml: attr appBarOverlay wajib ada "
        "(overlay AppBar sadar-tema — v2.0.7 #2)"
    )
_browse_xml = (
    RES / "layout" / "fragment_browse.xml"
).read_text(encoding="utf-8")
if "?attr/appBarOverlay" not in _browse_xml:
    errors.append(
        "fragment_browse.xml: AppBarLayout wajib memakai "
        "?attr/appBarOverlay (bukan satu style mati — v2.0.7 #2)"
    )

# 17e. Panah kembali di-tint ?attr/colorControlNormal (v2.0.7 #2):
#      helper + dipakai 3 layar; tidak boleh lagi setNavigationIcon
#      dengan drawable hitam keras tanpa tint.
_extensions_kt = (
    JAVA / "animelovers" / "rebornhd" / "util" / "Extensions.kt"
).read_text(encoding="utf-8")
if "fun Toolbar.setTintedNavigationIcon" not in _extensions_kt:
    errors.append(
        "Extensions.kt: helper Toolbar.setTintedNavigationIcon wajib ada "
        "(panah kembali di-tint tema aktif — v2.0.7 #2)"
    )
if "colorControlNormal" not in _extensions_kt:
    errors.append(
        "Extensions.kt: tint panah kembali wajib dari "
        "?attr/colorControlNormal (v2.0.7 #2)"
    )
_episode_kt = (
    JAVA / "animelovers" / "rebornhd" / "ui" / "episode" / "EpisodeActivity.kt"
).read_text(encoding="utf-8")
_lainnya_kt = (
    JAVA / "animelovers" / "rebornhd" / "ui" / "episode" / "LainnyaActivity.kt"
).read_text(encoding="utf-8")
for _name, _text in (
    ("EpisodeActivity.kt", _episode_kt),
    ("LainnyaActivity.kt", _lainnya_kt),
    ("SettingActivity.kt", _setting_kt),
):
    if "setTintedNavigationIcon(R.drawable.ic_arrow_back_black_24dp)" not in _text:
        errors.append(
            f"{_name}: panah kembali toolbar wajib memakai "
            "setTintedNavigationIcon (v2.0.7 #2)"
        )
    if "setNavigationIcon(R.drawable.ic_arrow_back_black_24dp)" in _text:
        errors.append(
            f"{_name}: setNavigationIcon dengan drawable hitam keras "
            "dilarang — pakai setTintedNavigationIcon (v2.0.7 #2)"
        )

# 17f. Cache home di disk (v2.0.7 #3): TTL 10 menit + render-dari-disk
#      di HomeFragment + fetch segar paksa (homeFresh).
if "homeDiskFile" not in _repo_kt:
    errors.append(
        "SankaRepository.kt: homeDiskFile wajib ada "
        "(cache disk Beranda — v2.0.7 #3)"
    )
if "fun homeDiskCached()" not in _repo_kt:
    errors.append(
        "SankaRepository.kt: homeDiskCached() wajib ada "
        "(baca cache disk tanpa jaringan — v2.0.7 #3)"
    )
if "fun homeFresh()" not in _repo_kt:
    errors.append(
        "SankaRepository.kt: homeFresh() wajib ada "
        "(fetch segar paksa setelah render dari disk — v2.0.7 #3)"
    )
if "home_cache.json" not in _app_kt:
    errors.append(
        "AnimeloversApp.kt: file cache disk wajib home_cache.json di "
        "filesDir (v2.0.7 #3)"
    )
if "homeDiskCached()" not in _home_kt:
    errors.append(
        "HomeFragment.kt: load() wajib render dari homeDiskCached() bila "
        "memori kosong (bukaan dingin — v2.0.7 #3)"
    )
if "homeFresh()" not in _home_kt:
    errors.append(
        "HomeFragment.kt: load() wajib memanggil homeFresh() bila data "
        "tampil bukan dari memori segar (v2.0.7 #3)"
    )

# 17g. Visibility-gating poster Jelajahi (v2.0.7 #4): muat pertama hanya
#      saat tab dipilih (onResume + penanda sekali); offscreenPageLimit
#      TETAP 10.
_browse_kt = (
    JAVA / "animelovers" / "rebornhd" / "ui" / "main" / "browse" / "BrowseFragment.kt"
).read_text(encoding="utf-8")
if "hasLoadedOnce" not in _browse_kt:
    errors.append(
        "BrowseFragment.kt: penanda hasLoadedOnce wajib ada "
        "(muat pertama sekali saja — v2.0.7 #4)"
    )
if "override fun onResume()" not in _browse_kt:
    errors.append(
        "BrowseFragment.kt: muat pertama wajib dari onResume "
        "(tab dipilih — v2.0.7 #4)"
    )
if "if (!hasLoadedOnce)" not in _browse_kt:
    errors.append(
        "BrowseFragment.kt: onResume wajib menjaga muat pertama dengan "
        "hasLoadedOnce (v2.0.7 #4)"
    )
_main_kt = (
    JAVA / "animelovers" / "rebornhd" / "ui" / "main" / "MainActivity.kt"
).read_text(encoding="utf-8")
if "offscreenPageLimit = 10" not in _main_kt:
    errors.append(
        "MainActivity.kt: offscreenPageLimit wajib tetap 10 "
        "(visibility-gating bukan lewat limit — v2.0.7 #4)"
    )

# 17h. Badge jumlah tontonan tanpa emoji mata (v2.0.7 #5): format "N view".
if "👁" in _adapters_kt:
    errors.append(
        "AnimeAdapters.kt: emoji mata 👁 dilarang di badge jumlah tonton "
        "— aslinya '31.890.685 view' tanpa emoji (v2.0.7 #5)"
    )
if '"${formatViews(anime.views)} view"' not in _adapters_kt:
    errors.append(
        "AnimeAdapters.kt: badge Terlaris wajib format "
        "'{angka} view' (v2.0.7 #5)"
    )

# 17i. Play/pause satu tombol bergantian (v2.0.7 #6): hanya satu tampil
#      per keadaan — dikelola eksplisit, bukan mengandalkan PlayerView.
if "fun updatePlayPauseButton()" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: updatePlayPauseButton wajib ada "
        "(satu tombol play/pause bergantian — v2.0.7 #6)"
    )
if "R.id.exo_play" not in _video_kt or "R.id.exo_pause" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: updatePlayPauseButton wajib mengatur "
        "exo_play + exo_pause (v2.0.7 #6)"
    )
if "onPlayWhenReadyChanged" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: onPlayWhenReadyChanged wajib ada "
        "(tombol ikut berubah saat play/pause — v2.0.7 #6)"
    )

# 17j. Tombol HD tidak menabrak poster (v2.0.7 #7): tumpukan menu kanan
#      dipendekkan (margin item 2dp + margin wadah 0dp) — posisi menu
#      TIDAK dipindah (tetap menu_kanan seperti aslinya). Cek di
#      exo_playback_control_view.xml (bukan activity_video.xml — menu
#      kanan hidup di layout kontrol itu).
_exo_xml = (
    RES / "layout" / "exo_playback_control_view.xml"
).read_text(encoding="utf-8")
if "menu_kanan" not in _exo_xml:
    errors.append(
        "exo_playback_control_view.xml: menu_kanan wajib tetap ada "
        "(ikon HD tetap di menu kanan — v2.0.7 #7)"
    )
if _exo_xml.count('android:layout_marginTop="2dp"') < 4:
    errors.append(
        "exo_playback_control_view.xml: margin vertikal item menu_kanan "
        "wajib 2dp (4 item — jarak ke poster, v2.0.7 #7)"
    )

# 17k. Batasan keras TIDAK BOLEH berubah (re-assert v2.0.6 #7 + R8):
#      throttle 1.400 ms + timeout transport 30 dtk + guard R8.
if not re.search(r"SANKA_MIN_INTERVAL_MS\s*=\s*1_400L", _constants):
    errors.append(
        "Constants.kt: SANKA_MIN_INTERVAL_MS wajib tetap 1_400L "
        "(v2.0.7 #8 tidak boleh mengecilkan throttle)"
    )
if not re.search(r"SANKA_READ_TIMEOUT_S\s*=\s*30", _constants):
    errors.append(
        "Constants.kt: SANKA_READ_TIMEOUT_S wajib tetap 30 "
        "(v2.0.7 #8 tidak boleh menaikkan timeout transport)"
    )
_proguard = (ROOT / "app" / "proguard-rules.pro").read_text(encoding="utf-8")
if "TypeToken" not in _proguard or "Signature" not in _proguard:
    errors.append(
        "proguard-rules.pro: guard R8 (keep TypeToken + Signature) wajib "
        "tetap ada (v2.0.7 tidak boleh menyentuhnya)"
    )

# 17l. Geser horizontal kartu Episode Baru/Terlaris menggeser baris,
#      bukan pindah tab (v2.0.7 #9): nested scrolling AKTIF di kedua baris.
if _home_kt.count("isNestedScrollingEnabled = true") < 2:
    errors.append(
        "HomeFragment.kt: kedua RecyclerView horizontal (Episode Baru + "
        "Terlaris) wajib isNestedScrollingEnabled = true (v2.0.7 #9)"
    )
if "isNestedScrollingEnabled = false" in _home_kt:
    errors.append(
        "HomeFragment.kt: isNestedScrollingEnabled = false dilarang di "
        "baris horizontal — ViewPager2 akan mencegat geser dan pindah tab "
        "(v2.0.7 #9)"
    )

# 17m. Tidak ada judul ber-genre Donghua tampil di Beranda/Jelajahi/
#      pencarian (v2.0.7 #10): filter di SankaRepository, tanpa pengalih.
if "fun isDonghua" not in _repo_kt:
    errors.append(
        "SankaRepository.kt: fungsi isDonghua wajib ada "
        "(sembunyikan donghua total — v2.0.7 #10)"
    )
if "isDonghua(it)" not in _repo_kt:
    errors.append(
        "SankaRepository.kt: visibleAnimes/visibleEpisodes wajib "
        "menyaring isDonghua (v2.0.7 #10)"
    )

# 17n. Deskripsi tampil di layar Detail (v2.0.7 #11): sinopsis Sanka
#      penuh (layout tanpa maxLines) + baris disembunyikan bila kosong.
#      Sumber tambahan berbahasa Inggris (MAL/Jikan) butuh keputusan
#      Faygo — dicatat sebagai keterbatasan sumber, bukan bug aplikasi.
_detail_xml = (
    RES / "layout" / "activity_detail.xml"
).read_text(encoding="utf-8")
_desc_block = _detail_xml.split("descDetailView", 1)[1].split("/>", 1)[0] \
    if "descDetailView" in _detail_xml else ""
if "maxLines" in _desc_block:
    errors.append(
        "activity_detail.xml: descDetailView tidak boleh punya maxLines "
        "— sinopsis wajib tampil penuh (v2.0.7 #11)"
    )
if "descDetailView" not in _detail_kt or "synopsis" not in _detail_kt:
    errors.append(
        "DetailActivity.kt: descDetailView wajib diisi dari sinopsis Sanka "
        "(v2.0.7 #11)"
    )

# 17o. Hanya SATU spinner loading di player (v2.0.7 #12): spinner bawaan
#      PlayerView dimatikan; yang tampil ProgressBar + teks aplikasi.
_activity_video_xml = (
    RES / "layout" / "activity_video.xml"
).read_text(encoding="utf-8")
if 'app:show_buffering="never"' not in _activity_video_xml:
    errors.append(
        "activity_video.xml: PlayerView wajib show_buffering=\"never\" "
        "(satu spinner saja — v2.0.7 #12)"
    )
if 'app:show_buffering="when_playing"' in _activity_video_xml:
    errors.append(
        "activity_video.xml: show_buffering=\"when_playing\" dilarang — "
        "spinner ganda dengan ProgressBar aplikasi (v2.0.7 #12)"
    )
if "showInitialLoading" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: showInitialLoading wajib ada "
        "(indikator loading aplikasi yang dipertahankan — v2.0.7 #12)"
    )

# 17p. Pencarian: seri utama paling atas + sequel berurutan + Movie
#      terakhir (v2.0.7 #13): nomor musim dari judul + ONA/OVA sebelum Movie.
if "seasonNumberFor" not in _repo_kt:
    errors.append(
        "SankaRepository.kt: seasonNumberFor wajib ada "
        "(nomor musim dari judul — v2.0.7 #13)"
    )
if '"ona", "ova"' not in _repo_kt:
    errors.append(
        "SankaRepository.kt: sortSearchResults wajib menaruh ONA/OVA "
        "sebelum Movie (v2.0.7 #13)"
    )
if '"movie" -> 4' not in _repo_kt:
    errors.append(
        "SankaRepository.kt: sortSearchResults wajib menaruh Movie "
        "terakhir (grup 4 — v2.0.7 #13)"
    )

# 17q. D-player (keputusan Faygo 05:09 WIB): SEMUA video (TV, Movie,
#      Special, OVA/ONA) diputar di kontrol player aplikasi sendiri
#      (Media3) — TANPA pembedaan jenis. isMovie hanya untuk label +
#      perilaku ENDED, BUKAN pemilihan player; PlayerRepository tidak
#      mengenal isMovie sama sekali. Dialog fallback "Putar lewat
#      website?" tetap ada (pengecualian untuk episode tanpa file direct).
_player_repo_kt = (
    JAVA / "animelovers" / "rebornhd" / "data" / "repo" / "PlayerRepository.kt"
).read_text(encoding="utf-8")
if "isMovie" in _player_repo_kt:
    errors.append(
        "PlayerRepository.kt: isMovie dilarang di sini — pemilihan "
        "kandidat tidak boleh membedakan jenis anime (D-player)"
    )
_pick_match = re.search(
    r"private fun pickStartCandidate.*?\n    \}", _video_kt, re.DOTALL
)
if _pick_match is None or "isMovie" in _pick_match.group(0):
    errors.append(
        "VideoPlayActivity.kt: pickStartCandidate tidak boleh memakai "
        "isMovie — pemilihan kandidat hanya dari pref HD/SD (D-player)"
    )
_play_match = re.search(
    r"private fun playCandidate.*?\n    \}", _video_kt, re.DOTALL
)
if _play_match is None or "isMovie" in _play_match.group(0):
    errors.append(
        "VideoPlayActivity.kt: playCandidate tidak boleh memakai isMovie "
        "— semua jenis diputar di Media3 yang sama (D-player)"
    )

# ---------- 18. Gerbang v2.0.7 item 14–16 — fallback lintas-sumber ----------
# (PRD v2.0.8 §18a–§18i + §18o + §18p; item 1–3 dipindah ke v2.0.7
# atas keputusan Faygo 05:31 WIB — versi TETAP 12 / "2.0.7".)
_samehadaku_kt = (
    JAVA / "animelovers" / "rebornhd" / "data" / "repo" / "SamehadakuRepository.kt"
).read_text(encoding="utf-8")
_proguard = (ROOT / "app" / "proguard-rules.pro").read_text(encoding="utf-8")
_source_kt = (
    JAVA / "animelovers" / "rebornhd" / "data" / "model" / "SankaSource.kt"
).read_text(encoding="utf-8")
_all_kt = ""
for _f in (JAVA).rglob("*.kt"):
    _all_kt += _f.read_text(encoding="utf-8", errors="replace") + "\n"

# 18a. Rantai fallback ada — ekstraksi ok.ru + adapter Samehadaku +
#      pemakaian keduanya di rantai kandidat setelah lapis Animekompi,
#      sebelum dialog.
if "fun extractOkRuUrls" not in _player_repo_kt:
    errors.append(
        "PlayerRepository.kt: extractOkRuUrls wajib ada "
        "(ekstraksi tier ok.ru — v2.0.7 item 15)"
    )
if "class SamehadakuRepository" not in _samehadaku_kt:
    errors.append(
        "SamehadakuRepository.kt: adapter Samehadaku wajib ada "
        "(v2.0.7 item 16)"
    )
if "fun fetchOkRuCandidates" not in _player_repo_kt:
    errors.append(
        "PlayerRepository.kt: fetchOkRuCandidates wajib ada "
        "(lapis 2 — v2.0.7 item 15)"
    )
if "fun fetchSamehadakuCandidates" not in _player_repo_kt:
    errors.append(
        "PlayerRepository.kt: fetchSamehadakuCandidates wajib ada "
        "(lapis 3 — v2.0.7 item 16)"
    )
if "fetchOkRuCandidates(" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: rantai fallback wajib memanggil "
        "fetchOkRuCandidates sebelum dialog embed (v2.0.7 item 15)"
    )
if "fetchSamehadakuCandidates(" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: rantai fallback wajib memanggil "
        "fetchSamehadakuCandidates sebelum dialog embed (v2.0.7 item 16)"
    )

# 18b. API Donghua TIDAK dipanggil — 0 kemunculan `anime/donghua/` di kode
#      (lapis Donghua tidak aktif — D-donghua).
if "anime/donghua/" in _all_kt:
    errors.append(
        "Kode: anime/donghua/ dilarang — lapis Donghua TIDAK AKTIF "
        "(v2.0.7 item 14–16; aktifkan hanya bila donghua dibuka lagi)"
    )

# 18c. Pencocokan episode antar sumber memakai (judul ternormalisasi +
#      season + nomor episode); tidak ada pencocokan slug mentah;
#      bila ragu → dialog (jalur "jangan tebak" ada).
for _fn in ("fun normalizeSeriesTitle", "fun seasonNumberFromTitle",
            "fun episodeNumberFromSearchItem", "fun matchEpisode"):
    if _fn not in _samehadaku_kt:
        errors.append(
            f"SamehadakuRepository.kt: {_fn} wajib ada "
            "(pencocokan judul + season + episode — v2.0.7 item 16)"
        )
if "hits.size == 1" not in _samehadaku_kt:
    errors.append(
        "SamehadakuRepository.kt: matchEpisode wajib memakai hasil hanya "
        "bila TEPAT SATU kandidat cocok (bila ragu JANGAN tebak — "
        "v2.0.7 item 16)"
    )

# 18d. Samehadaku 500 = "tidak ada di sumber ini" — lanjut tanpa retry,
#      tanpa error ke pengguna.
if "SamehadakuNotFoundException" not in _samehadaku_kt:
    errors.append(
        "SamehadakuRepository.kt: SamehadakuNotFoundException wajib ada "
        "(500 = judul tidak ada — v2.0.7 item 16)"
    )
if "statusCode == 500" not in _samehadaku_kt:
    errors.append(
        "SamehadakuRepository.kt: HTTP 500 Samehadaku wajib dipetakan ke "
        "\"tidak ada\" (bukan error — v2.0.7 item 16)"
    )

# 18e. Ekstraksi ok.ru — host terkunci `ok.ru` videoembed; tier hd → sd;
#      URL tidak disimpan di DB/riwayat; label kualitas jujur dari tier.
if "fun isOkRuEmbed" not in _player_repo_kt:
    errors.append(
        "PlayerRepository.kt: isOkRuEmbed wajib ada (host terkunci ok.ru "
        "videoembed — v2.0.7 item 15)"
    )
if '"/videoembed/"' not in _player_repo_kt:
    errors.append(
        "PlayerRepository.kt: embed ok.ru wajib path /videoembed/ "
        "(v2.0.7 item 15)"
    )
if 'urls["hd"]' not in _player_repo_kt or 'urls["sd"]' not in _player_repo_kt:
    errors.append(
        "PlayerRepository.kt: fetchOkRuCandidates wajib memakai tier "
        "hd → sd (v2.0.7 item 15)"
    )
if "OKRU_720" not in _source_kt or "OKRU_360" not in _source_kt:
    errors.append(
        "SankaSource.kt: PlayableKind OKRU_720/OKRU_360 wajib ada "
        "(label jujur dari tier — v2.0.7 item 15)"
    )

# 18f. Samehadaku Blogger — pakai ulang extractBloggerUrls (itag 22 → 18);
#      adapter sendiri; model baru tercakup guard R8.
if "fetchBloggerUrls(bloggerUrl)" not in _player_repo_kt:
    errors.append(
        "PlayerRepository.kt: fetchSamehadakuCandidates wajib memakai "
        "ulang fetchBloggerUrls/extractBloggerUrls (itag 22 → 18 — "
        "v2.0.7 item 16)"
    )
if "SamehadakuSearchData" not in _samehadaku_kt:
    errors.append(
        "SamehadakuRepository.kt: model SamehadakuSearchData wajib dipakai "
        "(adapter sendiri — v2.0.7 item 16)"
    )
if "animelovers.rebornhd.data.**" not in _proguard:
    errors.append(
        "proguard-rules.pro: wildcard data.** wajib ada — model "
        "Samehadaku (data/model) tercakup guard R8 (v2.0.7 item 16)"
    )

# 18g. Rantai MALAS — ok.ru/Samehadaku tidak di-fetch bila lapis 1 sukses.
if "hasLayer1" not in _player_repo_kt:
    errors.append(
        "PlayerRepository.kt: resolve wajib malas — ok.ru hanya di-fetch "
        "bila lapis 1 kosong (hasLayer1 — v2.0.7 item 17)"
    )
if "hasLayer1 || hasLayer2" not in _player_repo_kt:
    errors.append(
        "PlayerRepository.kt: resolve wajib malas — Samehadaku hanya "
        "di-fetch bila lapis 1–2 kosong (v2.0.7 item 17)"
    )
if "tryLazyLayer(nextIndex)" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: lapis malas wajib dicoba lewat "
        "tryLazyLayer hanya sebelum jatuh ke embed (v2.0.7 item 17)"
    )

# 18h. D-player — isMovie 0 kemunculan di PlayerRepository / adapter baru /
#      pickStartCandidate / playCandidate (perluasan §17q ke lapis baru).
if "isMovie" in _player_repo_kt:
    errors.append(
        "PlayerRepository.kt: isMovie dilarang di sini — pemilihan "
        "kandidat tidak boleh membedakan jenis anime (D-player)"
    )
if "isMovie" in _samehadaku_kt:
    errors.append(
        "SamehadakuRepository.kt: isMovie dilarang di sini — adapter "
        "tidak boleh membedakan jenis anime (D-player)"
    )

# 18i. Dialog "Putar lewat website?" tetap jalan terakhir; tidak ada pesan
#      "Video tidak tersedia" sebagai fallback (grep string itu = 0 di
#      kode + res/).
if "showWebViewFallbackDialog" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: dialog fallback WebView wajib ada "
        "(jalan terakhir — v2.0.7 item 17)"
    )
if "Video tidak tersedia" in _all_kt:
    errors.append(
        'Kode: string "Video tidak tersedia" dilarang — ditolak Faygo '
        "sebagai solusi (v2.0.7 item 17)"
    )
_res_text = ""
for _f in (RES).rglob("*.xml"):
    _res_text += _f.read_text(encoding="utf-8", errors="replace") + "\n"
if "Video tidak tersedia" in _res_text:
    errors.append(
        'res/: string "Video tidak tersedia" dilarang — ditolak Faygo '
        "sebagai solusi (v2.0.7 item 17)"
    )

# 18o. Batasan keras TIDAK BOLEH berubah (re-assert §13d/§16f/§17k) +
#      versi TETAP 12 / "2.0.7" (item 14–16 masuk v2.0.7, BUKAN 2.0.8).
if not re.search(r"SANKA_MIN_INTERVAL_MS\s*=\s*1_400L", _constants):
    errors.append(
        "Constants.kt: SANKA_MIN_INTERVAL_MS wajib tetap 1_400L "
        "(v2.0.7 item 14–16 tidak boleh mengecilkan throttle)"
    )
if not re.search(r"SANKA_READ_TIMEOUT_S\s*=\s*30L", _constants):
    errors.append(
        "Constants.kt: SANKA_READ_TIMEOUT_S wajib tetap 30L "
        "(v2.0.7 item 14–16 tidak boleh mengubah timeout transport)"
    )
if "-keepattributes Signature" not in _proguard:
    errors.append(
        "proguard-rules.pro: guard R8 (keepattributes Signature) wajib "
        "ada — v2.0.7 item 14–16 tidak boleh menyentuh guard"
    )
if not re.search(r"versionCode\s*=\s*12\b", _app_gradle):
    errors.append(
        "app/build.gradle.kts: versionCode wajib TETAP 12 — item 14–16 "
        "masuk v2.0.7, bukan 2.0.8"
    )
if '"2.0.7"' not in _app_gradle:
    errors.append(
        'app/build.gradle.kts: versionName wajib TETAP "2.0.7"'
    )
if re.search(r"versionCode\s*=\s*13\b", _app_gradle):
    errors.append(
        "app/build.gradle.kts: versionCode 13 dilarang di v2.0.7 — "
        "itu versi v2.0.8"
    )

# 18p. Komentar diagnosis #8 menyebut 451 di-retry 8× (~20 dtk/kandidat)
#      — kata "tanpa retry" untuk 451 tidak ada lagi (koreksi O-1 Cari Bug).
if "DI-retry 8" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: komentar diagnosis wajib menyebut HTTP 451 "
        "DI-retry 8× (koreksi O-1 — v2.0.7 item 17)"
    )
if "gagal cepat tanpa retry" in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: frasa \"gagal cepat tanpa retry\" untuk 451 "
        "dilarang — 451 DI-retry 8× (koreksi O-1)"
    )

# 18q. SATU nama top-level = SATU definisi (H-1/C-1 — build gabungan
#      v2.0.7 sempat tidak bisa dikompilasi karena tiga class model
#      Samehadaku dideklarasikan di dua file paket yang sama; harness
#      tidak mengkompilasi file keduanya dan verify.py tidak punya gate
#      ini). Dua deklarasi top-level dengan nama sama di paket yang sama
#      SELALU error kompilasi Kotlin — jadi gate ini tidak bisa
#      false-positive pada kode yang benar. Cakupan: seluruh
#      app/src/main/java (persis yang dikompilasi Gradle). String,
#      komentar, dan kelas bersarang dibuang (strip_kotlin + lacak
#      kedalaman kurung per baris; hanya baris yang dimulai di
#      kedalaman 0 yang dihitung).
_decl_pattern = re.compile(
    r"^\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*"
    r"(?:(?:public|internal|private|protected|open|abstract|sealed|"
    r"data|inner|expect|actual|value|enum|annotation)\s+)*"
    r"(?:class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)"
)
_decl_package = re.compile(r"^\s*package\s+([\w.]+)", re.M)
_declared: dict[tuple[str, str], list[str]] = {}
for _f in kt_files:
    _stripped = strip_kotlin(_f.read_text(encoding="utf-8"))
    _pkg_m = _decl_package.search(_stripped)
    _pkg = _pkg_m.group(1) if _pkg_m else ""
    _depth = 0
    for _lineno, _line in enumerate(_stripped.split("\n"), start=1):
        if _depth == 0:
            _m = _decl_pattern.match(_line)
            if _m:
                _declared.setdefault((_pkg, _m.group(1)), []).append(
                    f"{_f.relative_to(ROOT)}:{_lineno}"
                )
        _depth += _line.count("{") - _line.count("}")
for (_pkg, _name), _locs in sorted(_declared.items()):
    if len(_locs) > 1:
        errors.append(
            f"Deklarasi top-level ganda: {_pkg}.{_name} didefinisikan "
            f"{len(_locs)}× ({'; '.join(_locs)}) — satu nama, satu "
            f"definisi (H-1/C-1 v2.0.7)"
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
