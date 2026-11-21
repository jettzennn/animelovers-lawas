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
import subprocess
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

def _template_end(body: str, i: int) -> int | None:
    """Indeks '}' penutup template `${...}` yang mulai di `body[i] == '$'`
    (dengan `body[i+1] == '{'`); None bila tidak tertutup. String di dalam
    interpolasi dilewati — kutip di dalamnya bukan penutup string luar
    (mis. `"${e.message ?: "tanpa pesan"}"` di SankaWebTransport.kt:134 —
    pemindai string yang naif berhenti di kutip dalam dan merusak hitungan
    kurung §7)."""
    depth = 0
    j = i + 1
    n = len(body)
    while j < n:
        if body[j] == '"':
            j += 1
            while j < n and body[j] != '"' and body[j] != "\n":
                if body[j] == "\\":
                    j += 2
                    continue
                j += 1
            if j < n and body[j] == '"':
                j += 1
            continue
        if body[j] == "{":
            depth += 1
        elif body[j] == "}":
            depth -= 1
            if depth == 0:
                return j
        j += 1
    return None


def _keep_template_code(body: str) -> str:
    """Pertahankan isi interpolasi `${...}` / `$nama` di dalam sebuah
    string Kotlin; samarkan sisa teks literal dengan spasi (newline
    dipertahankan supaya nomor baris tidak bergeser).

    Isi template adalah KODE HIDUP yang dievaluasi saat runtime — kata
    seperti `raw` / `simpleName` di dalamnya harus tetap terlihat oleh
    gerbang (F-6 Cari Bug v2.0.10: `"raw=${raw.take(50)}"` lolos §20e
    L-1 karena strip lama membuang seluruh isi string beserta
    interpolasinya). Teks literal di sekitarnya tetap dibuang — hanya
    interpolasi yang dihitung sebagai kode.
    """
    out: list[str] = []
    i, n = 0, len(body)
    while i < n:
        ch = body[i]
        if ch == "\\" and i + 1 < n:
            # Escape (mis. \" atau \$) — bukan awal template.
            out.append("  ")
            i += 2
            continue
        if ch == "$" and i + 1 < n and body[i + 1] == "{":
            close = _template_end(body, i)
            if close is None:
                out.append(body[i + 2:])
                i = n
            else:
                out.append(body[i + 2:close])
                i = close + 1
            continue
        if ch == "$" and i + 1 < n and (
            body[i + 1].isalpha() or body[i + 1] == "_"
        ):
            j = i + 1
            while j < n and (body[j].isalnum() or body[j] == "_"):
                j += 1
            out.append(body[i + 1:j])
            i = j
            continue
        out.append("\n" if ch == "\n" else " ")
        i += 1
    return "".join(out)


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
            out.append('"' + _keep_template_code(text[i + 3:j]) + '"')
            i = j + 3
            continue
        if text[i] == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "$" and j + 1 < n and text[j + 1] == "{":
                    # Template ${...}: lewati sampai '}' yang cocok —
                    # string di dalamnya bukan penutup string luar (F-6).
                    close = _template_end(text, j)
                    j = n if close is None else close + 1
                    continue
                if text[j] == '"' or text[j] == "\n":
                    break
                j += 1
            body = text[i + 1:j]
            if j < n and text[j] == '"':
                j += 1
            out.append('"' + _keep_template_code(body) + '"')
            i = j
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
#      + delay Splash 0 (D2) + versi rilis 2.0.10 / versionCode 15.
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
if not re.search(r"versionCode\s*=\s*18\b", _app_gradle):
    errors.append("app/build.gradle.kts: versionCode wajib 18 untuk v2.0.13")
if '"2.0.13"' not in _app_gradle:
    errors.append('app/build.gradle.kts: versionName wajib "2.0.13"')

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

# 16l. DIHAPUS di v2.0.8 (D-web): tidak ada lagi flip otomatis Media3 →
#      WebView karena JALUR WebView-nya dihapus total (item 5) — tidak ada
#      lagi playNextOrAsk / showWebViewFallbackDialog / string dialog.
#      Diganti §19e (0 identifier WebView pemain) + §19f (ujung gagal
#      jujur). Blok cek lama dihapus, bukan dikomentari — supaya tidak ada
#      yang mengira dialog itu masih wajib ada.

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
# v2.0.10 M-1: pemakaian loadPoster MEMENUHI gerbang ini — helper itu
# menulis ulang URL di dalamnya (rewritePosterHost(url), dicek di atas),
# jadi situs pemanggil tidak lagi memanggil rewritePosterHost langsung.
if "rewritePosterHost(item.idposter)" not in _history_kt and "loadPoster(" not in _history_kt:
    errors.append(
        "HistoryAdapters.kt: poster riwayat (baris lama di DB bisa v10) "
        "wajib ditulis ulang saat dimuat (v2.0.7 #1)"
    )
if "rewritePosterHost(animePoster)" not in _detail_kt and "loadPoster(" not in _detail_kt:
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
#      bukan pindah tab — DITULIS ULANG di v2.0.10 #4: nested scrolling
#      MATI di kedua baris. Alasan v2.0.7 #9 (mencegah ViewPager2
#      mencegat gestur) sudah usang sejak v2.0.9 #9 mematikan geser tab
#      di tingkat ViewPager2 — dan `= true` terbukti (video v2.0.9)
#      biang patah-patah: fling horizontal yang sedikit miring dipotong
#      di tengah jalan oleh ScrollView + SwipeRefreshLayout. Gerbang baru
#      ada di §21d (re-assert di sini supaya §17 tetap hijau).
if strip_kotlin(_home_kt).count("isNestedScrollingEnabled = true") != 0:
    errors.append(
        "HomeFragment.kt: isNestedScrollingEnabled = true dilarang — "
        "biang patah-patah baris Beranda (v2.0.10 #4, menggantikan "
        "v2.0.7 #9)"
    )
if strip_kotlin(_home_kt).count("isNestedScrollingEnabled = false") < 2:
    errors.append(
        "HomeFragment.kt: kedua RecyclerView horizontal (Episode Baru + "
        "Terlaris) wajib isNestedScrollingEnabled = false (v2.0.10 #4)"
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
# (PRD v2.0.8 §18a–§18i + §18o + §18p; item 1–3 v2.0.8 masuk versi
# 13 / "2.0.8" — gerbang §13d + §18o ikut naik.)
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
#      sebelum gagal jujur.
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
        "fetchOkRuCandidates sebelum gagal jujur (v2.0.7 item 15)"
    )
if "fetchSamehadakuCandidates(" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: rantai fallback wajib memanggil "
        "fetchSamehadakuCandidates sebelum gagal jujur (v2.0.7 item 16)"
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
#      bila ragu → gagal jujur (jalur "jangan tebak" ada).
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
if "tryLazyLayer()" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: lapis malas wajib dicoba lewat "
        "tryLazyLayer hanya bila daftar kandidat habis (v2.0.7 item 17; "
        "v2.0.8: tanpa argumen nextIndex — tidak ada lagi embed)"
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

# 18i. TIDAK ADA dialog fallback; tidak ada pesan
#      "Video tidak tersedia" sebagai fallback (grep string itu = 0 di
#      kode + res/). Ujung rantai = gagal jujur (lihat §19f).
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
#      versi 16 / "2.0.11" (v2.0.11 — 16 kini versi yang benar).
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
if not re.search(r"versionCode\s*=\s*18\b", _app_gradle):
    errors.append(
        "app/build.gradle.kts: versionCode wajib 18 untuk v2.0.13"
    )
if '"2.0.13"' not in _app_gradle:
    errors.append(
        'app/build.gradle.kts: versionName wajib "2.0.13"'
    )

# 18p. DITULIS ULANG di v2.0.8 (item 3): 451/404/410 fail-fast via
#      override (TIME_UNSET); error transient (IO/5xx/408/429) tetap
#      DI-retry 8×. Klaim lama "451 DI-retry 8×" menjadi SALAH — larang
#      frasa lama SECARA PRESISI (literal "451 DI-retry 8") supaya
#      frasa benar ("tetap DI-retry 8×" untuk transient) tidak ikut
#      terlarang.
if "getRetryDelayMsFor" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: override getRetryDelayMsFor wajib ada "
        "(fail-fast 451/404/410 — v2.0.8 item 3)"
    )
if "C.TIME_UNSET" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: fail-fast wajib mengembalikan C.TIME_UNSET "
        "(tanpa retry — v2.0.8 item 3)"
    )
if "HttpFailFast.isPermanent" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: override wajib memakai HttpFailFast "
        "(keputusan murni — v2.0.8 item 3)"
    )
if "451 DI-retry 8" in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: klaim lama \"451 DI-retry 8\" dilarang — "
        "451 fail-fast di v2.0.8 (yang tetap DI-retry 8× hanya error "
        "transient: IO/5xx/408/429)"
    )
if "gagal cepat tanpa retry" in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: frasa \"gagal cepat tanpa retry\" dilarang "
        "— pakai istilah \"fail-fast\" (v2.0.8 item 3)"
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

# ---------- 19. Gerbang v2.0.8 ----------
# 19a. Watchdog ambang pertumbuhan (item 1 — KONSERVATIF): ambang cek /
#      tanpa-tumbuh / maks TETAP 10/30/120 dtk; tambah ambang minimal
#      500 ms/cek; keputusan murni di StallTracker; baseline DIMAJUKAN
#      di setiap cek (bukan hanya saat lolos).
if not re.search(r"STALL_CHECK_INTERVAL_MS\s*=\s*10_000L", _video_kt):
    errors.append(
        "VideoPlayActivity.kt: STALL_CHECK_INTERVAL_MS wajib tetap "
        "10_000L (v2.0.8 item 1 — ambang 10/30/120 dtk tidak diubah)"
    )
if not re.search(r"STALL_NO_GROWTH_MS\s*=\s*30_000L", _video_kt):
    errors.append(
        "VideoPlayActivity.kt: STALL_NO_GROWTH_MS wajib tetap 30_000L "
        "(v2.0.8 item 1)"
    )
if not re.search(r"STALL_MAX_MS\s*=\s*120_000L", _video_kt):
    errors.append(
        "VideoPlayActivity.kt: STALL_MAX_MS wajib tetap 120_000L "
        "(v2.0.8 item 1)"
    )
if not re.search(r"STALL_MIN_GROWTH_MS\s*=\s*500L", _video_kt):
    errors.append(
        "VideoPlayActivity.kt: STALL_MIN_GROWTH_MS wajib 500L "
        "(ambang pertumbuhan minimal — v2.0.8 item 1)"
    )
if "StallTracker.isGrowth" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: startStallWatch wajib memakai "
        "StallTracker.isGrowth (keputusan murni — v2.0.8 item 1)"
    )
if "lastBufferedMs = buffered" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: baseline lastBufferedMs WAJIB dimajukan "
        "di SETIAP cek — varian baseline-saat-lolos gagal diam-diam "
        "untuk Ep 1180 (v2.0.8 item 1)"
    )
if "fun isGrowth" not in _player_repo_kt:
    errors.append(
        "PlayerRepository.kt: StallTracker.isGrowth wajib ada "
        "(fungsi murni untuk harness — v2.0.8 item 1)"
    )

# 19b. UA + Referer playback (item 2): UA browser di factory + izinkan
#      redirect lintas-protokol; Referer per-kind via headersFor (ok.ru
#      → https://ok.ru/, sisanya tanpa Referer); timeout 15/30 + retry 8
#      TETAP (gerbang §16j/§16k).
if "setUserAgent(PlayerRepository.OKRU_USER_AGENT)" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: setupPlayer wajib setUserAgent "
        "(OKRU_USER_AGENT — v2.0.8 item 2)"
    )
