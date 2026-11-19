#!/usr/bin/env python3
"""Uji negatif permanen gerbang verify.py (§20e L-1/L-2) — F-6 Cari Bug v2.0.10.

Menyalin repo + `tasks/uji/src/TestV210.kt` ke struktur sementara yang
sama (`<tmp>/animelovers-lawas` + `<tmp>/tasks/uji/src/TestV210.kt`),
lalu menjalankan SALINAN verify.py di tiap salinan:
  - bersih: verify.py harus OK (exit 0) — baseline, pohonnya sehat;
  - NEG-A interpolasi: `diagLogger?.warn(TAG, "raw=${raw.take(50)}")`
    disisipkan di SankaWebTransport (variabel `raw` ada di scope) →
    verify.py harus ERROR (gerbang L-1 menangkap bentuk interpolasi —
    dulu lolos karena strip_kotlin membuang isi string);
  - NEG-A telanjang: `diagLogger?.warn(TAG, raw)` → harus ERROR (kontrol:
    bentuk ini sudah tertangkap sebelum F-6);
  - NEG-B: `sanitize(` dihapus dari badan fun log() DiagLogger.kt →
    harus ERROR (gerbang L-2).
Keluar 0 bila semua kasus sesuai harapan, 1 bila ada yang meleset.
Repo asli TIDAK disentuh (semua injeksi di salinan /tmp).
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PROJECT = REPO.parent
VERIFY = REPO / "tools" / "verify.py"

NEG_A_INTERP = 'diagLogger?.warn(TAG, "raw=${raw.take(50)}")'
NEG_A_BARE = "diagLogger?.warn(TAG, raw)"
ANCHOR_RAW = (
    "val raw = decodeJsResult(jsResult)\n"
    "                                            ?: throw SankaEmptyResponseException()"
)
L1_NEEDLE = "tidak boleh dipanggil dengan variabel `raw`"
L2_NEEDLE = "sanitize( wajib ada DI DALAM badan fun log()"


def run_verify(repo_copy: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(repo_copy / "tools" / "verify.py")],
        capture_output=True, text=True, timeout=300,
    )


def fresh_copy(tmp: Path) -> Path:
    repo_copy = tmp / "animelovers-lawas"
    shutil.copytree(
        REPO, repo_copy,
        ignore=shutil.ignore_patterns(".git", "build", ".gradle", "local.properties"),
    )
    test_src = PROJECT / "tasks" / "uji" / "src" / "TestV210.kt"
    test_dst = tmp / "tasks" / "uji" / "src" / "TestV210.kt"
    test_dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(test_src, test_dst)
    return repo_copy


def inject_after(path: Path, anchor: str, line: str) -> None:
    text = path.read_text(encoding="utf-8")
    if anchor not in text:
        raise AssertionError(f"anchor tidak ketemu di {path.name}: {anchor!r}")
    text = text.replace(anchor, anchor + "\n" + line, 1)
    path.write_text(text, encoding="utf-8")


def main() -> int:
    failures: list[str] = []
    tmp = Path(tempfile.mkdtemp(prefix="verify-neg-"))
    try:
        # 1. Baseline: salinan bersih harus OK.
        repo = fresh_copy(tmp / "bersih")
        r = run_verify(repo)
        if r.returncode != 0:
            failures.append(
                "baseline bersih: verify.py ERROR padahal pohon bersih:\n"
                + r.stdout[-2000:]
            )
        else:
            print("PASS bersih: verify.py OK (exit 0)")

        # 2. NEG-A interpolasi.
        repo = fresh_copy(tmp / "nega-interp")
        inject_after(
            repo / "app/src/main/java/animelovers/rebornhd/data/api/SankaWebTransport.kt",
            ANCHOR_RAW, " " * 40 + NEG_A_INTERP,
        )
        r = run_verify(repo)
        if r.returncode == 0 or L1_NEEDLE not in r.stdout:
            failures.append(
                "NEG-A interpolasi: verify.py TIDAK menangkap "
                f'"raw=${{raw.take(50)}}" (exit {r.returncode})'
            )
        else:
            print("PASS NEG-A interpolasi: gerbang L-1 menangkap")

        # 3. NEG-A telanjang (kontrol).
        repo = fresh_copy(tmp / "nega-bare")
        inject_after(
            repo / "app/src/main/java/animelovers/rebornhd/data/api/SankaWebTransport.kt",
            ANCHOR_RAW, " " * 40 + NEG_A_BARE,
        )
        r = run_verify(repo)
        if r.returncode == 0 or L1_NEEDLE not in r.stdout:
            failures.append(
                f"NEG-A telanjang: verify.py TIDAK menangkap (exit {r.returncode})"
            )
        else:
            print("PASS NEG-A telanjang: gerbang L-1 menangkap")

        # 4. NEG-B: hapus sanitize( dari badan fun log().
        repo = fresh_copy(tmp / "negb")
        log_path = (
            repo / "app/src/main/java/animelovers/rebornhd/util/DiagLogger.kt"
        )
        log_text = log_path.read_text(encoding="utf-8")
        if "DiagSanitize.sanitize(message)" not in log_text:
            raise AssertionError("anchor NEG-B tidak ketemu di DiagLogger.kt")
        log_path.write_text(
            log_text.replace("DiagSanitize.sanitize(message)", "message", 1),
            encoding="utf-8",
        )
        r = run_verify(repo)
        if r.returncode == 0 or L2_NEEDLE not in r.stdout:
            failures.append(
                f"NEG-B: verify.py TIDAK menangkap sanitize() hilang "
                f"(exit {r.returncode})"
            )
        else:
            print("PASS NEG-B: gerbang L-2 menangkap")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    if failures:
        print()
        for f in failures:
            print(f"FAIL {f}")
        return 1
    print("\nOK — semua uji negatif gerbang lolos.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
