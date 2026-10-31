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

# 绝对不能进发布包的东西。
# ai-config.json 里是用户自己的大模型 key，quiz-dump 是抓题失败时的原始页面
# （含账号 cookie 和课程内容）。就算有人手动把它们拷进了暂存树，这里也拦一道。
SKIP_REL = {
    "app/ai-config.json",
    "client/settings.json",
    # 日志和临时文件不该进发布包：boot.log 里有账号、课程名这些
    "app/boot.log",
    "app/live.log",
    "app/app.log",
    "my.ini",
    "mysql57/console.log",
    "mysql57/console.log.old",
    "mysql57/mysql-error.log",
}
SKIP_DIRS = {
    "app/quiz-dump",
    "__pycache__",
}

if os.path.exists(OUT):
    os.remove(OUT)

files = []
skipped = []
for dirpath, dirnames, filenames in os.walk(SRC):
    for fn in filenames:
        full = os.path.join(dirpath, fn)
        rel = os.path.relpath(full, os.path.dirname(SRC))
        rel = rel.replace("\\", "/")
        key = rel.split("/", 1)[1] if "/" in rel else rel
        parts = key.split("/")
        if (key in SKIP_REL
                or any(p in SKIP_DIRS for p in parts[:-1])
                or key.endswith(".pyc")):
            skipped.append(key)
            continue
        files.append((full, rel))
files.sort(key=lambda x: x[1])
if skipped:
    print("已排除 %d 个不该发布的文件: %s" % (len(skipped), ", ".join(sorted(skipped)[:5])),
          flush=True)

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