if "setAllowCrossProtocolRedirects(true)" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: setupPlayer wajib "
        "setAllowCrossProtocolRedirects(true) (v2.0.8 item 2)"
    )
if "setDefaultRequestProperties" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: startMedia3 wajib memasang Referer "
        "per-kind via setDefaultRequestProperties (v2.0.8 item 2)"
    )
if "fun headersFor" not in _player_repo_kt:
    errors.append(
        "PlayerRepository.kt: headersFor(kind) wajib ada (Referer "
        "per-kind — v2.0.8 item 2)"
    )
if '"https://ok.ru/"' not in _player_repo_kt:
    errors.append(
        "PlayerRepository.kt: headersFor wajib memberi Referer "
        "https://ok.ru/ untuk kind ok.ru (v2.0.8 item 2)"
    )

# 19c. Fail-fast 4xx + pengecualian 408/429 (item 3): keputusan murni di
#      HttpFailFast; 408/429 TIDAK fail-fast (tetap di-retry 8×);
#      MIN_DIRECT_LOAD_RETRIES tetap 8.
if "fun isPermanent" not in _player_repo_kt:
    errors.append(
        "PlayerRepository.kt: HttpFailFast.isPermanent wajib ada "
        "(keputusan murni — v2.0.8 item 3)"
    )
if "code != 408 && code != 429" not in _player_repo_kt:
    errors.append(
        "PlayerRepository.kt: 408/429 WAJIB dikecualikan dari fail-fast "
        "(transient, tetap di-retry 8× — v2.0.8 item 3)"
    )
if not re.search(
    r"MIN_DIRECT_LOAD_RETRIES\s*=\s*8\b", _video_kt
):
    errors.append(
        "VideoPlayActivity.kt: MIN_DIRECT_LOAD_RETRIES wajib tetap 8 "
        "(v2.0.8 item 3 — fail-fast bukan pengurangan retry)"
    )

# 19d. Header Blogger (item 4): fetchBloggerUrls memakai
#      buildBloggerRequest (UA + Referer Sanka, sama seperti ok.ru).
if "fun buildBloggerRequest" not in _player_repo_kt:
    errors.append(
        "PlayerRepository.kt: buildBloggerRequest wajib ada "
        "(request Blogger murni — v2.0.8 item 4)"
    )
if "buildBloggerRequest(iframeUrl)" not in _player_repo_kt:
    errors.append(
        "PlayerRepository.kt: fetchBloggerUrls wajib memakai "
        "buildBloggerRequest (v2.0.8 item 4)"
    )
if _player_repo_kt.count('header("User-Agent", OKRU_USER_AGENT)') < 2:
    errors.append(
        "PlayerRepository.kt: UA browser wajib di request Blogger DAN "
        "ok.ru (v2.0.8 item 4)"
    )
if _player_repo_kt.count('header("Referer", Constants.SANKA_BASE_URL)') < 2:
    errors.append(
        "PlayerRepository.kt: Referer Sanka wajib di request Blogger DAN "
        "ok.ru (v2.0.8 item 4)"
    )

# 19e. 0 identifier WebView pemain — SCOPED (bukan grep global
#      "WebView"): rantai pemain (VideoPlayActivity, SankaSource,
#      PlayerRepository) + layout player + strings TIDAK BOLEH memuat
#      WebView/webView/isWebView/EMBED. Transport Sanka (SankaWebTransport
#      + MainActivity + SankaRepository) SENGAJA tidak dicakup — tanpa itu
#      aplikasi tidak bisa bicara ke Sanka (Cloudflare memblokir OkHttp).
for _name, _text in (
    ("VideoPlayActivity.kt", _video_kt),
    ("SankaSource.kt", _source_kt),
    ("PlayerRepository.kt", _player_repo_kt),
):
    for _bad in ("WebView", "webView", "isWebView", "EMBED"):
        if _bad in _text:
            errors.append(
                f"{_name}: identifier {_bad} dilarang di rantai pemain "
                f"(v2.0.8 item 5 — WebView pemain dihapus total)"
            )
if "android.webkit" in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: import android.webkit dilarang "
        "(v2.0.8 item 5)"
    )
if "<WebView" in _res_text or "webView" in _res_text:
    errors.append(
        "res/: elemen/ID WebView dilarang di layout player "
        "(v2.0.8 item 5)"
    )
if "putar_lewat_website" in defined["string"]:
    errors.append(
        "strings.xml: putar_lewat_website wajib DIHAPUS (v2.0.8 item 5)"
    )
if "webview_fallback_judul" in defined["string"]:
    errors.append(
        "strings.xml: webview_fallback_judul wajib DIHAPUS (v2.0.8 item 5)"
    )
if "webview_fallback_isi" in defined["string"]:
    errors.append(
        "strings.xml: webview_fallback_isi wajib DIHAPUS (v2.0.8 item 5)"
    )

# 19f. Ujung gagal jujur (item 5): tidak ada lagi playNextOrAsk /
#      dialog fallback; tryNextCandidate habis → lapis malas → toast
#      gagal_memutar + keluar; lapis malas menambah kandidat di AKHIR
#      daftar (addAll, tanpa embed yang didahului).
if "playNextOrAsk" in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: playNextOrAsk wajib DIHAPUS "
        "(v2.0.8 item 5 — tidak ada lagi dialog tanya-fallback)"
    )
if "showWebViewFallbackDialog" in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: showWebViewFallbackDialog wajib DIHAPUS "
        "(v2.0.8 item 5)"
    )
if "tryNextCandidate()" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: tryNextCandidate wajib ada (ujung rantai "
        "— v2.0.8 item 5)"
    )
if "updated.addAll(fresh)" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: launchLazyLayer wajib menambah kandidat "
        "baru di AKHIR daftar (addAll — v2.0.8 item 5)"
    )
if "toast(getString(R.string.gagal_memutar, episode))" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: ujung gagal wajib toast gagal_memutar "
        "(v2.0.8 item 5)"
    )

# ---------- 20. Gerbang v2.0.9 — Konsol Diagnostik ----------
_diag_log_kt = (
    JAVA / "animelovers" / "rebornhd" / "util" / "DiagLog.kt"
).read_text(encoding="utf-8")
_diag_logger_kt = (
    JAVA / "animelovers" / "rebornhd" / "util" / "DiagLogger.kt"
).read_text(encoding="utf-8")
_diag_poster_kt = (
    JAVA / "animelovers" / "rebornhd" / "util" / "DiagPosterListener.kt"
).read_text(encoding="utf-8")

# 20a. Logger murni + tanpa jaringan: DiagLog.kt ada + berisi formatLine /
#      sanitize / MAX_DIAG_LINES = 500; DiagLogger.kt ada; `android.`
#      TIDAK ADA di DiagLog.kt (murni — harus bisa dikompilasi harness
#      JVM); SankaTransport/OkHttpClient/transport.getJson TIDAK ADA di
#      DiagLogger.kt (Logger tidak menambah panggilan Sanka).
for _fn in ("fun formatLine", "fun sanitize", "MAX_DIAG_LINES = 500"):
    if _fn not in _diag_log_kt:
        errors.append(
            f"DiagLog.kt: {_fn} wajib ada (logger murni — v2.0.9 item 1)"
        )
if "android." in _diag_log_kt:
    errors.append(
        "DiagLog.kt: TIDAK BOLEH ada `android.` — file ini murni "
        "(harus bisa dikompilasi harness JVM tanpa Android — v2.0.9 item 1)"
    )
for _bad in ("SankaTransport", "OkHttpClient", "transport.getJson"):
    if _bad in _diag_logger_kt:
        errors.append(
            f"DiagLogger.kt: {_bad} dilarang — Logger tidak boleh "
            "menambah panggilan Sanka (throttle 45/menit tetap utuh — "
            "v2.0.9 item 1)"
        )

# 20b. Crash handler: setDefaultUncaughtExceptionHandler ADA di
#      AnimeloversApp.kt + handler lama disimpan dan dipanggil kembali
#      (chain — handler default TIDAK BOLEH ditelan).
if "setDefaultUncaughtExceptionHandler" not in _app_kt:
    errors.append(
        "AnimeloversApp.kt: setDefaultUncaughtExceptionHandler wajib ada "
        "(crash handler — v2.0.9 item 2)"
    )
if "getDefaultUncaughtExceptionHandler" not in _app_kt:
    errors.append(
        "AnimeloversApp.kt: handler crash lama wajib disimpan lewat "
        "getDefaultUncaughtExceptionHandler (v2.0.9 item 2)"
    )
if "uncaughtException(thread, throwable)" not in _app_kt:
    errors.append(
        "AnimeloversApp.kt: handler crash lama WAJIB di-chain "
        "(previousHandler?.uncaughtException — tanpa itu proses tidak "
        "mati + dialog crash sistem tidak muncul — v2.0.9 item 2)"
    )
if "logCrashSync" not in _app_kt:
    errors.append(
        "AnimeloversApp.kt: crash handler wajib menulis jejak lewat "
        "logCrashSync (v2.0.9 item 2)"
    )

# 20c. FileProvider: androidx.core.content.FileProvider ADA di manifest +
#      authorities animelovers.rebornhd.fileprovider + file_paths.xml ADA
#      + berisi path="diag/" + TIDAK berisi path="." (itu mengekspos
#      home_cache.json + file lain di filesDir).
if "androidx.core.content.FileProvider" not in _manifest_text:
    errors.append(
        "AndroidManifest.xml: FileProvider wajib ada (berbagi file log — "
        "v2.0.9 item 7)"
    )
if 'android:authorities="animelovers.rebornhd.fileprovider"' not in _manifest_text:
    errors.append(
        "AndroidManifest.xml: authorities FileProvider wajib "
        "animelovers.rebornhd.fileprovider (v2.0.9 item 7)"
    )
_file_paths = RES / "xml" / "file_paths.xml"
if not _file_paths.exists():
    errors.append(
        "res/xml/file_paths.xml wajib ada (FileProvider Konsol — "
        "v2.0.9 item 7)"
    )
else:
    _file_paths_text = _file_paths.read_text(encoding="utf-8")
    if 'path="diag/"' not in _file_paths_text:
        errors.append(
            'res/xml/file_paths.xml: wajib berisi path="diag/" '
            "(hanya folder log yang terekspos — v2.0.9 item 7)"
        )
    if 'path="."' in _file_paths_text:
        errors.append(
            'res/xml/file_paths.xml: path="." dilarang — itu mengekspos '
            "home_cache.json + file lain di filesDir (v2.0.9 item 7)"
        )

# 20d. Konsol terdaftar + pintu masuk: ConsoleActivity ADA di manifest +
#      file ui/console/ConsoleActivity.kt ADA + consoleButton ADA di
#      SettingActivity.kt + ConsoleActivity.start dipanggil dari sana +
#      layout activity_console.xml ADA.
if "ConsoleActivity" not in _manifest_text:
    errors.append(
        "AndroidManifest.xml: ConsoleActivity wajib didaftarkan "
        "(v2.0.9 item 6)"
    )
if not (
    JAVA / "animelovers" / "rebornhd" / "ui" / "console" / "ConsoleActivity.kt"
).exists():
    errors.append(
        "ui/console/ConsoleActivity.kt wajib ada (v2.0.9 item 6)"
    )
