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
