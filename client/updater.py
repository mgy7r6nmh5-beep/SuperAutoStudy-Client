# -*- coding: utf-8 -*-
"""SuperAutoStudy 客户端 OTA 自更新。

为什么要单独一个文件：
  - server.py 已经很长，更新逻辑（查询 GitHub Release / 下载校验 / 落盘 /
    重启）独立出来便于单独测试；
  - 「客户端补丁包」本身也会覆盖本文件 —— 自更新一个正在运行的模块没有安全
    问题（模块已加载进内存，重启后才生效），但 apply 过程中绝不能 import
    新代码，所以所有逻辑都在进程启动时一次性加载。

两种更新模式（下载后都先校验 SHA256 再动手）：
  client —— 下载发布页的「客户端补丁包」（约几百 KB，只含 client/ 代码文件），
            备份旧 client/ → 按 manifest 逐个校验并替换 → 重启客户端。
            后端 jar / MySQL / 正在跑的刷课线程完全不受影响。
  full   —— 下载完整安装包，静默覆盖安装（Inno，AppId 不变所以是原地升级），
            再重启客户端。安装期间 Windows 重启管理器会关闭占用文件的进程
            （java / mysqld），刷课会暂停大约一分钟，装完回界面点「启动服务」即可。

镜像 / 手动来源：apply 支持传 asset_url + asset_sha256 覆盖默认下载地址，
便于内网镜像或本地测试（校验哈希这一关无论如何都要过）。
"""

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
import zipfile

# 当前客户端版本。每次发布时手动提升，需与 installer/setup.iss 的 MyAppVersion、
# GitHub Release 的 tag 保持一致（发布流程见 README「发版」一节）。
CURRENT_VERSION = "1.0.4"

DEFAULT_REPO = "mgy7r6nmh5-beep/SuperAutoStudy-Client"
API_BASE = "https://api.github.com"
USER_AGENT = "SuperAutoStudy-Updater"

CLIENT_ASSET_RE = re.compile(r"SuperAutoStudy-client-.*\.zip$", re.I)
SETUP_ASSET_RE = re.compile(r"SuperAutoStudy-Setup-.*\.exe$", re.I)

CHECK_CACHE_SECONDS = 3600        # 检查结果缓存：启动时查一次，之后最多每小时一次
AUTO_CHECK_DELAY = 20             # 启动后多久做第一次自动检查（秒）
AUTO_CHECK_INTERVAL = 6 * 3600    # 自动检查间隔


# ------------------------------------------------------------------ 版本号

def parse_version(text):
    """'v1.0.4' / '1.0.4-beta' -> (1, 0, 4)。解析失败按 (0, 0, 0) 处理。"""
    if not text:
        return (0, 0, 0)
    m = re.search(r"(\d+(?:\.\d+)*)", str(text))
    if not m:
        return (0, 0, 0)
    parts = []
    for p in m.group(1).split(".")[:4]:
        try:
            parts.append(int(p))
        except ValueError:
            parts.append(0)
    while len(parts) < 3:
        parts.append(0)
    return tuple(parts)


def version_gt(a, b):
    return parse_version(a) > parse_version(b)


# ------------------------------------------------------------------ 网络