if "consoleButton" not in _setting_kt:
    errors.append(
        "SettingActivity.kt: consoleButton wajib ada (pintu masuk "
        "Konsol di Pengaturan — v2.0.9 item 6)"
    )
if "ConsoleActivity.start" not in _setting_kt:
    errors.append(
        "SettingActivity.kt: wajib memanggil ConsoleActivity.start "
        "(pintu masuk Konsol — v2.0.9 item 6)"
    )
if "activity_console" not in defined["layout"]:
    errors.append(
        "res/layout/activity_console.xml wajib ada (v2.0.9 item 6)"
    )

# 20e. Privasi: userEmail/emailUser TIDAK ADA di DiagLogger.kt +
#      DiagLog.kt; DiagLogger TIDAK PERNAH dipanggil dengan variabel
#      `raw` (grep DiagLogger.*\braw\b = 0 di semua .kt); sanitize(
#      ADA di dalam DiagLogger.kt (dipaksa di jalur log); string
#      "Video tidak tersedia" TETAP 0 (re-assert §18i).
for _bad in ("userEmail", "emailUser"):
    if _bad in _diag_logger_kt or _bad in _diag_log_kt:
        errors.append(
            f"DiagLogger.kt/DiagLog.kt: {_bad} dilarang — email pengguna "
            "tidak boleh masuk log diagnostik (v2.0.9 item 1, D-privasi-log)"
        )
# 20e (DIPERKUAT v2.0.10 — L-1/L-2 Pentest v2.0.9).
#  L-1: pola LAMA hanya menangkap `DiagLogger` kapital di SATU baris —
#       pemanggilan multi-baris (`diagLogger?.info(\n  ... raw ...\n)`)
#       dan variabel huruf kecil `diagLogger` lolos. Kini: cari semua
#       pemanggilan diagLogger.info/warn/error (tak peka huruf) di teks
#       yang sudah dibuang string/komentarnya, lalu pindai jendela
#       kurung-berimbang sesudahnya untuk kata `raw` (variabel payload
#       mentah — teks literal string tidak dihitung, TAPI isi template
#       `${...}` / `$nama` DIHITUNG karena itu kode hidup: F-6, bentuk
#       `"raw=${raw.take(50)}"` dulu lolos karena strip membuang isi
#       string beserta interpolasinya).
#  L-2: cek lama hanya memastikan string "sanitize(" ADA di mana pun di
#       DiagLogger.kt — kini dipastikan ADA DI DALAM badan fun log()
#       (kurung-kurawal-berimbang dari `private fun log(`).
for _f in kt_files:
    _t = _f.read_text(encoding="utf-8", errors="replace")
    _stripped = strip_kotlin(_t)
    # Satu pola untuk `diagLogger?.info(` dan `diagLogger.info(`.
    for _m in re.finditer(
        r"diagLogger\s*(?:\?)?\.\s*(?:info|warn|error)\s*\(",
        _stripped, flags=re.IGNORECASE
    ):
        _open = _m.end() - 1  # "(" pemanggilan itu sendiri
        _depth = 0
        _k = _open
        while _k < len(_stripped):
            if _stripped[_k] == "(":
                _depth += 1
            elif _stripped[_k] == ")":
                _depth -= 1
                if _depth == 0:
                    break
            _k += 1
        if re.search(r"\braw\b", _stripped[_open:_k + 1]):
            _line_no = _stripped.count("\n", 0, _m.start()) + 1
            errors.append(
                f"{_f.relative_to(ROOT)}:{_line_no}: DiagLogger tidak boleh "
                "dipanggil dengan variabel `raw` — payload mentah "
                "dilarang di Konsol (hanya ke logcat, di requireData — "
                "v2.0.9 item 1, D-privasi-log)"
            )
# L-2: sanitize( wajib ADA DI DALAM badan fun log() DiagLogger.kt.
_log_fn = strip_kotlin(_diag_logger_kt)
_log_start = _log_fn.find("private fun log(")
_log_body = ""
if _log_start >= 0:
    _brace = _log_fn.find("{", _log_start)
    if _brace >= 0:
        _depth = 0
        _k = _brace
        while _k < len(_log_fn):
            if _log_fn[_k] == "{":
                _depth += 1
            elif _log_fn[_k] == "}":
                _depth -= 1
                if _depth == 0:
                    break
            _k += 1
        _log_body = _log_fn[_brace:_k + 1]
if "sanitize(" not in _log_body:
    errors.append(
        "DiagLogger.kt: sanitize( wajib ada DI DALAM badan fun log() — "
        "semua pesan log harus lewat sanitasi URL (v2.0.9 item 1, "
        "D-privasi-log; L-2 Pentest v2.0.9)"
    )
if "Video tidak tersedia" in _all_kt or "Video tidak tersedia" in _res_text:
    errors.append(
        'string "Video tidak tersedia" tetap dilarang — ditolak Faygo '
        "sebagai solusi (re-assert §18i — v2.0.9)"
    )

# 20f. Hook hadir: string diagLogger/DiagLogger ADA di TIAP file hook —
#      VideoPlayActivity.kt, PlayerRepository.kt, SankaRepository.kt,
#      SamehadakuRepository.kt, HomeFragment.kt, BrowseFragment.kt,
#      AnimeloversApp.kt — + EventListener ADA di AnimeloversApp.kt atau
#      DiagPosterListener.kt (hook poster).
for _name, _text in (
    ("VideoPlayActivity.kt", _video_kt),
    ("PlayerRepository.kt", _player_repo_kt),
    ("SankaRepository.kt", _repo_kt),
    ("SamehadakuRepository.kt", _samehadaku_kt),
    ("HomeFragment.kt", _home_kt),
    ("BrowseFragment.kt", _browse_kt),
    ("AnimeloversApp.kt", _app_kt),
):
    if "diagLogger" not in _text and "DiagLogger" not in _text:
        errors.append(
            f"{_name}: diagLogger/DiagLogger wajib ada (hook Konsol — "
            "v2.0.9 item 3/4/5)"
        )
if "EventListener" not in _app_kt and "EventListener" not in _diag_poster_kt:
    errors.append(
        "AnimeloversApp.kt/DiagPosterListener.kt: EventListener Coil "
        "wajib ada (hook poster — v2.0.9 item 5)"
    )

# 20g. Cap ditegakkan: MAX_DIAG_LINES = 500 + logika trim ADA di
#      DiagLogger.kt/DiagLog.kt (drop( atau removeFirst — salah satu
#      harus ada); export header ADA (versionName + Build.MODEL +
#      Build.VERSION.RELEASE di kode export).
_diag_combined = _diag_log_kt + "\n" + _diag_logger_kt
if "MAX_DIAG_LINES = 500" not in _diag_combined:
    errors.append(
        "DiagLog.kt/DiagLogger.kt: MAX_DIAG_LINES = 500 wajib ada "
        "(cap 500 baris — v2.0.9 item 1)"
    )
if "drop(" not in _diag_combined and "removeFirst" not in _diag_combined:
    errors.append(
        "DiagLog.kt/DiagLogger.kt: logika trim wajib ada (drop( atau "
        "removeFirst — cap tidak boleh hanya angka — v2.0.9 item 1)"
    )
for _h in ("versionName", "Build.MODEL", "Build.VERSION.RELEASE"):
    if _h not in _diag_logger_kt:
        errors.append(
            f"DiagLogger.kt: header export wajib memuat {_h} "
            "(v2.0.9 item 7)"
        )

# 20h. Versi: §13d + §18o naik ke 18 / "2.0.13" (re-assert — gerbang
#      versi harus lolos SETELAH bump; pola v2.0.8: §13d ikut naik).
if not re.search(r"versionCode\s*=\s*18\b", _app_gradle):
    errors.append("app/build.gradle.kts: versionCode wajib 18 untuk v2.0.13")
if '"2.0.13"' not in _app_gradle:
    errors.append('app/build.gradle.kts: versionName wajib "2.0.13"')

# 20i (DIPERKUAT v2.0.10 — O-3 Cari Bug v2.0.9): cek pada teks yang
#      sudah dibuang string/komentarnya (strip_kotlin) — baris yang
#      dikomentari tidak boleh lagi memenuhi gerbang. Swipe tab dimatikan
#      (v2.0.9 #9): "isUserInputEnabled = false" ADA di MainActivity.kt
#      (navigasi tab hanya lewat ketuk TabLayout); carousel
#      "isUserInputEnabled = true" di HomeFragment.kt TIDAK BOLEH ikut
#      dimatikan (auto-scroll + geser slide tetap jalan).
_main_stripped = strip_kotlin(_main_kt)
_home_stripped = strip_kotlin(_home_kt)
if "isUserInputEnabled = false" not in _main_stripped:
    errors.append(
        "MainActivity.kt: viewpager.isUserInputEnabled = false wajib ada "
        "(swipe tab dimatikan — geser di baris/carousel tidak boleh "
        "pindah tab — v2.0.9 #9)"
    )
if "isUserInputEnabled = true" not in _home_stripped:
    errors.append(
        "HomeFragment.kt: carousel isUserInputEnabled = true wajib tetap "
        "ada (geser slide hero tetap jalan — v2.0.9 #9)"
    )

# 20j. Import EventListener Coil BENAR (CI-merah v2.0.9): di Coil 2.5.0
#      EventListener berada di paket "coil" (coil/EventListener.kt),
#      BUKAN "coil.listener" — import salah lolos sampai CI karena
#      harness tidak mengompilasi file ini (butuh kelas Coil) dan
#      §20f lama hanya mencocokkan string "EventListener".
if "import coil.EventListener" not in _diag_poster_kt:
    errors.append(
        "DiagPosterListener.kt: import coil.EventListener wajib ada "
        "(EventListener Coil 2.5.0 ada di paket coil, bukan "
        "coil.listener — CI-merah v2.0.9 run 44449686977)"
    )
if "coil.listener" in _diag_poster_kt:
    errors.append(
        "DiagPosterListener.kt: coil.listener TIDAK BOLEH ada "
        "(paket itu tidak ada di Coil 2.5.0 — Unresolved reference)"
    )

# ---------- 21. Gerbang v2.0.10 — perbaikan video + log v2.0.9 ----------

_console_kt = (
    JAVA / "animelovers" / "rebornhd" / "ui" / "console" / "ConsoleActivity.kt"
).read_text(encoding="utf-8")
_console_xml = (RES / "layout" / "activity_console.xml").read_text(encoding="utf-8")
_slide_xml = (RES / "layout" / "item_slide.xml").read_text(encoding="utf-8")
_history_adapters_kt = (
    JAVA / "animelovers" / "rebornhd" / "ui" / "adapter" / "HistoryAdapters.kt"
).read_text(encoding="utf-8")
_strings_xml = (RES / "values" / "strings.xml").read_text(encoding="utf-8")
_test_v210_path = ROOT.parent / "tasks" / "uji" / "src" / "TestV210.kt"
_test_v210 = (
    _test_v210_path.read_text(encoding="utf-8") if _test_v210_path.exists()
    else ""
)

# 21a. Resolve nomor ganda (item 1): pickEpisodeSlug ADA di
#      SankaRepository + DIPAKAI di DetailActivity, EpisodeActivity, DAN
#      VideoPlayActivity (episodeSlugs) — lookup mentah
#      `firstOrNull { it.number == episode }` TIDAK BOLEH tersisa di
#      kode kedua Activity itu (dicek pada teks strip_kotlin — penyebutan
#      di komentar/KDoc tidak dihitung).
if "fun pickEpisodeSlug" not in _repo_kt:
    errors.append(
        "SankaRepository.kt: fun pickEpisodeSlug wajib ada "
        "(pilih slug episode asli, bukan spesial/batch — v2.0.10 #1)"
    )
