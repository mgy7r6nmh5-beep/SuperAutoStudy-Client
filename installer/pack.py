# -*- coding: utf-8 -*-
"""把 dist-portable/SuperAutoStudy 打包成便携 ZIP。

对「本身已压缩」的扩展名用 STORED（jar/zip/png/...），其余 DEFLATE，
这样 800MB 的树能在几分钟内压完，体积和全 DEFLATE 几乎一样。
"""
import os
import sys
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)          # 仓库根
SRC = os.path.join(ROOT, "dist-portable", "SuperAutoStudy")
OUT = os.path.join(ROOT, "dist-portable", "SuperAutoStudy-portable.zip")

STORED_EXT = {".jar", ".zip", ".png", ".jpg", ".jpeg", ".gz", ".7z", ".mp4", ".xz"}

if os.path.exists(OUT):
    os.remove(OUT)

files = []
for dirpath, dirnames, filenames in os.walk(SRC):
    for fn in filenames:
        full = os.path.join(dirpath, fn)
        rel = os.path.relpath(full, os.path.dirname(SRC))
        files.append((full, rel.replace("\\", "/")))
files.sort(key=lambda x: x[1])

t0 = time.time()
n_stored = n_defl = 0
with zipfile.ZipFile(OUT, "w", allowZip64=True) as z:
    for full, rel in files:
        ext = os.path.splitext(rel)[1].lower()
        if ext in STORED_EXT:
            z.write(full, rel, compress_type=zipfile.ZIP_STORED)
            n_stored += 1
        else:
            z.write(full, rel, compress_type=zipfile.ZIP_DEFLATED, compresslevel=6)
            n_defl += 1
        if (n_stored + n_defl) % 300 == 0:
            print("  ...%d files, %.0fs" % (n_stored + n_defl, time.time() - t0), flush=True)

print("added %d files (stored=%d deflated=%d) in %.0fs"
      % (n_stored + n_defl, n_stored, n_defl, time.time() - t0), flush=True)

# 校验
with zipfile.ZipFile(OUT) as z:
    bad = z.testzip()
    print("testzip bad =", bad, flush=True)
    names = z.namelist()
print("entries =", len(names))
tops = sorted({n.split("/")[0] for n in names})
print("top-level =", tops)
print("size = %.1f MB" % (os.path.getsize(OUT) / 1024.0 / 1024.0))