def _http_get_json(url, timeout=20):
    req = urllib.request.Request(url, headers={
        "User-Agent": USER_AGENT,
        "Accept": "application/vnd.github+json",
    })
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(256 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _download(url, dest, expect_sha256=None, progress=None, timeout=60):
    """下载到 dest（先写 .part 再原子改名）。给了期望哈希就硬校验，不符即删。"""
    if not url:
        raise RuntimeError("没有下载地址")
    tmp = dest + ".part"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    h = hashlib.sha256()
    done = 0
    with urllib.request.urlopen(req, timeout=timeout) as resp, open(tmp, "wb") as f:
        total = int(resp.headers.get("Content-Length") or 0)
        while True:
            chunk = resp.read(256 * 1024)
            if not chunk:
                break
            f.write(chunk)
            h.update(chunk)
            done += len(chunk)
            if progress:
                progress(done, total)
    digest = h.hexdigest()
    if expect_sha256 and digest.lower() != expect_sha256.lower():
        try:
            os.remove(tmp)
        except OSError:
            pass
        raise RuntimeError("SHA256 校验失败：期望 %s…，实际 %s…"
                           % (expect_sha256[:16], digest[:16]))
    os.replace(tmp, dest)
    return digest


# ------------------------------------------------------------------ 更新器

class Updater:
    """线程安全。check() 可随时调用；apply() 只能在后台线程里跑（结尾会重启进程）。"""

    def __init__(self, settings_getter, root):
        self._settings = settings_getter      # callable -> 有 .data  dict 的对象
        self.root = root                      # 部署根目录（含 launch.cmd 的那层）
        self._lock = threading.RLock()
        self.state = {
            "current": CURRENT_VERSION,
            "checking": False,
            "last_check": 0.0,
            "error": None,
            "info": None,                     # check() 的完整结果
            "applying": False,
            "progress": None,                 # {"phase": str, "done": int, "total": int}
            "log": [],                        # 应用过程的关键步骤（给人看）
        }

    # ---------------- 内部小工具 ----------------

    def _log(self, msg):
        with self._lock:
            self.state["log"].append("[%s] %s" % (time.strftime("%H:%M:%S"), msg))
            self.state["log"] = self.state["log"][-60:]

    def _set_phase(self, phase, done=0, total=0):
        with self._lock:
            self.state["progress"] = {"phase": phase, "done": done, "total": total}

    def repo(self):
        try:
            r = (self._settings().data.get("update_repo") or "").strip()
        except Exception:
            r = ""
        return r or DEFAULT_REPO

    # ---------------- 检查 ----------------

    def check(self, force=False):
        """查 GitHub 最新 Release。有缓存（CHECK_CACHE_SECONDS），按钮点的是 force。"""
        with self._lock:
            if self.state["checking"]:
                return dict(self.state["info"] or {})
            now = time.time()
            if (not force and self.state["info"]
                    and now - self.state["last_check"] < CHECK_CACHE_SECONDS):
                return self.state["info"]
            self.state["checking"] = True
            self.state["error"] = None
        try:
            repo = self.repo()
            rel = _http_get_json("%s/repos/%s/releases/latest" % (API_BASE, repo))
            tag = str(rel.get("tag_name") or "")
            latest = tag.lstrip("vV") or CURRENT_VERSION
            assets = []
            for a in rel.get("assets") or []:
                assets.append({
                    "name": a.get("name") or "",
                    "size": int(a.get("size") or 0),
                    "digest": (a.get("digest") or "").split(":", 1)[-1] or None,
                    "url": a.get("url"),                      # API 下载地址（走 api.github.com）
                    "browser_download_url": a.get("browser_download_url"),
                })
            client_asset = next((a for a in assets if CLIENT_ASSET_RE.search(a["name"])), None)
            setup_asset = next((a for a in assets if SETUP_ASSET_RE.search(a["name"])), None)
            info = {
                "repo": repo,
                "tag": tag,
                "latest": latest,
                "published": rel.get("published_at"),
                "notes": (rel.get("body") or "")[:4000],
                "has_update": version_gt(latest, CURRENT_VERSION),
                "same_version": parse_version(latest) == parse_version(CURRENT_VERSION),
                "client_asset": client_asset,
                "setup_asset": setup_asset,
                "checked_at": now,
            }
            with self._lock:
                self.state["info"] = info
                self.state["last_check"] = now
            return info
        except Exception as exc:
            with self._lock:
                self.state["error"] = "%s: %s" % (type(exc).__name__, exc)
            raise
        finally:
            with self._lock:
                self.state["checking"] = False

    # ---------------- 应用 ----------------

    def apply(self, mode, asset_url=None, asset_sha256=None):
        """mode: 'client' | 'full'。会重启进程，务必在后台线程调用。

        asset_url / asset_sha256：覆盖默认资产地址（内网镜像 / 本地测试用），
        哈希校验无论如何都会执行。
        """
        with self._lock:
            if self.state["applying"]:
                raise RuntimeError("已经有一个更新任务在跑")
            self.state["applying"] = True
            self.state["error"] = None
            self.state["progress"] = {"phase": "准备", "done": 0, "total": 0}
            self.state["log"] = []
        try:
            info = self.state.get("info") or {}
            if mode == "client":
                self._apply_client_patch(info.get("client_asset"),
                                         asset_url, asset_sha256)
            elif mode == "full":
                self._apply_full_setup(info.get("setup_asset"),
                                       asset_url, asset_sha256)
            else:
                raise RuntimeError("未知更新模式: %r" % (mode,))
        except Exception as exc:
            with self._lock:
                self.state["applying"] = False
                self.state["progress"] = None
                self.state["error"] = "%s: %s" % (type(exc).__name__, exc)
                self._log("失败：%s" % self.state["error"])
            raise

    def _asset_source(self, asset, asset_url, asset_sha256):
        """确定 (url, sha256, name)。显式传参优先，否则用 release 里的资产。"""
        if asset_url:
            return asset_url, (asset_sha256 or None), (asset or {}).get("name") or "update.bin"
        if not asset:
            raise RuntimeError("该版本没有对应的下载资产")
        url = asset.get("url") or asset.get("browser_download_url")
        return url, (asset_sha256 or asset.get("digest")), asset.get("name")

    # ---------------- 模式一：客户端补丁 ----------------

    def _apply_client_patch(self, asset, asset_url=None, asset_sha256=None):
        url, want, name = self._asset_source(asset, asset_url, asset_sha256)
        self._log("开始更新客户端：%s" % name)
        tmpdir = tempfile.mkdtemp(prefix="sas_client_upd_")
        try:
            zip_path = os.path.join(tmpdir, "client.zip")
            self._set_phase("下载补丁包")
            _download(url, zip_path, want, self._progress_cb)
            self._log("下载完成（%.1f KB）" % (os.path.getsize(zip_path) / 1024))

            self._set_phase("校验文件清单")
            with zipfile.ZipFile(zip_path) as z:
                names = set(z.namelist())
                if "manifest.json" not in names:
                    raise RuntimeError("补丁包缺少 manifest.json")
                manifest = json.loads(z.read("manifest.json").decode("utf-8"))
                entries = manifest.get("files") or []
                if not entries:
                    raise RuntimeError("manifest.json 的文件清单是空的")
                staged = []                      # [(部署内相对路径, bytes)]
                for e in entries:
                    rel = (e.get("path") or "").replace("\\", "/")
                    if not rel.startswith("client/") or rel.count(".."):
                        raise RuntimeError("manifest 含非法路径: %r" % rel)
                    if rel not in names:
                        raise RuntimeError("补丁包缺少文件: %s" % rel)
                    data = z.read(rel)
                    got = hashlib.sha256(data).hexdigest()
                    if e.get("sha256") and got != e["sha256"].lower():
                        raise RuntimeError("文件校验失败: %s" % rel)
                    staged.append((rel, data))
                self._log("清单校验通过：%d 个文件，版本 %s"
                          % (len(staged), manifest.get("version")))

            self._set_phase("备份当前客户端")
            client_dir = os.path.join(self.root, "client")
            backup = os.path.join(self.root, "_update_backup_%s"
                                  % time.strftime("%Y%m%d_%H%M%S"))
            if os.path.isdir(client_dir):
                shutil.copytree(
                    client_dir, backup,
                    ignore=shutil.ignore_patterns("__pycache__", "*.pyc",
                                                  "client.log", "_shot", "*.bak-*"))
                self._log("已备份到 %s" % os.path.basename(backup))

            self._set_phase("替换文件")
            for rel, data in staged:
                dest = os.path.join(self.root, rel.replace("/", os.sep))
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with open(dest, "wb") as f:
                    f.write(data)
            self._log("已替换 %d 个文件" % len(staged))

            # 清掉字节码缓存，防止旧 .pyc 被复用
            for dp, dn, fn in os.walk(client_dir):
                if os.path.basename(dp) == "__pycache__":
                    shutil.rmtree(dp, ignore_errors=True)
            self._log("已清理 __pycache__")
        finally:
            shutil.rmtree(tmpdir, ignore_errors=True)

        # 更新动作已全部完成，先复位 applying：剩下只有重启这一步，
        # 万一重启失败，界面也应显示「更新完成但重启失败」而不是「更新中」。
        with self._lock:
            self.state["applying"] = False
        self._set_phase("重启客户端")
        self._log("更新完成，正在重启…")
        self.restart_client()

    # ---------------- 模式二：完整安装包 ----------------

    def _apply_full_setup(self, asset, asset_url=None, asset_sha256=None):
        url, want, name = self._asset_source(asset, asset_url, asset_sha256)
        self._log("开始完整更新：%s" % name)
        tmpdir = tempfile.mkdtemp(prefix="sas_full_upd_")
        try:
            exe = os.path.join(tmpdir, name if name.endswith(".exe") else "setup.exe")
            self._set_phase("下载安装包")
            _download(url, exe, want, self._progress_cb)
            self._log("安装包下载完成（%.1f MB）" % (os.path.getsize(exe) / 1048576))

            self._set_phase("静默安装")
            log = os.path.join(tmpdir, "setup.log")
            # AppId 不变 → 原地覆盖升级；/SP- 跳过「这是升级」的询问；
            # 安装期间 java/mysqld 会被重启管理器关闭（重启客户端后回界面点启动服务）。
            proc = subprocess.run(
                [exe, "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/SP-",
                 "/LOG=%s" % log],
                timeout=900, check=False)
            self._log("安装器退出码 %s" % proc.returncode)
            if proc.returncode not in (0, 1):
                raise RuntimeError("安装失败，详见 %s" % log)
        finally:
            shutil.rmtree(tmpdir, ignore_errors=True)

        # 同客户端补丁：安装已完成，重启只是收尾，先复位状态
        with self._lock:
            self.state["applying"] = False
        self._set_phase("重启客户端")
        self._log("更新完成，正在重启…")
        self.restart_client()

    # ---------------- 下载进度回调 ----------------

    def _progress_cb(self, done, total):
        p = self.state.get("progress") or {}
        self._set_phase(p.get("phase", "下载"), done, total)

    # ---------------- 重启 ----------------

    def restart_client(self):
        """拉起 launch.cmd（脱离会话）后退出自己。失败就不退，把错误留给界面。"""
        launch = os.path.join(self.root, "launch.cmd")
        if not os.path.isfile(launch):
            with self._lock:
                self.state["applying"] = False
                self.state["progress"] = None
                self.state["error"] = "找不到 launch.cmd：%s" % launch
                self._log("重启失败：%s" % self.state["error"])
            return False
        try:
            flags = (subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
                     | subprocess.CREATE_NO_WINDOW)
            subprocess.Popen(["cmd.exe", "/c", launch], cwd=self.root,
                             stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, creationflags=flags,
                             close_fds=True)
        except Exception as exc:
            with self._lock:
                self.state["applying"] = False
                self.state["progress"] = None
                self.state["error"] = "拉起 launch.cmd 失败: %s" % exc
                self._log("重启失败：%s" % self.state["error"])
            return False
        time.sleep(1.2)
        os._exit(0)


def start_auto_check(updater, log=print):
    """后台线程：启动后延迟查一次，之后每 6 小时一次。只记状态，不自动安装。"""
    def loop():
        time.sleep(AUTO_CHECK_DELAY)
        while True:
            try:
                updater.check(force=True)
            except Exception as exc:
                try:
                    log("[updater] 自动检查失败: %s" % exc)
                except Exception:
                    pass
            time.sleep(AUTO_CHECK_INTERVAL)
    t = threading.Thread(target=loop, daemon=True, name="ota-check")
    t.start()
    return t