for _name, _text in (
    ("DetailActivity.kt", _detail_kt),
    ("EpisodeActivity.kt", _episode_kt),
    ("VideoPlayActivity.kt", _video_kt),
):
    if "pickEpisodeSlug(" not in _text:
        errors.append(
            f"{_name}: pickEpisodeSlug( wajib dipakai — lookup nomor "
            "episode mentah bisa kena entri Spesial (v2.0.10 #1)"
        )
for _name, _text in (
    ("DetailActivity.kt", _detail_kt),
    ("EpisodeActivity.kt", _episode_kt),
):
    if "firstOrNull { it.number == episode }" in strip_kotlin(_text):
        errors.append(
            f"{_name}: lookup mentah `firstOrNull {{ it.number == "
            "episode }}` dilarang — pakai pickEpisodeSlug (v2.0.10 #1)"
        )

# 21b. Error 1 huruf R8 (item 2): `simpleName` = 0 di semua file .kt
#      (dicek pada teks strip_kotlin — penyebutan di komentar tidak
#      dihitung), KECUALI SankaWebTransport.kt yang DIBEKUKAN (jangan
#      disentuh — aturan v2.0.8): di sana tersisa TEPAT 1 pemakaian,
#      di catch-all sankaErrorReason yang pesannya didahulukan
#      ("<nama>: <pesan>" — pesan tetap terbaca walau namanya 1 huruf).
for _f in kt_files:
    _stripped = strip_kotlin(_f.read_text(encoding="utf-8", errors="replace"))
    _n = _stripped.count("simpleName")
    if _f.name == "SankaWebTransport.kt":
        if _n > 1:
            errors.append(
                "SankaWebTransport.kt: simpleName hanya boleh tersisa 1 "
                "(catch-all sankaErrorReason yang pesannya didahulukan — "
                "file dibekukan, jangan tambah pemakaian baru — v2.0.10 #2)"
            )
    elif _n > 0:
        errors.append(
            f"{_f.relative_to(ROOT)}: simpleName ({_n}×) dilarang — nama "
            "kelas hasil rename R8 (1 huruf) tidak boleh masuk log; pakai "
            "DiagError.describe (v2.0.10 #2)"
        )

# 21c. Log resolve mencatat slug (item 3): "slug=" ADA di baris log
#      resolve PlayerRepository + "slug=$episodeSlug" di baris "resolve
#      selesai" VideoPlayActivity.
if "slug=" not in _player_repo_kt:
    errors.append(
        "PlayerRepository.kt: baris log resolve wajib mencatat slug "
        "episode (slug=...) — tanpa ini resolve yang salah pilih slug "
        "tidak bisa dibedakan dari yang benar (v2.0.10 #3)"
    )
if "slug=$episodeSlug" not in _video_kt:
    errors.append(
        "VideoPlayActivity.kt: baris 'resolve selesai' wajib mencatat "
        "slug=$episodeSlug (v2.0.10 #3)"
    )

# 21d. Baris Beranda tidak patah-patah (item 4): `isNestedScrollingEnabled
#      = true` = 0 di HomeFragment, `= false` ≥ 2 (kedua baris).
_home_nested_true = strip_kotlin(_home_kt).count("isNestedScrollingEnabled = true")
_home_nested_false = strip_kotlin(_home_kt).count("isNestedScrollingEnabled = false")
if _home_nested_true != 0:
    errors.append(
        "HomeFragment.kt: isNestedScrollingEnabled = true dilarang "
        f"({_home_nested_true}×) — biang patah-patah baris Beranda "
        "(v2.0.10 #4)"
    )
if _home_nested_false < 2:
    errors.append(
        "HomeFragment.kt: isNestedScrollingEnabled = false wajib ada di "
        "KEDUA baris Beranda (baru "
        f"{_home_nested_false}× — v2.0.10 #4)"
    )

# 21e. Ranking pencarian diuji (item 5): TestV210.kt wajib memuat tes
#      sufiks "[...]" + musim di tengah subjudul (kasus live v2.0.9).
if not _test_v210:
    errors.append(
        "tasks/uji/src/TestV210.kt wajib ada (tes fungsi murni v2.0.10)"
    )
else:
    for _needle in (
        "stripBracketSuffix", "seasonRankFor", "tantei wa mou",
    ):
        if _needle not in _test_v210:
            errors.append(
                f"TestV210.kt: tes ranking wajib memuat '{_needle}' "
                "(sufiks [...] + musim di tengah subjudul — v2.0.10 #5)"
            )

# 21f. Log error search mencatat query (item 6): searchEndpointLabel ADA
#      + DIPAKAI di search() SankaRepository; baris gagal BrowseFragment
#      memangkas query ke 40 karakter (take(40) ≥ 2× di file itu —
#      1× di baris sukses + 1× di baris gagal).
if "fun searchEndpointLabel" not in _repo_kt:
    errors.append(
        "SankaRepository.kt: fun searchEndpointLabel wajib ada "
        "(endpoint search membawa query — v2.0.10 #6)"
    )
if "searchEndpointLabel(query)" not in _repo_kt:
    errors.append(
        "SankaRepository.kt: search() wajib memakai searchEndpointLabel "
        "(query yang gagal harus tercatat — v2.0.10 #6)"
    )
if _browse_kt.count("take(40)") < 2:
    errors.append(
        "BrowseFragment.kt: baris gagal wajib memangkas query ke 40 "
        "karakter (take(40) ≥ 2× — sukses + gagal — v2.0.10 #6)"
    )

# 21g. Judul toolbar Konsol sadar-tema (item 7): titleTextColor memakai
#      ?attr/ di activity_console.xml; TIDAK ADA warna hitam keras di
#      layout Konsol + ConsoleActivity (nama drawable
#      `ic_arrow_back_black_24dp` bukan warna — tidak dihitung).
if 'app:titleTextColor="?attr/textColor"' not in _console_xml:
    errors.append(
        "activity_console.xml: toolbar_console wajib memakai "
        'app:titleTextColor="?attr/textColor" (judul Konsol gelap-di-'
        "gelap di tema gelap — v2.0.10 #7)"
    )
for _bad in ("#000", "Color.BLACK", "@android:color/black"):
    if _bad in _console_xml or _bad in _console_kt:
        errors.append(
            f"Konsol: {_bad} dilarang di activity_console.xml / "
            "ConsoleActivity.kt — warna keras tidak sadar-tema "
            "(v2.0.10 #7)"
        )

# 21h. Urutan coba kandidat diuji (item 9): TestV210.kt wajib memuat tes
#      CandidateTryOrder (kasus #0 tidak pernah dicoba — v2.0.9).
if "CandidateTryOrder" not in _test_v210:
    errors.append(
        "TestV210.kt: tes CandidateTryOrder wajib ada "
        "(kandidat #0 ikut dicoba — v2.0.10 #9)"
    )

# 21i. Riwayat hanya bila frame pertama tercapai (item 10): guard
#      per-episode ADA DI DALAM badan persistResume VideoPlayActivity;
#      nama lama `hasFirstFrame` TIDAK BOLEH tersisa.
#      (Pass delta F-1: guard dulu `if (!firstFrameReached) return` —
#      flag itu bersemantik per-kandidat karena di-reset di startMedia3,
#      sehingga keluar saat buffering retry kehilangan Riwayat. Kini
#      guard memakai FirstFrameTracker.hasEpisodeReachedFirstFrame().)
_persist_stripped = strip_kotlin(_video_kt)
_persist_start = _persist_stripped.find("private fun persistResume()")
_persist_body = ""
if _persist_start >= 0:
    _brace = _persist_stripped.find("{", _persist_start)
    if _brace >= 0:
        _depth = 0
        _k = _brace
        while _k < len(_persist_stripped):
            if _persist_stripped[_k] == "{":
                _depth += 1
            elif _persist_stripped[_k] == "}":
                _depth -= 1
                if _depth == 0:
                    break
            _k += 1
        _persist_body = _persist_stripped[_brace:_k + 1]
if "hasEpisodeReachedFirstFrame()" not in _persist_body:
    errors.append(
        "VideoPlayActivity.kt: persistResume wajib dijaga guard "
        "per-episode `hasEpisodeReachedFirstFrame()` — film/episode yang "
        "langsung gagal tidak boleh masuk Riwayat (v2.0.10 #10; F-1)"
    )
if "hasFirstFrame" in strip_kotlin(_video_kt):
    errors.append(
        "VideoPlayActivity.kt: nama lama hasFirstFrame tidak boleh "
        "tersisa — sudah diganti FirstFrameTracker (v2.0.10 #10; F-1)"
    )

# 21j. Daftar izin import (O-4 Cari Bug v2.0.9): setiap import di file
#      .kt wajib berawalan segmen yang diizinkan (dienumerasi dari
#      import yang benar-benar ada di tree); `coil.listener` dilarang
#      di mana pun (paket itu tidak ada di Coil 2.5.0 — §20j).
_ALLOWED_IMPORT_FIRST = {
    "android", "androidx", "animelovers", "coil", "com", "io", "java",
    "javax", "kotlin", "kotlinx", "okhttp3", "org",
}
for _f in kt_files:
    for _line in _f.read_text(encoding="utf-8", errors="replace").split("\n"):
        _s = _line.strip()
        if not _s.startswith("import "):
            continue
        _imp = _s[len("import "):].strip()
        if _imp.startswith("coil.listener"):
            errors.append(
                f"{_f.relative_to(ROOT)}: import coil.listener dilarang — "
                "paket itu tidak ada di Coil 2.5.0 (v2.0.10 §21j)"
            )
        _first = _imp.split(".")[0]
        if _first not in _ALLOWED_IMPORT_FIRST:
            errors.append(
                f"{_f.relative_to(ROOT)}: import '{_imp}' berawalan "
                f"'{_first}' — di luar daftar izin import "
                "(v2.0.10 §21j)"
            )

# 21k. Judul hero tidak terpotong di tengah kata (item 11): slideNameTXT
#      di item_slide.xml wajib maxLines=3 + ellipsize=end.
if 'android:maxLines="3"' not in _slide_xml or 'android:ellipsize="end"' not in _slide_xml:
    errors.append(
        "item_slide.xml: slideNameTXT wajib maxLines=3 + ellipsize=end "
        "(judul hero panjang terpotong di tengah kata — v2.0.10 #11)"
    )

# 21l. M-4 (Cari Bug v2.0.7): pendingEpisode ADA di DetailActivity +
#      string detail_memuat ADA di strings.xml (ketukan "Lanjutkan"
#      sebelum /detail kembali ditahan, bukan toast gagal).
if "pendingEpisode" not in _detail_kt:
    errors.append(
        "DetailActivity.kt: pendingEpisode wajib ada — ketukan "
        "'Lanjutkan' sebelum /detail kembali harus ditahan lalu diputar "
        "begitu detail tiba (M-4, v2.0.10 #15)"
    )
if "detail_memuat" not in _strings_xml:
    errors.append(
        "strings.xml: string detail_memuat wajib ada "
        "(M-4, v2.0.10 #15)"
    )

# 21m. M-1 (Cari Bug v2.0.7): 6 situs poster Detail + Riwayat wajib
#      memakai helper loadPoster (bukan .load langsung dengan
#      error splashh) — helper memberi ikon error + ketuk-coba-lagi.
if "error(R.drawable.splashh)" in strip_kotlin(_detail_kt):
    errors.append(
        "DetailActivity.kt: error(R.drawable.splashh) dilarang — poster "
        "Detail wajib memakai helper loadPoster (M-1, v2.0.10 #15)"
    )
