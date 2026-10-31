# -*- coding: utf-8 -*-
"""打「客户端补丁包」—— OTA 用的最小更新单元。

只替换 client/ 下的代码，不碰后端 jar / MySQL / 正在跑的刷课线程，
所以是几百 KB 的增量包，装完不用重启后端。

补丁包结构（updater._apply_client_patch 会严格校验）：
    manifest.json                     {"version": ..., "files": [{path, sha256}]}
    client/...                        部署内相对路径，必须以 client/ 开头

用法：
    python installer/patch.py 1.0.18
输出：
    dist-portable/SuperAutoStudy-client-1.0.18.zip
"""
import hashlib
import json
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)                      # 仓库根
OUT_DIR = os.path.join(ROOT, "dist-portable")

# 按固定顺序列出，便于 diff 两次补丁的差别。
# 新增 client/ 下的文件时记得加到这里，否则补丁包不会带上它。
FILES = [
    "client/_update_sentinel.cmd",
    "client/desktop.py",
    "client/server.py",
    "client/settings.example.json",
    "client/updater.py",
    "client/web/app.js",
    "client/web/index.html",
    "client/web/style.css",
    "client/web/tokens.css",
    "client/启动客户端.cmd",
]


def main():
    version = sys.argv[1] if len(sys.argv) > 1 else ""
    if not version:
        raise SystemExit("用法: python installer/patch.py <版本号>  例如 1.0.18")

    entries = []
    for rel in FILES:
        full = os.path.join(ROOT, rel.replace("/", os.sep))
        if not os.path.isfile(full):
            raise SystemExit("缺文件: %s" % rel)
        with open(full, "rb") as f:
            entries.append({"path": rel,
                            "sha256": hashlib.sha256(f.read()).hexdigest()})

    manifest = {"version": version, "files": entries}
    out = os.path.join(OUT_DIR, "SuperAutoStudy-client-%s.zip" % version)
    os.makedirs(OUT_DIR, exist_ok=True)

    with zipfile.ZipFile(out, "w", allowZip64=True) as z:
        z.writestr("manifest.json",
                   json.dumps(manifest, ensure_ascii=False, indent=2))
        for rel in FILES:
            z.write(os.path.join(ROOT, rel.replace("/", os.sep)), rel,
                    compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)

    # 自检：manifest 里每个 sha256 都要和包内实际内容对得上
    with zipfile.ZipFile(out) as z:
        names = set(z.namelist())
        m = json.loads(z.read("manifest.json").decode("utf-8"))
        for e in m["files"]:
            if e["path"] not in names:
                raise SystemExit("包内缺文件: %s" % e["path"])
            got = hashlib.sha256(z.read(e["path"])).hexdigest()
            if got != e["sha256"]:
                raise SystemExit("校验失败: %s" % e["path"])

    print("wrote %s" % out)
    print("version = %s, files = %d, size = %.1f KB"
          % (version, len(entries), os.path.getsize(out) / 1024.0))
    print("self-check OK")


if __name__ == "__main__":
    main()