if "error(R.drawable.splashh)" in strip_kotlin(_history_adapters_kt):
    errors.append(
        "HistoryAdapters.kt: error(R.drawable.splashh) dilarang — poster "
        "Riwayat wajib memakai helper loadPoster (M-1, v2.0.10 #15)"
    )
if "loadPoster(" not in _detail_kt or "loadPoster(" not in _history_adapters_kt:
    errors.append(
        "DetailActivity.kt/HistoryAdapters.kt: loadPoster( wajib dipakai "
        "di kedua file (M-1, v2.0.10 #15)"
    )

# 21n. M-2/M-3 (Cari Bug v2.0.7): homeMemoryCached() mati TIDAK BOLEH ada
#      (nol pemanggil — yang hidup homeCached()); writeHomeToDisk wajib
#      tulis atomik (file .tmp + renameTo).
if "fun homeMemoryCached" in _repo_kt:
    errors.append(
        "SankaRepository.kt: homeMemoryCached() mati wajib dihapus "
        "(M-2, v2.0.10 #15)"
    )
if ".tmp" not in _repo_kt or "renameTo(" not in _repo_kt:
    errors.append(
        "SankaRepository.kt: writeHomeToDisk wajib tulis atomik "
        "(file .tmp + renameTo — M-3, v2.0.10 #15)"
    )

# 21o. M-1 Cari Bug v2.0.9 (race DiagLogger): DiagFileMirror ADA di
#      DiagLog.kt + DIPAKAI DiagLogger.kt (seq + generasi + reset);
#      clear() TIDAK BOLEH menghapus file secara async (launch).
if "class DiagFileMirror" not in _diag_log_kt:
    errors.append(
        "DiagLog.kt: class DiagFileMirror wajib ada — penjaga urutan "
        "tulis file log (M-1 Cari Bug v2.0.9, v2.0.10 #12)"
    )
if "fileMirror" not in _diag_logger_kt:
    errors.append(
        "DiagLogger.kt: fileMirror (DiagFileMirror) wajib dipakai — "
        "tulis file log tidak boleh lagi dari nilai saat log() dipanggil "
        "(M-1 Cari Bug v2.0.9, v2.0.10 #12)"
    )
_clear_stripped = strip_kotlin(_diag_logger_kt)
_clear_start = _clear_stripped.find("fun clear()")
if _clear_start >= 0:
    _clear_window = _clear_stripped[_clear_start:_clear_start + 1200]
    if "fileScope.launch" in _clear_window:
        errors.append(
            "DiagLogger.kt: clear() tidak boleh menghapus file secara "
            "async (fileScope.launch) — hapus sinkron supaya force-stop "
            "tidak menghidupkan kembali file lama "
            "(M-1 Cari Bug v2.0.9, v2.0.10 #12)"
        )

# ---------- v2.0.11 (kill switch) ----------
# 22a. Evaluator murni ADA, urutan cek WAJIB pengecualian → saklar →
#      kedaluwarsa, dan TIDAK BOLEH mengimpor Android/Firebase
#      (harness kotlinc JVM mengompilasinya tanpa classpath Android).
_ks_logic_path = JAVA / "animelovers" / "rebornhd" / "util" / "KillSwitchLogic.kt"
_ks_wrapper_path = JAVA / "animelovers" / "rebornhd" / "util" / "KillSwitch.kt"
if not _ks_logic_path.exists():
    errors.append("KillSwitchLogic.kt tidak ada — evaluator murni saklar (v2.0.11 #2)")
else:
    _ks_logic = _ks_logic_path.read_text(encoding="utf-8")
    if "fun evaluate(" not in _ks_logic:
        errors.append("KillSwitchLogic.kt: fun evaluate() wajib ada (v2.0.11 #2)")
    _ks_exempt_pos = _ks_logic.find("parseExemptIds(config.exemptIds)")
    _ks_switch_pos = _ks_logic.find("config.switchOn")
    _ks_expiry_pos = _ks_logic.find("isExpired(config.expiryDate")
    if min(_ks_exempt_pos, _ks_switch_pos, _ks_expiry_pos) < 0:
        errors.append(
            "KillSwitchLogic.kt: urutan cek tidak lengkap — wajib ada "
            "pengecualian, saklar, lalu kedaluwarsa (v2.0.11 #2)"
        )
    elif not (_ks_exempt_pos < _ks_switch_pos < _ks_expiry_pos):
        errors.append(
            "KillSwitchLogic.kt: URUTAN CEK SALAH — wajib pengecualian → "
            "saklar → kedaluwarsa (v2.0.11 #2)"
        )
    for _ks_bad_import in ("import android.", "import com.google.firebase"):
        if _ks_bad_import in _ks_logic:
            errors.append(
                "KillSwitchLogic.kt: TIDAK BOLEH mengimpor Android/Firebase "
                f"({_ks_bad_import}…) — berkas ini dikompilasi harness JVM "
                "tanpa classpath Android (v2.0.11 #2)"
            )
    if "import java.time.LocalDate" not in _ks_logic:
        warnings.append(
            "KillSwitchLogic.kt: tanpa java.time.LocalDate — tanggal "
            "kedaluwarsa sebaiknya dinilai per hari lokal Asia/Jakarta "
            "(v2.0.11 #2)"
        )
if not _ks_wrapper_path.exists():
    errors.append("KillSwitch.kt tidak ada — wrapper Android/Firebase (v2.0.11 #2)")
else:
    _ks_wrapper = _ks_wrapper_path.read_text(encoding="utf-8")
    for _ks_need in (
        "FirebaseRemoteConfig", "FirebaseInstallations",
        "setMinimumFetchIntervalInSeconds", "fetchAndActivate",
    ):
        if _ks_need not in _ks_wrapper:
            errors.append(f"KillSwitch.kt: {_ks_need} wajib ada (v2.0.11 #1/#2)")
    if "900" not in _ks_wrapper:
        errors.append(
            "KillSwitch.kt: interval fetch 900 detik (15 menit) wajib ada "
            "(v2.0.11 #2)"
        )

# 22b. google-services.json: WAJIB di .gitignore, WAJIB TIDAK terindeks
#      git, dan WAJIB TIDAK PERNAH masuk ZIP (Deploy memakainya dari
#      CI secret). Isinya (api key) tidak pernah ditulis di laporan.
_ks_gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8", errors="replace")
if "google-services.json" not in _ks_gitignore:
    errors.append(
        ".gitignore: google-services.json WAJIB diabaikan — berisi api key "
        "proyek Firebase (v2.0.11 #1)"
    )
try:
    _ks_ls = subprocess.run(
        ["git", "-C", str(ROOT), "ls-files", "--", "app/google-services.json"],
        capture_output=True, text=True, timeout=15,
    )
    if _ks_ls.returncode == 0 and _ks_ls.stdout.strip():
        errors.append(
            "app/google-services.json TERINDEKS git — hapus dari index "
            "(file ini hanya untuk build lokal + CI secret, v2.0.11 #1)"
        )
    elif _ks_ls.returncode != 0:
        warnings.append("verify.py §22b: git ls-files gagal — tidak bisa memastikan google-services.json tidak terindeks")
except Exception:
    warnings.append("verify.py §22b: git tidak bisa dijalankan — tidak bisa memastikan google-services.json tidak terindeks")

# 22c. (DIPERBARUI v2.0.13) Gradle: versionCode 18 / "2.0.13";
#      BOM 32.7.0 + config-ktx + installations-ktx + auth-ktx +
#      play-services-auth + facebook-login; plugin google-services
#      4.4.0. Larangan v2.0.11 atas firebase-auth/play-services-auth/
#      facebook-login SUDAH DICABUT (login dibangun di v2.0.12) —
#      komentar larangan lama wajib sudah diganti.
_ks_app_gradle = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
_ks_root_gradle = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
_ks_toml = (ROOT / "gradle" / "libs.versions.toml").read_text(encoding="utf-8")
if "versionCode = 18" not in _ks_app_gradle:
    errors.append("app/build.gradle.kts: versionCode wajib 18 (v2.0.13)")
if 'versionName = "2.0.13"' not in _ks_app_gradle:
    errors.append('app/build.gradle.kts: versionName wajib "2.0.13" (v2.0.13)')
for _ks_dep in (
    "firebase.bom",
    "firebase.config.ktx",
    "firebase.installations.ktx",
    "firebase.auth.ktx",
    "play.services.auth",
    "facebook.login",
):
    if _ks_dep not in _ks_app_gradle:
        errors.append(f"app/build.gradle.kts: {_ks_dep} wajib ada (v2.0.12 #1)")
if "google.services" not in _ks_app_gradle or "google.services" not in _ks_root_gradle:
    errors.append(
        "plugin google-services wajib diterapkan di root + app "
        "build.gradle.kts (v2.0.11 #1)"
    )
if '"32.7.0"' not in _ks_toml or '"4.4.0"' not in _ks_toml:
    errors.append(
        "gradle/libs.versions.toml: firebase-bom wajib 32.7.0 dan "
        "google-services wajib 4.4.0 (v2.0.11 #1)"
    )
if "JANGAN firebase-auth" in _ks_app_gradle:
    errors.append(
        "app/build.gradle.kts: komentar larangan 'JANGAN firebase-auth' "
        "dari v2.0.11 wajib sudah diganti — login dibangun di v2.0.12 "
        "(PRD §B1)"
    )

# 22d. Splash cek SEBELUM disclaimer/Main (maks. 5 detik); Main punya
#      startDead + cek ulang onResume (>15 menit).
_ks_splash = (JAVA / "animelovers" / "rebornhd" / "ui" / "splash" / "SplashActivity.kt").read_text(encoding="utf-8")
if "KillSwitch.check" not in _ks_splash or "startDead" not in _ks_splash:
    errors.append(
        "SplashActivity.kt: wajib cek KillSwitch.check() sebelum "
        "disclaimer/Main dan ke MainActivity.startDead() bila MATI "
        "(v2.0.11 #2/#3)"
    )
if "SPLASH_TIMEOUT_MS" not in _ks_splash and "withTimeoutOrNull" not in _ks_splash:
    errors.append(
        "SplashActivity.kt: cek saklar wajib dibatasi 5 detik "
        "(SPLASH_TIMEOUT_MS / withTimeoutOrNull, v2.0.11 #2)"
    )
_ks_main = (JAVA / "animelovers" / "rebornhd" / "ui" / "main" / "MainActivity.kt").read_text(encoding="utf-8")
if "fun startDead" not in _ks_main or "EXTRA_SHOW_DEAD" not in _ks_main:
    errors.append(
        "MainActivity.kt: startDead() + EXTRA_SHOW_DEAD wajib ada — "
        "jalur MATI kembali ke error state Beranda (v2.0.11 #3)"
    )
if "RECHECK_INTERVAL_MS" not in _ks_main or "KillSwitch.check" not in _ks_main:
    errors.append(
        "MainActivity.kt: onResume wajib cek ulang bila cek terakhir "
        "> 15 menit (v2.0.11 #6)"
    )

# 22e. Gerbang isDead() di semua jalur masuk konten + player, dan
#      include error state ada di Beranda + Jelajahi.
_ks_guarded = {
    "HomeFragment": JAVA / "animelovers" / "rebornhd" / "ui" / "main" / "home" / "HomeFragment.kt",
    "BrowseFragment": JAVA / "animelovers" / "rebornhd" / "ui" / "main" / "browse" / "BrowseFragment.kt",
    "DetailActivity": JAVA / "animelovers" / "rebornhd" / "ui" / "detail" / "DetailActivity.kt",
    "EpisodeActivity": JAVA / "animelovers" / "rebornhd" / "ui" / "episode" / "EpisodeActivity.kt",
    # Delta F-3 Cari Bug v2.0.11: LainnyaActivity juga memuat konten
    # server (/home) — gerbang isDead() wajib ada di load().
    "LainnyaActivity": JAVA / "animelovers" / "rebornhd" / "ui" / "episode" / "LainnyaActivity.kt",
    "VideoPlayActivity": JAVA / "animelovers" / "rebornhd" / "ui" / "player" / "VideoPlayActivity.kt",
}
for _ks_name, _ks_path in _ks_guarded.items():
    _ks_text = _ks_path.read_text(encoding="utf-8")
    if "KillSwitch.isDead()" not in _ks_text:
        errors.append(f"{_ks_name}: gerbang KillSwitch.isDead() wajib ada (v2.0.11 #3)")
    if _ks_name == "VideoPlayActivity" and "fun start(" in _ks_text:
        _ks_start = _ks_text[_ks_text.find("fun start("):]
        if "KillSwitch.isDead()" not in _ks_start[:1200]:
            errors.append(
                "VideoPlayActivity.start(): gerbang isDead() wajib ada — "
                "player tidak boleh dibuka saat MATI (v2.0.11 #3)"
            )
for _ks_layout in ("fragment_home.xml", "fragment_browse.xml"):
    _ks_layout_text = (RES / "layout" / _ks_layout).read_text(encoding="utf-8")
    if "server_error" not in _ks_layout_text or "layout_server_error" not in _ks_layout_text:
        errors.append(
            f"{_ks_layout}: include layout_server_error wajib ada — "
            "error state server (v2.0.11 #3)"
        )
if not (RES / "layout" / "layout_server_error.xml").exists():
    errors.append("layout_server_error.xml tidak ada (v2.0.11 #3)")

# 22f. Bahasa NETRAL: layar error + string + baris log jalur saklar
#      TIDAK BOLEH mengandung kata kill / dimatikan / firebase.
#      (Nama kelas/parameter/kunci seperti KillSwitch, kill_switch,
#      kill_last_check_ms BUKAN teks tampil — dikecualikan.)
_ks_forbidden_words = ("kill", "dimatikan", "firebase")
_ks_allowed_literals = {
    "kill_switch", "kill_message", "kill_expiry_date", "kill_exempt_ids",
    "kill_last_check_ms",
}
for _ks_path in (_ks_logic_path, _ks_wrapper_path):
    if not _ks_path.exists():
        continue
    _ks_text = _ks_path.read_text(encoding="utf-8")
    for _ks_lit in re.findall(r'"([^"]*)"', _ks_text):
        if _ks_lit in _ks_allowed_literals:
            continue
        _ks_low = _ks_lit.lower()
        for _ks_word in _ks_forbidden_words:
            if _ks_word in _ks_low:
                errors.append(
                    f"{_ks_path.name}: literal \"{_ks_lit}\" mengandung kata "
                    f"terlarang '{_ks_word}' — layar error + log jalur "
                    "saklar wajib netral (v2.0.11 #7)"
                )
# Hanya string v2.0.11 (jalur saklar) yang dicek — string lama
# login_belum_tersedia ("proyek Firebase baru belum dipasang") memang
# menyebut Firebase dan itu benar untuk konteksnya (login).
_ks_v211_strings = {
    "server_sanka_putus", "server_sanka_putus_detail",
    "id_instalasi", "id_instalasi_kosong", "id_disalin",
}
for _ks_match in re.finditer(
    r"<string name=\"([^\"]+)\">([^<]*)</string>", _strings_xml
):
    if _ks_match.group(1) not in _ks_v211_strings:
        continue
    _ks_low = _ks_match.group(2).lower()
    for _ks_word in _ks_forbidden_words:
        if _ks_word in _ks_low:
            errors.append(
                f"strings.xml: string {_ks_match.group(1)} mengandung kata "
                f"terlarang '{_ks_word}' — teks tampil wajib netral "
                "(v2.0.11 #7)"
            )
_ks_server_layout = (RES / "layout" / "layout_server_error.xml")
if _ks_server_layout.exists():
    _ks_layout_text = _ks_server_layout.read_text(encoding="utf-8")
    for _ks_word in _ks_forbidden_words:
        if _ks_word in _ks_layout_text.lower():
            errors.append(
                f"layout_server_error.xml: mengandung kata terlarang "
                f"'{_ks_word}' — layar error wajib netral (v2.0.11 #7)"
            )

# 22g. Prefs + Konsol: installation_id + kill_last_check_ms; header
#      ID Instalasi (ketuk = salin); baris "Konsol dibuka" hanya
#      boleh mencatat 8 karakter pertama (shortId).
_ks_prefs = (JAVA / "animelovers" / "rebornhd" / "util" / "Prefs.kt").read_text(encoding="utf-8")
for _ks_key in ("installationId", "killLastCheckMs"):
    if _ks_key not in _ks_prefs:
        errors.append(f"Prefs.kt: properti {_ks_key} wajib ada (v2.0.11 #4/#6)")
_ks_console = (JAVA / "animelovers" / "rebornhd" / "ui" / "console" / "ConsoleActivity.kt").read_text(encoding="utf-8")
# consoleInstallationId = field binding dari id console_installation_id.
for _ks_need in ("consoleInstallationId", "copyInstallationId", "shortId"):
    if _ks_need not in _ks_console:
        errors.append(f"ConsoleActivity.kt: {_ks_need} wajib ada — ID Instalasi di header (v2.0.11 #5)")
if "console_installation_id" not in (RES / "layout" / "activity_console.xml").read_text(encoding="utf-8"):
    errors.append("activity_console.xml: id console_installation_id wajib ada — header ID Instalasi (v2.0.11 #5)")
if "Konsol dibuka" in _ks_console and "shortId" not in _ks_console:
    errors.append(
        "ConsoleActivity.kt: baris 'Konsol dibuka' wajib memakai shortId "
        "(ID penuh TIDAK BOLEH masuk log, v2.0.11 #5)"
    )

# 22h. Larangan abadi: string "Video tidak tersedia" = 0 dan opsi
#      "Putar lewat website" = 0 di seluruh app/src; berkas BEKU
#      SankaWebTransport.kt tidak boleh disentuh v2.0.11.
#      (Frasa "lewat website" masih ada di KOMENTAR lama sebagai catatan
#      sejarah penghapusan — jadi dicek hanya di baris kode (bukan
#      baris komentar) + nilai string XML, bukan teks mentah.)
_ks_src_all = ""
_ks_code_lines: list[str] = []
for _ks_file in (ROOT / "app" / "src").rglob("*.kt"):
    _ks_file_text = _ks_file.read_text(encoding="utf-8", errors="replace")
    _ks_src_all += _ks_file_text + "\n"
    for _ks_line in _ks_file_text.splitlines():
        _ks_stripped = _ks_line.strip()
        if _ks_stripped.startswith(("//", "*", "/*", "*/")):
            continue
        _ks_code_lines.append(_ks_line)
_ks_code_text = "\n".join(_ks_code_lines)
if "Video tidak tersedia" in _ks_src_all or "Video tidak tersedia" in _strings_xml:
    errors.append(
        'String "Video tidak tersedia" DILARANG SELAMANYA — bukan solusi '
        "(v2.0.11, keputusan Faygo)"
    )
_ks_xml_values = " ".join(
    _ks_m.group(2) for _ks_m in re.finditer(
        r"<string name=\"([^\"]+)\">([^<]*)</string>", _strings_xml
    )
)
if "Putar lewat website" in _ks_code_text or "Putar lewat website" in _ks_xml_values:
    errors.append(
        'Opsi/dialog "Putar lewat website" DILARANG — sudah dihapus total '
        "di v2.0.8, jangan dibangun lagi (v2.0.11)"
    )
_ks_transport = (JAVA / "animelovers" / "rebornhd" / "data" / "api" / "SankaWebTransport.kt").read_text(encoding="utf-8")
if "KillSwitch" in _ks_transport:
    errors.append(
        "SankaWebTransport.kt BEKU — tidak boleh disentuh v2.0.11 "
        "(v2.0.11 #8)"
    )

# 22i. Gerbang delta Cari Bug v2.0.11 (F-1/F-2/F-7):
#      - F-1: cek sekali-per-proses — KillSwitch punya
#        ensureCheckedInProcess + hasCheckedInProcess, dan MainActivity
#        memakainya di onCreate (jalur pemulihan proses yang melewati
#        Splash); aturan murninya di KillSwitchLogic.needsRecheck.
#      - F-2: retry manual ("Coba Lagi") memaksa fetch segar —
#        KillSwitch punya jalur fetch(0) + bedakan throttling
#        (FirebaseRemoteConfigFetchThrottledException, BUKAN log
#        fetch-gagal); ketiga jalur retry memanggil check(force = true).
#      - F-7: CancellationException dilempar ulang di KillSwitch.
if "ensureCheckedInProcess" not in _ks_wrapper or "hasCheckedInProcess" not in _ks_wrapper:
    errors.append(
        "KillSwitch.kt: ensureCheckedInProcess() + hasCheckedInProcess() "
        "wajib ada — cek sekali-per-proses (delta F-1)"
    )
if "needsRecheck" not in _ks_logic:
    errors.append(
        "KillSwitchLogic.kt: needsRecheck() wajib ada — aturan paksa cek "
        "bila proses belum pernah cek (delta F-1)"
    )
if "ensureCheckedInProcess" not in _ks_main:
    errors.append(
        "MainActivity.kt: onCreate wajib memaksa satu kali cek via "
        "ensureCheckedInProcess() — jalur pemulihan proses melewati "
        "Splash (delta F-1)"
    )
if "fetch(0" not in _ks_wrapper:
    errors.append(
        "KillSwitch.kt: jalur fetch(0) wajib ada — retry manual memaksa "
        "fetch segar, mengabaikan interval 15 menit (delta F-2)"
    )
if "FirebaseRemoteConfigFetchThrottledException" not in _ks_wrapper:
    errors.append(
        "KillSwitch.kt: throttling SDK wajib dibedakan dari gagal "
        "(FirebaseRemoteConfigFetchThrottledException — bukan log "
        "fetch-gagal, delta F-2)"
    )
if "CancellationException" not in _ks_wrapper:
    errors.append(
        "KillSwitch.kt: CancellationException wajib dilempar ulang — "
        "jangan ditelan jadi fail-open (delta F-7)"
    )
for _ks_retry_name, _ks_retry_path in (
    ("HomeFragment", JAVA / "animelovers" / "rebornhd" / "ui" / "main" / "home" / "HomeFragment.kt"),
    ("BrowseFragment", JAVA / "animelovers" / "rebornhd" / "ui" / "main" / "browse" / "BrowseFragment.kt"),
    ("DetailActivity", JAVA / "animelovers" / "rebornhd" / "ui" / "detail" / "DetailActivity.kt"),
):
    _ks_retry_text = _ks_retry_path.read_text(encoding="utf-8")
    if "force = true" not in _ks_retry_text:
        errors.append(
            f"{_ks_retry_name}: tombol 'Coba Lagi' wajib memanggil "
            "check(force = true) — retry manual memaksa fetch segar "
            "(delta F-2)"
        )

# ---------- v2.0.12 (login Google + Facebook via Firebase Auth) ----------

def _lg_no_diff(rel_paths):
    """True bila worktree sama dengan basis 6e908f6 untuk path-path itu.
    None bila git tidak bisa dijalankan (pemanggil mengubah jadi warning)."""
    try:
        # WAJIB cek dulu repo + revisi basis ada: di luar repo git,
        # `git diff --quiet <rev> -- <path>` jatuh ke jalur --no-index
        # dan mengembalikan 1 (satu path) / 129 (dua path) — kode yang
        # SAMA dengan "ada diff" — jadi returncode saja tidak bisa
        # membedakan "git gagal" dari "ada diff" (bukti: /tmp/manual-neg,
        # F-1 susulan). rev-parse gagal -> None (warning di pemanggil).
        _lg_ok = subprocess.run(
            ["git", "-C", str(ROOT), "rev-parse", "--verify", "--quiet",
             "6e908f6^{commit}"],
            capture_output=True, text=True, timeout=15,
        )
        if _lg_ok.returncode != 0:
            return None
        _lg_r = subprocess.run(
            ["git", "-C", str(ROOT), "diff", "--quiet", "6e908f6", "--"] + rel_paths,
            capture_output=True, text=True, timeout=15,
        )
        # Setelah rev-parse lolos, 0 = tidak ada diff, 1 = ada diff;
        # kode lain (tak terduga) tetap diperlakukan sebagai "git gagal".
        return True if _lg_r.returncode == 0 else (False if _lg_r.returncode == 1 else None)
    except Exception:
        return None

_lg_auth_dir = JAVA / "animelovers" / "rebornhd" / "data" / "auth"
_lg_repo_path = _lg_auth_dir / "FirebaseAuthRepository.kt"
_lg_session_path = _lg_auth_dir / "LoginSession.kt"
_lg_interface_path = _lg_auth_dir / "AuthRepository.kt"
_lg_app_path = JAVA / "animelovers" / "rebornhd" / "AnimeloversApp.kt"
_lg_setting_path = (
    JAVA / "animelovers" / "rebornhd" / "ui" / "settings" / "SettingActivity.kt"
)
_lg_detail_path = (
    JAVA / "animelovers" / "rebornhd" / "ui" / "detail" / "DetailActivity.kt"
)
_lg_manifest_path = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
_lg_toml = (ROOT / "gradle" / "libs.versions.toml").read_text(encoding="utf-8")
_lg_gradle = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")

# 23a. Dependensi login dipatok versinya (PRD §B1): firebase-auth-ktx
#      dikelola BOM 32.7.0 (tanpa version.ref), play-services-auth
#      20.7.0, facebook-login 16.3.0.
if 'play-services-auth = "20.7.0"' not in _lg_toml:
    errors.append(
        "gradle/libs.versions.toml: play-services-auth wajib 20.7.0 "
        "(v2.0.12 #1)"
    )
if 'facebook-login = "16.3.0"' not in _lg_toml:
    errors.append(
        "gradle/libs.versions.toml: facebook-login wajib 16.3.0 "
        "(v2.0.12 #1)"
    )
_lg_auth_lines = [
    _lg_line for _lg_line in _lg_toml.splitlines()
    if "firebase-auth-ktx" in _lg_line
    and not _lg_line.strip().startswith("#")
]
if not _lg_auth_lines:
    errors.append(
        "gradle/libs.versions.toml: firebase-auth-ktx wajib ada "
        "(v2.0.12 #1)"
    )
elif "version.ref" in _lg_auth_lines[0]:
    errors.append(
        "gradle/libs.versions.toml: firebase-auth-ktx wajib dikelola "
        "firebase-bom (tanpa version.ref, v2.0.12 #1)"
    )
if '"32.7.0"' not in _lg_toml:
    errors.append(
        "gradle/libs.versions.toml: firebase-bom wajib tetap 32.7.0 "
        "(v2.0.12 #1)"
    )

# 23b. Versi 18 / "2.0.13" (re-assert §22c — gerbang versi harus
#      lolos SETELAH bump).
if "versionCode = 18" not in _lg_gradle:
    errors.append("app/build.gradle.kts: versionCode wajib 18 (v2.0.13)")
if 'versionName = "2.0.13"' not in _lg_gradle:
    errors.append('app/build.gradle.kts: versionName wajib "2.0.13" (v2.0.13)')

# 23c. FirebaseAuthRepository: signInWithCredential untuk kedua
#      provider (Google + Facebook) + signOut membersihkan Prefs
#      auth dan pref Ecchi.
if not _lg_repo_path.exists():
    errors.append(
        "FirebaseAuthRepository.kt tidak ada — implementasi login "
        "Firebase (v2.0.12 #2)"
    )
else:
    _lg_repo = _lg_repo_path.read_text(encoding="utf-8")
    for _lg_need in (
        "GoogleAuthProvider.getCredential",
        "FacebookAuthProvider.getCredential",
        "signInWithCredential",
    ):
        if _lg_need not in _lg_repo:
            errors.append(
                f"FirebaseAuthRepository.kt: {_lg_need} wajib ada — "
                "masuk kedua provider lewat Firebase Auth (v2.0.12 #2)"
            )
    if "clearAuth()" not in _lg_repo or "clearEcchi()" not in _lg_repo:
        errors.append(
            "FirebaseAuthRepository.kt: signOut() wajib memanggil "
            "clearAuth() + clearEcchi() (v2.0.12 #2)"
        )
    # Literal persis "not_configured" (dengan kutip) — pesan Stub.
    # "facebook_not_configured" TIDAK BOLEH ikut kena (substring).
    if '"not_configured"' in _lg_repo:
        errors.append(
            "FirebaseAuthRepository.kt: TIDAK BOLEH mengembalikan "
            "'not_configured' — itu hanya milik Stub (v2.0.12 #2)"
        )
    for _lg_bad in ("Log.d", "Log.i", "Log.w", "Log.e", "println("):
        if _lg_bad in _lg_repo:
            errors.append(
                f"FirebaseAuthRepository.kt: {_lg_bad} DILARANG — token "
                "login tidak boleh ditulis ke log (v2.0.12 #2)"
            )

# 23d. String login_belum_tersedia TIDAK LAGI dipakai di jalur masuk
#      aktif (hanya boleh tersisa definisinya di strings.xml sebagai
#      resource tak terpakai; di Kotlin = 0).
_lg_belum_count = 0
for _lg_kt in (ROOT / "app" / "src").rglob("*.kt"):
    if "login_belum_tersedia" in _lg_kt.read_text(encoding="utf-8", errors="replace"):
        _lg_belum_count += 1
if _lg_belum_count:
    errors.append(
        "login_belum_tersedia masih dipakai di Kotlin — string 'Masuk "
        "belum tersedia' TIDAK BOLEH muncul lagi setelah Firebase "
        "dipasang (v2.0.12 #2)"
    )

# 23e. (DIPERBARUI v2.0.13) Gerbang Ecchi: aturan v2.0.12
#      "DetailActivity TIDAK disentuh" DICABUT oleh PRD v2.0.13 item 2
#      (fail-safe) — DetailActivity BOLEH berubah, TAPI gerbangnya
#      tetap memeriksa isSignedIn() + ecchiEnabled == "1" dan akses
#      auth-nya wajib terjaga runCatching (gagal init → tamu, bukan
#      crash). SettingActivity tetap hanya menyimpan ecchiEnabled bila
#      sudah login (kini lewat baca fail-safe).
_lg_detail = _lg_detail_path.read_text(encoding="utf-8")
if "isSignedIn()" not in _lg_detail or 'ecchiEnabled == "1"' not in _lg_detail:
    errors.append(
        "DetailActivity.kt: gerbang Ecchi wajib tetap memeriksa "
        "isSignedIn() + ecchiEnabled == \"1\" (v2.0.12 #3, v2.0.13 #2)"
    )
if "runCatching { app().authRepository.isSignedIn() }" not in _lg_detail:
    errors.append(
        "DetailActivity.kt: akses auth gerbang Ecchi wajib terjaga "
        "runCatching — gagal init auth harus jadi tamu, bukan crash "
        "(v2.0.13 #2)"
    )
_lg_setting = _lg_setting_path.read_text(encoding="utf-8")
if "isSignedIn() == true" not in _lg_setting:
    errors.append(
        "SettingActivity.kt: saveAndFinish hanya boleh menyimpan "
        "ecchiEnabled bila sudah login (v2.0.12 #3, v2.0.13 #2)"
    )

# 23f. Binding aktif = FirebaseAuthRepository (satu baris di
#      AnimeloversApp.kt); Stub TIDAK BOLEH lagi direferensikan.
_lg_app = _lg_app_path.read_text(encoding="utf-8")
if "StubAuthRepository" in _lg_app:
    errors.append(
        "AnimeloversApp.kt: StubAuthRepository TIDAK BOLEH lagi "
        "direferensikan — binding aktif wajib Firebase (v2.0.12 #4)"
    )
if _lg_app.count("FirebaseAuthRepository(this)") != 1:
    errors.append(
        "AnimeloversApp.kt: binding wajib tepat satu baris "
        "'FirebaseAuthRepository(this)' (v2.0.12 #4)"
    )

# 23g. Manifest Facebook: meta-data ApplicationId + string resource
#      facebook_app_id; TIDAK ADA string App Secret di app/src.
_lg_manifest = _lg_manifest_path.read_text(encoding="utf-8")
if "com.facebook.sdk.ApplicationId" not in _lg_manifest:
    errors.append(
        "AndroidManifest.xml: meta-data com.facebook.sdk.ApplicationId "
        "wajib ada (v2.0.12 #5)"
    )
if "@string/facebook_app_id" not in _lg_manifest:
    errors.append(
        "AndroidManifest.xml: App ID Facebook wajib sebagai string "
        "resource @string/facebook_app_id (v2.0.12 #5)"
    )
if "facebook_app_id" not in _strings_xml:
    errors.append(
        "strings.xml: string facebook_app_id wajib ada (v2.0.12 #5)"
    )
elif 'name="facebook_app_id">0<' in _strings_xml:
    warnings.append(
        "verify.py §23g: facebook_app_id masih placeholder '0' — login "
        "Facebook gagal dengan pesan jujur sampai App ID asli (A3) "
        "dimasukkan; BUKAN error, tapi dicatat"
    )
for _lg_src_file in (ROOT / "app" / "src").rglob("*"):
    if _lg_src_file.suffix not in (".kt", ".xml"):
        continue
    if "app_secret" in _lg_src_file.read_text(
        encoding="utf-8", errors="replace",
    ).lower():
        errors.append(
            f"{_lg_src_file.name}: mengandung 'app_secret' — App Secret "
            "Facebook TIDAK PERNAH boleh masuk repo (v2.0.12 #5)"
        )

# 23h. refreshLoginUi membaca sesi dari repository (bukan hardcode
#      profil): isSignedIn() + userEmail() + userPhotoUrl().
for _lg_read in ("auth.isSignedIn()", "auth.userEmail()", "auth.userPhotoUrl()"):
    if _lg_read not in _lg_setting:
        errors.append(
            f"SettingActivity.kt: refreshLoginUi() wajib membaca "
            f"{_lg_read} dari repository (v2.0.12 #6)"
        )

# 23i. Sesi TIDAK BOLEH dihapus di jalur start aplikasi — tidak ada
#      signOut()/clearAuth() di AnimeloversApp/Splash/MainActivity.
for _lg_name, _lg_path in (
    ("AnimeloversApp", _lg_app_path),
    ("SplashActivity", JAVA / "animelovers" / "rebornhd" / "ui" / "splash" / "SplashActivity.kt"),
    ("MainActivity", JAVA / "animelovers" / "rebornhd" / "ui" / "main" / "MainActivity.kt"),
):
    _lg_text = _lg_path.read_text(encoding="utf-8")
    for _lg_call in ("signOut()", "clearAuth()"):
        if _lg_call in _lg_text:
            errors.append(
                f"{_lg_name}: {_lg_call} DILARANG di jalur start — sesi "
                "masuk wajib bertahan setelah restart (v2.0.12 #7)"
            )

# 23j. (a) Jalur kill switch TIDAK BERUBAH terhadap v2.0.11
#      (diff kosong vs basis 6e908f6); (b) larangan abadi "Video
#      tidak tersedia" = 0 (re-assert §22h).
_lg_ks_diff = _lg_no_diff(
    [
        "app/src/main/java/animelovers/rebornhd/util/KillSwitch.kt",
        "app/src/main/java/animelovers/rebornhd/util/KillSwitchLogic.kt",
    ],
)
if _lg_ks_diff is False:
    errors.append(
        "KillSwitch.kt / KillSwitchLogic.kt berubah terhadap basis "
        "6e908f6 — jalur saklar TIDAK BOLEH disentuh di v2.0.12 "
        "(PRD §B8)"
    )
elif _lg_ks_diff is None:
    warnings.append(
        "verify.py §23j: git diff gagal — tidak bisa memastikan jalur "
        "kill switch tidak berubah"
    )
_lg_all_kt = ""
for _lg_kt in (ROOT / "app" / "src").rglob("*.kt"):
    _lg_all_kt += _lg_kt.read_text(encoding="utf-8", errors="replace") + "\n"
if "Video tidak tersedia" in _lg_all_kt or "Video tidak tersedia" in _strings_xml:
    errors.append(
        'String "Video tidak tersedia" DILARANG SELAMANYA — bukan solusi '
        "(v2.0.12, keputusan Faygo)"
    )

# 23k. Guard R8 utuh (re-assert): keepattributes Signature +
#      keep TypeToken + TypeToken.getParameterized di kode; bila
#      proguard-rules.pro berubah, tambahannya HANYA boleh baris
#      -keep / komentar / kosong (keep yang didokumentasikan).
_lg_proguard_path = ROOT / "app" / "proguard-rules.pro"
_lg_proguard = _lg_proguard_path.read_text(encoding="utf-8")
for _lg_keep in ("-keepattributes Signature", "TypeToken"):
    if _lg_keep not in _lg_proguard:
        errors.append(
            f"proguard-rules.pro: guard R8 ({_lg_keep}) wajib ada "
            "(v2.0.12 #9)"
        )
if "getParameterized" not in _lg_all_kt:
    errors.append(
        "TypeToken.getParameterized wajib dipakai di kode — guard R8 "
        "v2.0.4 tidak boleh hilang (v2.0.12 #9)"
    )
try:
    _lg_pg = subprocess.run(
        ["git", "-C", str(ROOT), "diff", "6e908f6", "--", "app/proguard-rules.pro"],
        capture_output=True, text=True, timeout=15,
    )
    if _lg_pg.returncode == 0 and _lg_pg.stdout.strip():
        for _lg_line in _lg_pg.stdout.splitlines():
            if not _lg_line.startswith("+"):
                continue
            _lg_added = _lg_line[1:].strip()
            if _lg_added.startswith(("+++", "-keep", "#")) or not _lg_added:
                continue
            errors.append(
                "proguard-rules.pro berubah dengan baris bukan -keep — "
                "perubahan guard R8 wajib hanya keep rules yang "
                f"didokumentasikan (baris: {_lg_added[:60]!r}, v2.0.12 #9)"
            )
            break
    elif _lg_pg.returncode != 0:
        warnings.append(
            "verify.py §23k: git diff gagal — tidak bisa memastikan "
            "proguard-rules.pro tidak berubah"
        )
except Exception:
    warnings.append(
        "verify.py §23k: git tidak bisa dijalankan — tidak bisa "
        "memastikan proguard-rules.pro tidak berubah"
    )

# 23l. Semua R.string.* yang dipakai di Kotlin wajib terdefinisi di
#      strings.xml — satu nama hilang = build rilis GAGAL. (Pelajaran
#      saat menulis v2.0.12: `login_dibatalkan` sempat dikira dipakai
#      SettingActivity, padahal cabang batalnya SENGAJA tanpa toast —
#      gerbang ini memastikan yang BENAR-BENAR dipakai semuanya ada.)
_lg_defined_strings = set(
    re.findall(r'name="([^"]+)"', _strings_xml),
)
_lg_missing_strings = set()
for _lg_kt in (ROOT / "app" / "src").rglob("*.kt"):
    _lg_kt_text = _lg_kt.read_text(encoding="utf-8", errors="replace")
    for _lg_ref in re.findall(r"R\.string\.([A-Za-z0-9_]+)", _lg_kt_text):
        if _lg_ref not in _lg_defined_strings:
            _lg_missing_strings.add(_lg_ref)
for _lg_missing in sorted(_lg_missing_strings):
    errors.append(
        f"R.string.{_lg_missing} dipakai di Kotlin tapi TIDAK ADA di "
        "strings.xml — build rilis akan gagal (v2.0.12 #6)"
    )

# ---------- v2.0.13 (perbaikan crash startup: Client Token + hardening) ----------

# 24a. Client Token Facebook terisi nilai asli (PRD §B1): meta-data
#      com.facebook.sdk.ClientToken ada di manifest + string
#      facebook_client_token ada di strings.xml + nilainya BUKAN
#      placeholder/kosong — pola + panjang minimum (bukan menghafal
#      nilai): minimal 16 karakter heksadesimal. Nilai asli dari A1
#      (Client Token — dirancang untuk kode klien, seperti App ID).
#      Gerbang ini SENGAJA GAGAL selama token TBD (string kosong /
#      placeholder) — Deploy berhenti sampai token asli diisi.
if "com.facebook.sdk.ClientToken" not in _lg_manifest:
    errors.append(
        "AndroidManifest.xml: meta-data com.facebook.sdk.ClientToken "
        "wajib ada (v2.0.13 #1)"
    )
if "@string/facebook_client_token" not in _lg_manifest:
    errors.append(
        "AndroidManifest.xml: Client Token Facebook wajib sebagai string "
        "resource @string/facebook_client_token (v2.0.13 #1)"
    )
_lg_token_match = re.search(
    r'name="facebook_client_token">([^<]*)<', _strings_xml,
)
if not _lg_token_match:
    errors.append(
        "strings.xml: string facebook_client_token wajib ada (v2.0.13 #1)"
    )
else:
    _lg_token = _lg_token_match.group(1).strip()
    if (
        not _lg_token
        or _lg_token in ("TBD", "TODO", "PLACEHOLDER", "ISI_TOKEN", "0")
        or not re.fullmatch(r"[0-9a-fA-F]{16,}", _lg_token)
    ):
        errors.append(
            "strings.xml: facebook_client_token wajib terisi nilai asli "
            "dari A1 (minimal 16 karakter heksadesimal) — placeholder "
            "TBD/kosong TIDAK BOLEH lolos; Deploy berhenti sampai token "
            "asli diisi (v2.0.13 #1)"
        )

# 24b. isEcchiUnlocked() fail-safe (PRD §B2): mengandung penanganan
#      exception (try/catch atau runCatching) di sekitar akses
#      authRepository dan mengembalikan false bila akses gagal; DAN
#      tidak ada lagi akses authRepository tanpa penjagaan di jalur
#      bind pertama Beranda (HomeFragment.applyData hanya memanggil
#      isEcchiUnlocked(), tidak menyentuh authRepository langsung).
_lg_main_path = (
    JAVA / "animelovers" / "rebornhd" / "ui" / "main" / "MainActivity.kt"
)
_lg_main_kt = _lg_main_path.read_text(encoding="utf-8")
_lg_unlocked = re.search(
    r"fun isEcchiUnlocked\(\)[^{]*\{(.*?)\n    \}",
    _lg_main_kt, re.DOTALL,
)
if _lg_unlocked is None:
    errors.append(
        "MainActivity.kt: isEcchiUnlocked() tidak ditemukan (v2.0.13 #2)"
    )
else:
    _lg_body = _lg_unlocked.group(1)
    if "authRepository" not in _lg_body:
        errors.append(
            "MainActivity.kt: isEcchiUnlocked() wajib membaca "
            "authRepository (v2.0.13 #2)"
        )
    if "runCatching" not in _lg_body and "try {" not in _lg_body:
        errors.append(
            "MainActivity.kt: isEcchiUnlocked() wajib mengandung "
            "penanganan exception (runCatching/try) di sekitar akses "
            "authRepository — gagal init auth harus jadi tamu, bukan "
            "crash (v2.0.13 #2)"
        )
    if "getOrDefault(false)" not in _lg_body:
        errors.append(
            "MainActivity.kt: isEcchiUnlocked() wajib mengembalikan "
            "false bila akses auth gagal (v2.0.13 #2)"
        )
_lg_home_path = (
    JAVA / "animelovers" / "rebornhd" / "ui" / "main" / "home"
    / "HomeFragment.kt"
)
_lg_home_kt = _lg_home_path.read_text(encoding="utf-8")
if "authRepository" in _lg_home_kt:
    errors.append(
        "HomeFragment.kt: jalur bind Beranda TIDAK BOLEH menyentuh "
        "authRepository langsung — hanya lewat isEcchiUnlocked() yang "
        "terjaga (v2.0.13 #2)"
    )
if "isEcchiUnlocked()" not in _lg_home_kt:
    errors.append(
        "HomeFragment.kt: bind Beranda wajib memakai isEcchiUnlocked() "
        "(v2.0.13 #2)"
    )

# 24c. Versi 18 / "2.0.13".
if "versionCode = 18" not in _lg_gradle:
    errors.append("app/build.gradle.kts: versionCode wajib 18 (v2.0.13)")
if 'versionName = "2.0.13"' not in _lg_gradle:
    errors.append('app/build.gradle.kts: versionName wajib "2.0.13" (v2.0.13)')

# 24d. (a) App Secret TIDAK ada di repo (re-assert §23g); (b) token di
#      strings.xml BUKAN App ID (pemeriksaan kewarasan — token dan App
#      ID adalah nilai berbeda). Perbandingan penuh token-vs-Secret
#      adalah gerbang Orkestrator/Deploy, bukan grep (Frontend tidak
#      memegang Secret).
for _lg_src_file in (ROOT / "app" / "src").rglob("*"):
    if _lg_src_file.suffix not in (".kt", ".xml"):
        continue
    if "app_secret" in _lg_src_file.read_text(
        encoding="utf-8", errors="replace",
    ).lower():
        errors.append(
            f"{_lg_src_file.name}: mengandung 'app_secret' — App Secret "
            "Facebook TIDAK PERNAH boleh masuk repo (v2.0.13 §24d)"
        )
_lg_app_id_match = re.search(
    r'name="facebook_app_id">([^<]*)<', _strings_xml,
)
if _lg_token_match and _lg_app_id_match:
    if (
        _lg_token_match.group(1).strip()
        == _lg_app_id_match.group(1).strip()
    ):
        errors.append(
            "strings.xml: facebook_client_token TIDAK BOLEH sama dengan "
            "facebook_app_id — keduanya nilai berbeda (v2.0.13 §24d)"
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
