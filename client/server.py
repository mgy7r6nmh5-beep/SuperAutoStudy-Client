# -*- coding: utf-8 -*-
"""SuperAutoStudy 客户端 —— 本地服务

一个纯标准库（+ pymysql）实现的本地 HTTP 服务：

  · 托管前端静态页面（web/）
  · 提供 REST API：服务状态 / 课程队列 / 日志 / 登录 / 选课 / 设置 / 服务启停
  · 管理 MySQL 与后端子进程（CREATE_NO_WINDOW，不弹黑框）

只读为主。写操作只有三类，且都由用户在界面上显式触发：
  · 启动 / 停止服务
  · 队列的增（加课）/ 删（删队列行）/ 改（优先级）
  · 保存设置

用法：
    python server.py               # 只起服务，浏览器访问 http://127.0.0.1:3080
    python desktop.py              # 起服务 + 原生窗口（pywebview）
"""

import io
import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, quote

# ------------------------------------------------------------------ 常量

APP_NAME = "SuperAutoStudy"
HTTP_PORT = 3030            # Spring Boot 后端
CLIENT_PORT = 3080          # 本客户端服务
DB_PORT = 3306
DB_HOST = "127.0.0.1"
DB_USER = "root"
DB_PASS = "SuperAuto2026"
DB_NAME = "tihai"

# 启动判定：进程存活但 30 秒未就绪即超时；轮询间隔 0.5 秒
READY_TIMEOUT = 30
POLL_INTERVAL = 0.5

DEFAULT_ROOT = ""  # 留空：部署根目录交给 find_root() 自动探测；打包分发时绝不能写死个人路径

CREATE_NO_WINDOW = 0x08000000
CREATE_NEW_PROCESS_GROUP = 0x00000200
FLAGS_HIDDEN = CREATE_NO_WINDOW | CREATE_NEW_PROCESS_GROUP

# my.ini 的模板。basedir / datadir / log-error 必须按当前部署根目录算，
# 不能沿用打包机上的路径 —— 之前分发包里带的是写死 C:/Users/<打包者>/... 的 my.ini，
# 换台机器 mysqld 就找不到 datadir，直接起不来。
MY_INI_TEMPLATE = """[mysqld]
basedir={basedir}
datadir={datadir}
port={port}
bind-address={host}
character-set-server=utf8mb4
collation-server=utf8mb4_general_ci
max_connections=200
max_allowed_packet=64M
log-error={logerror}
slow_query_log=0
log_syslog=0
default-authentication-plugin=mysql_native_password
explicit_defaults_for_timestamp=1
sql_mode=STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION
innodb_log_file_size=16M
innodb_log_files_in_group=2

[client]
port={port}
default-character-set=utf8mb4

[mysql]
default-character-set=utf8mb4
"""

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
WEB_DIR = os.path.join(BASE_DIR, "web")
SETTINGS_PATH = os.path.join(BASE_DIR, "settings.json")

# 与 com.tihai.enums.WkTaskStatusEnum 一一对应
# 3 是历史遗留状态，当前代码不再产生，保留映射只为兼容旧数据库里的残留行
STATUS_TEXT = {0: "待处理", 1: "进行中", 2: "队列中", 3: "已废弃",
               4: "已完成", 5: "异常", 6: "暂停"}

DEFAULT_SETTINGS = {
    # 面向用户的设置只剩「运行时防止电脑休眠」。
    # 刷课节奏、线程数、播放遍数、断点续刷等全部由后端固定策略决定，
    # 旧 settings.json 里的废弃键在读取/保存时一律丢弃。
    "keep_awake": True,
}

# ------------------------------------------------------------------ 防休眠
# 刷课要跑很久，Windows 默认 15~30 分钟无操作就睡眠，一睡超星的上报就断了
# （视频进度停在原地，甚至被判定异常）。所以客户端运行期间持续调用
# SetThreadExecutionState 告诉系统「我在干活，别睡」。
#
# 标志含义：
#   ES_CONTINUOUS          让后面两个标志持续生效（而不是只生效一次）
#   ES_SYSTEM_REQUIRED     阻止系统休眠（最关键）
#   ES_AWAYMODE_REQUIRED   进入「离开模式」：屏幕照样关，但系统不算睡眠、
#                          音频继续播 —— 省电和不误事兼得
ES_CONTINUOUS = 0x80000000
ES_SYSTEM_REQUIRED = 0x00000001
ES_AWAYMODE_REQUIRED = 0x00000040
_AWAKE_FLAGS = ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_AWAYMODE_REQUIRED
_AWAKE_RESET = ES_CONTINUOUS

_KEEP_AWAKE = {"supported": False, "active": False, "last_err": None}


def _set_execution_state(flags):
    """调用 SetThreadExecutionState；非 Windows 或缺 ctypes 时静默失败。"""
    try:
        import ctypes
        prev = ctypes.windll.kernel32.SetThreadExecutionState(flags)
        return int(prev)
    except Exception as exc:  # 非 Windows / pythonw 无权限 都不影响主流程
        _KEEP_AWAKE["last_err"] = str(exc)
        return 0


def keep_awake_supported():
    if not _KEEP_AWAKE["supported"]:
        _KEEP_AWAKE["supported"] = bool(
            sys.platform.startswith("win")
            and _set_execution_state(_AWAKE_RESET))
    return _KEEP_AWAKE["supported"]


def keep_awake_loop():
    """后台守护：查开关每 1 秒、续期每 25 秒。

    SetThreadExecutionState 的标志是「持续」型，重复调用只是保险，防止别的
    程序把系统状态改回去。查开关和续期分开跑，是为了界面关掉开关后最多 1 秒
    就生效，不用干等 25 秒。active 反映最近一次调用的真实成败（返回 0 = 失败）。
    """
    last_on = None
    last_refresh = 0.0
    while True:
        try:
            on = bool(SETTINGS.data.get("keep_awake", True))
        except Exception:
            on = False
        if on and not keep_awake_supported():
            on = False
        if on != last_on:
            if on:
                _KEEP_AWAKE["active"] = bool(_set_execution_state(_AWAKE_FLAGS))
            else:
                _set_execution_state(_AWAKE_RESET)
                _KEEP_AWAKE["active"] = False
            last_on = on
            last_refresh = time.time()
        elif on and time.time() - last_refresh >= 25:
            _KEEP_AWAKE["active"] = bool(_set_execution_state(_AWAKE_FLAGS))
            last_refresh = time.time()
        time.sleep(1)


_KEEP_AWAKE_STARTED = False


def start_keep_awake_thread():
    """把 keep_awake_loop 拉起来（幂等，哪里启动都能调）。

    server.py 有两种入口：`python server.py` 直接跑（走 main()），以及被
    desktop.py import 后由它自己起 HTTP 服务。桌面窗口模式**不会**调用
    main()，所以守护线程必须在 import 时启动，否则桌面模式下防休眠完全不
    生效（界面显示"不支持"，因为 supported 永远是初始值 False）。
    """
    global _KEEP_AWAKE_STARTED
    if _KEEP_AWAKE_STARTED:
        return True
    try:
        threading.Thread(target=keep_awake_loop, daemon=True,
                         name="keep-awake").start()
        _KEEP_AWAKE_STARTED = True
        return True
    except Exception:
        return False


if sys.platform.startswith("win"):
    try:
        start_keep_awake_thread()
    except Exception:
        pass


# ANSI 转义序列。Spring Boot 的彩色输出重定向到文件后 ESC 字节会丢，
# 只剩 "[1;32m" 这种纯文本，所以要按无 ESC 的形式也匹配一次。
ANSI_RE = re.compile(r"\x1b\[[0-9;]*m|\[\d+(?:;\d+)*m")

LOG_TAIL_LINES = 300
LOG_TAIL_BYTES = 200000

# ------------------------------------------------------------------ 工具


def _candidate_roots():
    """所有可能是部署根目录的路径（含 mysql57 的那层）。"""
    out = []
    p = BASE_DIR
    for _ in range(5):
        p = os.path.dirname(p)
        if not p or p in out:
            break
        out.append(p)
    if DEFAULT_ROOT and DEFAULT_ROOT not in out:
        out.append(DEFAULT_ROOT)
    return [c for c in out if os.path.isdir(os.path.join(c, "mysql57"))]


def _live_log_mtime(root):
    try:
        return os.path.getmtime(os.path.join(root, "app", "live.log"))
    except OSError:
        return -1.0


def find_root():
    """定位部署根目录（含 mysql57 与 app 的那层）。

    同一台机器上可能同时存在两份部署（开发实例 + 便携包），各有各的
    app/live.log。若只认自己所在目录，便携包客户端就会读到包内那份空白
    日志，界面显示"没有在刷视频"——哪怕另一个实例正刷得热火朝天。

    所以按可靠性依次尝试：
      1) 问正在运行的 3030 后端"你的部署目录在哪"（GET /api/where）
      2) 候选目录里 live.log 最近被写过的那个
      3) 自身目录
    """
    cands = _candidate_roots()
    if not cands:
        # 兜底：client/ 的上一级就是部署根（不再依赖写死的个人路径）
        return os.path.dirname(BASE_DIR)

    if port_open(HTTP_PORT):
        try:
            r = http_json("http://127.0.0.1:{}/api/where".format(HTTP_PORT), timeout=5)
            data = r.get("data") if isinstance(r, dict) else None
            root = data.get("root") if isinstance(data, dict) else None
            if root:
                norm = os.path.normpath(root).lower()
                for c in cands:
                    if os.path.normpath(c).lower() == norm:
                        return c
        except Exception:
            pass

    if len(cands) > 1:
        best = max(cands, key=_live_log_mtime)
        if _live_log_mtime(best) > 0:
            return best

    return cands[0]


def port_open(port, host="127.0.0.1", timeout=0.35):
    with socket.socket() as s:
        s.settimeout(timeout)
        try:
            return s.connect_ex((host, port)) == 0
        except OSError:
            return False


def oem_text(raw):
    """命令行工具输出按 OEM 代码页解码（中文 Windows 是 cp936）。"""
    if raw is None:
        return ""
    try:
        import ctypes
        cp = ctypes.windll.kernel32.GetOEMCP()
        return raw.decode("cp{}".format(cp), errors="replace")
    except Exception:
        return raw.decode("utf-8", errors="replace")


def run_hidden(args, timeout=20):
    try:
        p = subprocess.run(args, capture_output=True, timeout=timeout,
                           creationflags=CREATE_NO_WINDOW)
        return oem_text(p.stdout)
    except Exception:
        return ""


def pid_on_port(port):
    out = run_hidden(["netstat", "-ano", "-p", "TCP"], timeout=8)
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 5 and parts[3] == "LISTENING" and parts[1].endswith(":" + str(port)):
            try:
                return int(parts[4])
            except ValueError:
                pass
    return None


def kill_pid(pid):
    try:
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"],
                       capture_output=True, timeout=15,
                       creationflags=CREATE_NO_WINDOW)
        return True
    except Exception:
        return False


def proc_start_time(pid):
    """进程启动时间（本地 naive datetime）；拿不到返回 None。"""
    if not pid:
        return None
    try:
        import ctypes
        import ctypes.wintypes as wt
        from datetime import datetime
        k32 = ctypes.windll.kernel32
        h = k32.OpenProcess(0x1000, False, int(pid))
        if not h:
            return None
        try:
            c, e, k, u = (wt.FILETIME(), wt.FILETIME(), wt.FILETIME(), wt.FILETIME())
            if not k32.GetProcessTimes(h, ctypes.byref(c), ctypes.byref(e),
                                       ctypes.byref(k), ctypes.byref(u)):
                return None
            ticks = (c.dwHighDateTime << 32) | c.dwLowDateTime
            return datetime.fromtimestamp(ticks / 1e7 - 11644473600.0)
        finally:
            k32.CloseHandle(h)
    except Exception:
        return None


def tail_lines(path, n=LOG_TAIL_LINES, max_bytes=LOG_TAIL_BYTES):
    try:
        size = os.path.getsize(path)
    except OSError:
        return []
    try:
        with open(path, "rb") as f:
            if size > max_bytes:
                f.seek(size - max_bytes)
            data = f.read()
    except OSError:
        return []
    lines = data.decode("utf-8", errors="replace").splitlines()
    if size > max_bytes and lines:
        lines = lines[1:]
    return [ANSI_RE.sub("", ln) for ln in lines[-n:]]


# ------------------------------------------------------------------ 日志增量读
#
# 老实现每次轮询都重读日志末尾 300 行，靠 LiveTracker 的 _seen 集合按
# 「整行字符串」去重。这个去重有个致命歧义：**同一个视频被重复学习时**
# （第一遍没计分重来 / 重复任务点 / 轮次重放），第二批「播放位置」日志行和
# 第一批逐字节相同（线程名、任务名、位置、遍数、第几报全都一样），于是全部
# 被当成重复行丢弃 —— 卡片的位置停在第一次的值不动，最终 300 秒过期消失，
# 表现为「池子在刷课但界面只有一张卡」。
#
# 改成按字节偏移增量读：每行只会被处理一次，与内容是否重复无关。
_LOG_READ_LOCK = threading.Lock()
_LOG_OFFSET = {"pos": 0, "path": None}

# 首读只回放日志尾部这么多字节。用 LOG_TAIL_BYTES（200KB）的话会把上一个进程
# 遗留的「开始学习视频」一起喂进 LiveTracker，那些线程早已不存在，卡片却按
# 「刚看到」算时间，于是总览上多出一张跑不完的幽灵卡（实测 elapsed=2581s）。
FIRST_READ_BYTES = 16384


def read_log_incremental(path):
    """只返回自上次调用以来新增的日志行（按字节偏移，不做内容去重）。"""
    with _LOG_READ_LOCK:
        try:
            size = os.path.getsize(path)
        except OSError:
            return []
        if _LOG_OFFSET.get("path") != path:    # 换文件了（boot.log ↔ live.log）：偏移重来
            _LOG_OFFSET["path"] = path
            _LOG_OFFSET["pos"] = 0
        start = _LOG_OFFSET["pos"]
        mid_start = False
        if size < start:                       # 日志被截断/轮转：从头读
            start = 0
        elif start == 0 and size > FIRST_READ_BYTES:
            start = size - FIRST_READ_BYTES    # 首读只取尾部，避免全量重放
            mid_start = True
        if size <= start:
            return []
        try:
            with open(path, "rb") as f:
                f.seek(start)
                data = f.read()
        except OSError:
            return []
        # 只有「从文件中间切进去」那一次才可能撞上半行；正常增量读取是从行首开始的，
        # 这时候再丢第一行会把刚写进来的进度日志整条吃掉（之前就踩了这个）。
        if mid_start and not data.startswith(b"\n"):
            cut = data.find(b"\n")
            if cut < 0:
                return []
            start += cut + 1
            data = data[cut + 1:]
        end = start + len(data)
        if data and not data.endswith(b"\n"):
            cut = data.rfind(b"\n")            # 末行可能只写了一半，退回完整行
            if cut < 0:
                return []
            data = data[:cut + 1]
            end = start + len(data)
        _LOG_OFFSET["pos"] = end
        lines = data.decode("utf-8", errors="replace").splitlines()
        return [ANSI_RE.sub("", ln) for ln in lines if ln.strip()]


def http_json(url, payload=None, method=None, timeout=30):
    """调后端 JSON 接口。"""
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    req = urllib.request.Request(url, data=data, headers=headers,
                                 method=method or ("POST" if data else "GET"))
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8", errors="replace")
    try:
        return json.loads(raw)
    except Exception:
        return {"code": 200, "raw": raw}


# 必须在 http_json / port_open 定义之后求值：find_root 会向后端探测部署目录
ROOT = find_root()


# ------------------------------------------------------------------ 启动探测

def probe_backend(timeout=2.0):
    """ready: 本部署的后端；busy: 3030 被别的程序占用；absent: 端口没开。"""
    if not port_open(HTTP_PORT):
        return "absent", None
    try:
        data = http_json("http://127.0.0.1:{}/api/where".format(HTTP_PORT), timeout=timeout)
    except Exception:
        return "busy", None
    payload = (data or {}).get("data") or {}
    root = payload.get("root")
    if not root:
        return "busy", data
    try:
        same = os.path.normcase(os.path.abspath(root)) == \
            os.path.normcase(os.path.abspath(ROOT))
    except Exception:
        same = False
    return ("ready" if same else "busy"), data


def environment_self_check():
    """JDK / jar 缺失自检；端口归属由 probe_backend 判定。"""
    if not os.path.exists(SVC.java):
        return {"ok": False, "errorCode": "STARTUP-JDK-001",
                "reason": "随包 JDK 缺失", "nextStep": "重新安装客户端或恢复 jdk8 目录"}
    if not SVC.jar():
        return {"ok": False, "errorCode": "STARTUP-JAR-001",
                "reason": "后端 jar 缺失", "nextStep": "重新安装或修复 app/target 下的 jar"}
    state, _ = probe_backend()
    if state == "busy":
        return {"ok": False, "errorCode": "STARTUP-PORT-001",
                "reason": "3030 已被其他程序占用", "nextStep": "关闭占用 3030 的程序后重试"}
    return {"ok": True}


def parse_startup_failure(path):
    """从日志尾部解析 ==== STARTUP FAILED ==== 块；找不到返回 None。"""
    try:
        with io.open(path, "r", encoding="utf-8", errors="replace") as f:
            lines = f.read().splitlines()
    except OSError:
        return None
    start = None
    for i in range(len(lines) - 1, -1, -1):
        if "==== STARTUP FAILED ====" in ANSI_RE.sub("", lines[i]):
            start = i
            break
    if start is None:
        return None
    fields = {}
    for line in lines[start:]:
        clean = ANSI_RE.sub("", line).strip()
        if clean.startswith("===="):
            if "END STARTUP FAILED" in clean:
                break
            continue
        if "=" not in clean:
            continue
        key, value = clean.split("=", 1)
        fields[key.strip()] = value.strip()
    if not fields.get("code"):
        return None
    return fields


# ------------------------------------------------------------------ 设置


class Settings:
    def __init__(self, path):
        self.path = path
        self.data = dict(DEFAULT_SETTINGS)
        self.load()

    def load(self):
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                saved = json.load(f)
        except Exception:
            return self.data
        if isinstance(saved, dict):
            keep = saved.get("keep_awake", DEFAULT_SETTINGS["keep_awake"])
            self.data["keep_awake"] = keep if isinstance(keep, bool) else DEFAULT_SETTINGS["keep_awake"]
        return self.data

    def save(self, patch):
        """只接受 keep_awake；未知键与旧版刷课参数一律丢弃。"""
        if isinstance(patch, dict) and isinstance(patch.get("keep_awake"), bool):
            self.data["keep_awake"] = patch["keep_awake"]
        self.data = {"keep_awake": bool(self.data.get("keep_awake", True))}
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.data, f, ensure_ascii=False, indent=2)
        os.replace(tmp, self.path)
        return self.data


SETTINGS = Settings(SETTINGS_PATH)


# ------------------------------------------------------------------ AI 配置
# 章节测验靠大模型作答，配置放哪很讲究：后端（java）的工作目录是 <root>/app，
# AiAnswerService 读的是**相对路径** ai-config.json，所以文件必须落在 app/ 下。
# 塞进 client/settings.json 是没用的 —— 两边读的根本不是一个文件。
AI_BASE_URL = "https://api.deepseek.com"
AI_MODEL = "deepseek-chat"
AI_TIMEOUT_SEC = 60


def ai_config_path():
    return os.path.join(SVC.appdir, "ai-config.json")


def load_ai_config():
    """读 AI 配置；文件不存在时返回默认值（api_key 为空 = 未配置）。"""
    cfg = {"base_url": AI_BASE_URL, "api_key": "", "model": AI_MODEL,
           "timeout_sec": AI_TIMEOUT_SEC}
    try:
        with open(ai_config_path(), "r", encoding="utf-8") as f:
            saved = json.load(f)
    except Exception:
        return cfg
    if isinstance(saved, dict):
        for k in ("base_url", "api_key", "model"):
            v = saved.get(k)
            if isinstance(v, str) and v.strip():
                cfg[k] = v.strip()
        try:
            cfg["timeout_sec"] = int(saved.get("timeout_sec") or AI_TIMEOUT_SEC)
        except (TypeError, ValueError):
            pass
    return cfg


def save_ai_config(patch):
    """写 AI 配置。

    api_key 传空串或传掩码（sk-****abcd）一律理解为「不改」——界面回显的是掩码，
    用户只改模型名时如果把掩码原样回传当真 key 存下去，key 就废了。
    """
    cfg = load_ai_config()
    if isinstance(patch, dict):
        for k in ("base_url", "model"):
            v = patch.get(k)
            if isinstance(v, str) and v.strip():
                cfg[k] = v.strip()
        key = patch.get("api_key")
        if isinstance(key, str):
            key = key.strip()
            if key and "****" not in key:
                cfg["api_key"] = key
        try:
            t = int(patch.get("timeout_sec") or cfg["timeout_sec"])
            cfg["timeout_sec"] = max(10, min(300, t))
        except (TypeError, ValueError):
            pass
    path = ai_config_path()
    d = os.path.dirname(path)
    if d and not os.path.isdir(d):
        os.makedirs(d)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)
    return cfg


def mask_key(key):
    if not key:
        return ""
    if len(key) <= 8:
        return "****"
    return key[:3] + "****" + key[-4:]


def ai_config_public(cfg):
    return {
        "base_url": cfg["base_url"],
        "model": cfg["model"],
        "timeout_sec": cfg["timeout_sec"],
        "api_key": mask_key(cfg["api_key"]),
        "has_key": bool(cfg["api_key"]),
        "path": ai_config_path(),
    }


def ai_endpoint(cfg):
    """拼 /chat/completions 地址。

    必须和 Java 那边 Config.endpoint() 一字不差地同规则 —— 界面上的「测一下」
    要测的就是后端真正会去请求的那个地址，不然测通了照样白搭。
    """
    base = (cfg.get("base_url") or "").strip().rstrip("/")
    if base.endswith("/chat/completions"):
        return base
    if not base.endswith("/v1"):
        base += "/v1"
    return base + "/chat/completions"


def test_ai_config(cfg):
    """真发一个最小请求，确认 key / 地址 / 模型三者都对得上。

    只能在客户端（python）这边发：各家大模型 API 基本都不给 CORS 头，
    浏览器直连必定被拦，看起来像"key 无效"。
    """
    if not cfg.get("api_key"):
        return {"ok": False, "error": "还没填 api_key"}
    payload = {
        "model": cfg["model"],
        "messages": [{"role": "user", "content": "只回两个字：能用"}],
        "max_tokens": 8,
        "temperature": 0,
        "stream": False,
    }
    req = urllib.request.Request(
        ai_endpoint(cfg),
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        method="POST",
        headers={"Authorization": "Bearer " + cfg["api_key"],
                 "Content-Type": "application/json; charset=utf-8"})
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=min(60, cfg["timeout_sec"])) as resp:
            body = resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:300]
        return {"ok": False, "error": "HTTP {} {}".format(exc.code, detail)}
    except Exception as exc:
        return {"ok": False, "error": str(exc)}
    ms = int((time.time() - started) * 1000)
    try:
        reply = json.loads(body)["choices"][0]["message"]["content"]
    except Exception:
        return {"ok": False, "error": "返回的不是 OpenAI 格式：" + body[:200]}
    return {"ok": True, "reply": str(reply).strip()[:40], "ms": ms}


# ------------------------------------------------------------------ 服务控制


class Services:
    def __init__(self, root):
        self.root = root
        self.mysql_proc = None
        self.app_proc = None
        self._mysql_fh = None
        self._app_fh = None

    # ---- 路径
    @property
    def mysqld(self):
        return os.path.join(self.root, "mysql57", "bin", "mysqld.exe")

    @property
    def mysqladmin(self):
        return os.path.join(self.root, "mysql57", "bin", "mysqladmin.exe")

    @property
    def myini(self):
        return os.path.join(self.root, "my.ini")

    @property
    def java(self):
        return os.path.join(self.root, "jdk8", "bin", "java.exe")

    @property
    def appdir(self):
        return os.path.join(self.root, "app")

    @property
    def applog(self):
        return os.path.join(self.appdir, "app.log")

    @property
    def livelog(self):
        return os.path.join(self.appdir, "live.log")

    @property
    def bootlog(self):
        """仅承接日志系统初始化前的极早期崩溃输出。"""
        return os.path.join(self.appdir, "boot.log")

    def log_path(self):
        """界面日志和「正在刷的视频」都要盯 boot.log。

        v1.0.16 起 start_app() 把 java 的 stdout/stderr 重定向到 boot.log
        （早期崩溃也能留证据），logback 的 CONSOLE appender 也跟着进了 boot.log，
        所以 boot.log 才是「当前进程的完整输出」——刷课进度那些 System.out
        （续刷课程 / 开始学习视频 / 播放位置）全在里面。

        live.log 是 logback 的 FILE appender，只收日志事件、没有 System.out，
        而且进程重启后不再续写。原来这里优先返回 live.log，结果界面日志视图和
        LiveTracker 都盯着一个不再更新的文件 —— 总览「正在刷的视频」「最近完成的视频」
        永远是空的，日志页也停在旧进程的内容上。
        """
        if os.path.exists(self.bootlog):
            return self.bootlog
        return self.livelog

    def jar(self):
        d = os.path.join(self.appdir, "target")
        if not os.path.isdir(d):
            return None
        jars = sorted(f for f in os.listdir(d)
                      if f.startswith("SuperAuto-") and f.endswith(".jar"))
        return os.path.join(d, jars[-1]) if jars else None

    # ---- 启动
    def ensure_my_ini(self):
        """确保 my.ini 里的路径指向当前部署目录，返回是否重写过。

        安装包/绿色包里可能带着别人机器上的 my.ini，直接拿去启动 mysqld 会指向
        一个不存在的 datadir。所以每次启动前核一遍 basedir/datadir，对不上就重写。
        用户自己改过的其它选项（比如调 max_connections）只要路径是对的就不动。
        """
        mysql_dir = os.path.join(self.root, "mysql57")
        basedir = mysql_dir.replace("\\", "/")
        datadir = os.path.join(mysql_dir, "data").replace("\\", "/")
        logerror = os.path.join(mysql_dir, "mysql-error.log").replace("\\", "/")

        try:
            with io.open(self.myini, "r", encoding="utf-8", errors="replace") as f:
                old = f.read()
        except OSError:
            old = ""
        if "basedir={}".format(basedir) in old and "datadir={}".format(datadir) in old:
            return False

        body = MY_INI_TEMPLATE.format(basedir=basedir, datadir=datadir,
                                      logerror=logerror, port=DB_PORT, host=DB_HOST)
        with io.open(self.myini, "w", encoding="utf-8", newline="\r\n") as f:
            f.write(body)
        return True

    def start_mysql(self):
        if port_open(DB_PORT):
            return "already", "MySQL 已在运行"
        if not os.path.exists(self.mysqld):
            return "err", "找不到 {}".format(self.mysqld)
        logpath = os.path.join(self.root, "mysql57", "console.log")
        if self.ensure_my_ini():
            print("[my.ini] 路径已按当前目录重写：{}".format(self.root))
        try:
            self._mysql_fh = open(logpath, "ab", buffering=0)
            self.mysql_proc = subprocess.Popen(
                [self.mysqld, "--defaults-file={}".format(self.myini), "--console"],
                cwd=self.root, stdin=subprocess.DEVNULL,
                stdout=self._mysql_fh, stderr=self._mysql_fh,
                creationflags=FLAGS_HIDDEN)
        except Exception as exc:
            return "err", "启动 MySQL 失败: {}".format(exc)
        return "ok", "MySQL 启动中"

    def start_app(self):
        state, _ = probe_backend()
        if state == "ready":
            return "already", "后端已在运行"
        if state == "busy":
            return "port-busy", "3030 已被其他程序占用"
        jar = self.jar()
        if not jar:
            return "err", "后端 jar 缺失（app/target）"
        if not os.path.exists(self.java):
            return "err", "找不到随包 JDK：{}".format(self.java)
        args = [self.java, "-Dfile.encoding=UTF-8", "-jar", jar,
                "--server.port={}".format(HTTP_PORT)]

        try:
            self._app_fh = open(self.bootlog, "ab", buffering=0)
            self.app_proc = subprocess.Popen(
                args, cwd=self.appdir, stdin=subprocess.DEVNULL,
                stdout=self._app_fh, stderr=self._app_fh,
                creationflags=FLAGS_HIDDEN)
        except Exception as exc:
            return "err", "启动后端失败: {}".format(exc)
        return "ok", "后端启动中"

    # ---- 停止
    def stop_app(self):
        if not port_open(HTTP_PORT):
            return "already", "后端本来就没在跑"
        pid = pid_on_port(HTTP_PORT)
        if pid is None and self.app_proc and self.app_proc.poll() is None:
            pid = self.app_proc.pid
        if pid is None:
            return "err", "找不到后端进程"
        kill_pid(pid)
        return "ok", "后端已停止"

    def stop_mysql(self):
        if not port_open(DB_PORT):
            return "already", "MySQL 本来就没在跑"
        env = dict(os.environ, MYSQL_PWD=DB_PASS)
        try:
            subprocess.run(
                [self.mysqladmin, "-u", DB_USER, "--host={}".format(DB_HOST),
                 "--port={}".format(DB_PORT), "shutdown"],
                env=env, capture_output=True, timeout=25,
                creationflags=CREATE_NO_WINDOW)
            for _ in range(20):
                if not port_open(DB_PORT):
                    return "ok", "MySQL 已停止"
                time.sleep(0.4)
        except Exception:
            pass
        pid = pid_on_port(DB_PORT)
        if pid:
            kill_pid(pid)
            return "ok", "MySQL 已强制停止"
        return "err", "MySQL 停止失败"

    # ---- 数据库
    def connect(self, timeout=3):
        import pymysql
        return pymysql.connect(
            host=DB_HOST, port=DB_PORT, user=DB_USER, password=DB_PASS,
            database=DB_NAME, charset="utf8mb4",
            connect_timeout=timeout, read_timeout=5, write_timeout=5)

    PROGRESS_SQL = """
SELECT q.id, q.course_id, q.course_name, q.status, q.priority, q.retry_count,
       q.creat_time,
       l.current_chapter_index, l.current_progress, l.status AS lstatus,
       l.current_job, l.error_message, l.remark, l.start_time, l.end_time,
       l.chapter_id, l.failed_chapters, l.video_position_seconds
FROM th_wk_queue q
LEFT JOIN (
    SELECT t.login_account, t.course_name, t.current_chapter_index,
           t.current_progress, t.status, t.current_job,
           t.error_message, t.remark, t.start_time, t.end_time,
           t.chapter_id, t.failed_chapters, t.video_position_seconds
    FROM th_wk_log t
    JOIN (SELECT login_account, course_name, MAX(id) AS mid
          FROM th_wk_log GROUP BY login_account, course_name) m
      ON t.login_account = m.login_account
     AND t.course_name  = m.course_name
     AND t.id           = m.mid
) l ON l.login_account = q.login_account AND l.course_name = q.course_name
ORDER BY q.creat_time
"""

    LOGIN_SQL = """
SELECT account, CHAR_LENGTH(COALESCE(cookies, '')) AS cookie_len
  FROM th_wk_user ORDER BY id DESC LIMIT 1
"""

    def fetch_progress(self):
        conn = self.connect()
        try:
            with conn.cursor() as cur:
                cur.execute(self.PROGRESS_SQL)
                rows = list(cur.fetchall())
                cur.execute(self.LOGIN_SQL)
                login = cur.fetchone()
            return rows, login
        finally:
            conn.close()

    def fetch_account(self):
        """当前账号（含明文密码 —— 加课要用）。"""
        conn = self.connect()
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT account, password, name, school_name, "
                            "CHAR_LENGTH(COALESCE(cookies,'')) FROM th_wk_user "
                            "ORDER BY id DESC LIMIT 1")
                return cur.fetchone()
        finally:
            conn.close()

    def pending_quiz(self):
        """待人工确认的测验条数（给标签页小红点用）。

        th_wk_quiz 是后端启动时建的，后端从没跑过的库上表不存在，
        这时返回 0 就行 —— 不能因为一个小红点把整个 /api/state 打挂。
        """
        conn = self.connect()
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT COUNT(*) FROM th_wk_quiz WHERE status IN (1,5)")
                row = cur.fetchone()
                return int(row[0] or 0) if row else 0
        except Exception:
            return 0
        finally:
            conn.close()

    # 老库升级要补的列。实体字段是写死在 Java 里的，库里缺列会直接
    # "Unknown column"，整门课都跑不起来。所以启动时先对一遍 information_schema。
    SCHEMA_PATCHES = [
        ("th_wk_log", "chapter_id",
         "ALTER TABLE th_wk_log ADD COLUMN chapter_id varchar(64) NULL DEFAULT NULL "
         "COMMENT '当前章节id（非序号，防章节顺序变化错位）' AFTER current_chapter_index"),
        ("th_wk_log", "failed_chapters",
         "ALTER TABLE th_wk_log ADD COLUMN failed_chapters varchar(1024) NULL DEFAULT NULL "
         "COMMENT '本轮未通过校验的章节id，逗号分隔' AFTER chapter_id"),
        ("th_wk_log", "video_position_seconds",
         "ALTER TABLE th_wk_log ADD COLUMN video_position_seconds int(11) NULL DEFAULT NULL "
         "COMMENT '本地上次成功上报的视频位置（秒）' AFTER failed_chapters"),
        ("th_wk_log", "video_position_job_id",
         "ALTER TABLE th_wk_log ADD COLUMN video_position_job_id varchar(64) NULL DEFAULT NULL "
         "COMMENT '位置所属任务点 jobId' AFTER video_position_seconds"),
    ]

    def ensure_schema(self):
        """给老版本升级上来的库补列，已经是新库就什么都不做。返回补了哪些列。"""
        conn = self.connect()
        try:
            added = []
            with conn.cursor() as cur:
                for table, column, ddl in self.SCHEMA_PATCHES:
                    cur.execute(
                        "SELECT COUNT(*) FROM information_schema.columns "
                        "WHERE table_schema = %s AND table_name = %s AND column_name = %s",
                        (DB_NAME, table, column))
                    if cur.fetchone()[0]:
                        continue
                    cur.execute(ddl)
                    added.append(column)
            conn.commit()
            return added
        finally:
            conn.close()

    def rescan_queue(self):
        with urllib.request.urlopen(
                "http://127.0.0.1:{}/chaoxing".format(HTTP_PORT), timeout=15) as resp:
            return resp.status

    def enroll(self, course_id, course_name):
        """加课：POST /chaoxing（纯 DB 插入 + 入队，不触网）。"""
        acc = self.fetch_account()
        if not acc:
            raise RuntimeError("本地没有账号，请先登录")
        body = {
            "loginAccount": acc[0],
            "password": acc[1],
            "schoolName": acc[3] or "",
            "courseName": course_name,
            "courseId": str(course_id),
        }
        r = http_json("http://127.0.0.1:{}/chaoxing".format(HTTP_PORT), body)
        return r

    def remove_course(self, queue_id):
        conn = self.connect()
        try:
            with conn.cursor() as cur:
                n = cur.execute("DELETE FROM th_wk_queue WHERE id = %s", (queue_id,))
            conn.commit()
            print("[操作] 移除课程 {} -> {}".format(queue_id, "已移除" if n else "不存在"))
            return n
        finally:
            conn.close()

    def set_priority(self, queue_id, priority):
        try:
            p = int(priority)
        except (TypeError, ValueError):
            raise ValueError("优先级必须是 1~10 的整数")
        if p < 1 or p > 10:
            raise ValueError("优先级要在 1~10 之间")
        conn = self.connect()
        try:
            with conn.cursor() as cur:
                n = cur.execute("UPDATE th_wk_queue SET priority = %s WHERE id = %s",
                                (p, queue_id))
            conn.commit()
            print("[操作] 调整优先级 {} -> {}（下一次排队生效）".format(queue_id, p))
            return n
        finally:
            conn.close()

    def set_status(self, queue_id, status):
        conn = self.connect()
        try:
            with conn.cursor() as cur:
                n = cur.execute("UPDATE th_wk_queue SET status = %s WHERE id = %s",
                                (int(status), queue_id))
            conn.commit()
            return n
        finally:
            conn.close()

    def reset_stuck_tasks(self, backend_started=None):
        """把「卡住 / 异常」的任务捞回「待处理」。返回 (重置, 重试, 已完成)。"""
        conn = self.connect()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT q.id, q.status, q.creat_time, l.lstatus, l.start_time, l.end_time "
                    "  FROM th_wk_queue q LEFT JOIN ("
                    "      SELECT t.login_account, t.course_name, t.status AS lstatus, "
                    "             t.start_time, t.end_time "
                    "        FROM th_wk_log t JOIN (SELECT login_account, course_name, MAX(id) AS mid "
                    "                FROM th_wk_log GROUP BY login_account, course_name) m ON t.id = m.mid"
                    "  ) l ON l.login_account = q.login_account AND l.course_name = q.course_name "
                    " WHERE q.status IN (2, 5)")
                rows = cur.fetchall()
                finished, stuck, failed = [], [], []
                for row_id, qstatus, creat_time, lstatus, start_time, end_time in rows:
                    if qstatus == 5:
                        failed.append(row_id)
                        continue
                    if lstatus == 4:
                        finished.append(row_id)
                        continue
                    marks = [t for t in (start_time, end_time) if t is not None]
                    last = max(marks) if marks else creat_time
                    if backend_started is None or last is None or last < backend_started:
                        stuck.append(row_id)

                def upd(status, ids, clear_retry=False):
                    if not ids:
                        return 0
                    if clear_retry:
                        # 用户手动点「续刷 / 重试」= 重新给一轮完整额度。
                        # 后端自愈有 5 次上限（见 StuckTaskRecover / TaskScheduleManager），
                        # 不清零的话刷到上限后就再也跑不动了。
                        return cur.executemany(
                            "UPDATE th_wk_queue SET status = %s, retry_count = 0 WHERE id = %s",
                            [(status, i) for i in ids])
                    return cur.executemany(
                        "UPDATE th_wk_queue SET status = %s WHERE id = %s",
                        [(status, i) for i in ids])

                n_done = upd(4, finished)
                n_retry = upd(0, failed, clear_retry=True)
                n_reset = upd(0, stuck, clear_retry=True)
            conn.commit()
            return n_reset, n_retry, n_done
        finally:
            conn.close()


SVC = Services(ROOT)


# ------------------------------------------------------------------ 直播视频跟踪


class LiveTracker:
    """从后端日志里跟踪「当前正在刷的视频」及其播放进度。

    日志形如：
        priority-pool-2开始学习视频: 课程名:X 任务名:Y 时长:1066s 上报步长:60s 墙钟间隔:60000ms 起始:1791875793000
        priority-pool-2视频已计分: Y（共 19 条上报，耗时 1140s）

    末尾的「起始:<epoch毫秒>」是后端给客户端的绝对起点。老版本日志没有这段，
    那时只能退化成「本服务第一次看到该行的时间」——重启客户端就把进度归零。
    rate 恒为 1（不倍速），所以播放位置 ≈ 已过墙钟时间，推算足够准。
    """

    START_RE = re.compile(
        r"(priority-pool-\d+)开始学习视频: 课程名:(.*?) 任务名:(.*?) 时长:(\d+)s"
        r"(?: 上报步长:\d+s 墙钟间隔:\d+ms| 真实节奏 步长:\d+s)?(?: 起始:(\d+))?")
    DONE_RE = re.compile(r"(priority-pool-\d+)(视频已计分|视频未计分|视频被超星风控拦截): (.*?)[（(]")
    POS_RE = re.compile(
        r"(priority-pool-\d+)播放位置: (.*?) (\d+)s/(\d+)s (?:第)?(\d+)遍 第(\d+)报")
    # 任意提及某线程的行（含 [skip] 跳过测验、异常栈等）——用于刷新存活时间，
    # 让卡片在「视频之间翻页 / 跳过章节测验」的阶段继续可见。
    ANY_THREAD_RE = re.compile(r"priority-pool-\d+")

    def __init__(self):
        self.active = {}     # thread -> dict
        self.recent = []     # 最近完成的视频

    def feed(self, lines):
        for ln in lines:
            tm = self.ANY_THREAD_RE.search(ln)
            if tm and tm.group(0) in self.active:
                self.active[tm.group(0)]["last_seen"] = time.time()
            m = self.POS_RE.search(ln)
            if m:
                thread, job = m.group(1), m.group(2)
                pt, dur = int(m.group(3)), int(m.group(4))
                v = self.active.get(thread)
                if v is None:
                    v = {"thread": thread, "course": "", "job": job,
                         "duration": dur, "started": time.time()}
                    self.active[thread] = v
                v["job"] = job
                v["position"] = pt
                v["duration"] = dur
                v["round"] = int(m.group(5))
                v["reports"] = int(m.group(6))
                v["pos_at"] = time.time()
                v["last_seen"] = time.time()
                continue
            m = self.START_RE.search(ln)
            if m:
                thread, course, job, dur = m.group(1), m.group(2), m.group(3), int(m.group(4))
                epoch = m.group(5)
                # 有绝对起点就用它；否则退化成「第一次看到这行」的时间
                started = (int(epoch) / 1000.0) if epoch else time.time()
                self.active[thread] = {
                    "thread": thread, "course": course, "job": job,
                    "duration": dur, "started": started,
                    "last_seen": time.time(),
                }
                continue
            m = self.DONE_RE.search(ln)
            if m:
                thread, kind, job = m.group(1), m.group(2), m.group(3)
                info = self.active.pop(thread, None)
                self.recent.append({
                    "course": info["course"] if info else "",
                    "job": job,
                    "duration": info["duration"] if info else None,
                    "result": kind,
                    "at": time.time(),
                })
                self.recent = self.recent[-30:]

    def snapshot(self):
        now = time.time()

        out = []
        for v in self.active.values():
            # 某个池不再产日志时（后端重启、线程崩了、课程跑完被回收），条目
            # 会永远留在 active 里 —— 表现为一张永不消失的「正在播放」幽灵卡片
            # （曾见 priority-pool-3 elapsed=50200s）。这里按最后一次见到该线程
            # 日志的时间过期：单课串行下每 step 秒必有上报，留 5 分钟足够，
            # 不会误杀真正在跑的线程。
            if now - v.get("last_seen", v.get("started", now)) > 300:
                continue
            dur = v["duration"] or 0
            elapsed = max(0.0, now - v["started"])
            pos = v.get("position")
            if pos is None:
                # 单课串行 + 真实节奏：播放位置就按 1 秒/秒 推进
                pos = int(elapsed)
            if dur:
                pct = min(100.0, pos * 100.0 / dur)
            else:
                pct = 0.0
            out.append({
                "thread": v["thread"], "course": v["course"], "job": v["job"],
                "duration": dur, "elapsed": int(elapsed),
                "position": pos,
                "round": v.get("round", 1),
                "percent": round(pct, 1),
            })
        out.sort(key=lambda x: x["thread"])
        return out


TRACKER = LiveTracker()

# 「最近完成的视频」在重启后就空了 —— tracker 只记得本进程喂过的行。
# 启动后补一次历史：把日志尾部的「视频已计分 / 未计分 / 被风控」捞出来。
_BACKFILL = {"done": False}
_BACKFILL_BYTES = 2 * 1024 * 1024


def _scan_done_records(paths):
    """从历史日志里回放完成记录。

    用**临时** tracker 扫，扫完只取 recent、把 active 丢掉 —— 历史里的
    「开始学习视频」如果留进主 tracker 的 active，就会变成一张永远跑不完的
    幽灵卡片（实测 pool-3 elapsed=2581s 一直挂在总览上）。

    两个文件都要扫：v1.0.16 之前 stdout 重定向到 live.log，之后改成 boot.log，
    所以旧记录在 live.log、新记录在 boot.log，只扫一个会缺一半。
    """
    tmp = LiveTracker()
    for p in paths:
        try:
            with open(p, "rb") as f:
                f.seek(0, 2)
                size = f.tell()
                f.seek(max(0, size - _BACKFILL_BYTES))
                data = f.read()
        except OSError:
            continue
        lines = data.decode("utf-8", errors="replace").splitlines()
        tmp.feed([ANSI_RE.sub("", ln) for ln in lines])
    return tmp.recent


def _dedup_recent(items, limit=30):
    """同一个视频可能被刷好几遍，按 (任务名, 结果) 去重，保留最后那条。"""
    seen = set()
    out = []
    for r in reversed(items):
        key = (r.get("job"), r.get("result"))
        if key in seen:
            continue
        seen.add(key)
        out.append(r)
    out.reverse()
    return out[-limit:]


# ------------------------------------------------------------------ OTA 自更新

try:
    import updater as _updater_mod

    def _kill_install_processes():
        """结束所有从当前安装目录启动的 java / python / mysqld（排除自身）。

        老版本重复启动会留下多个后端进程，stop_app() 只按 3030 端口杀一个，
        其余仍锁着 jar，Inno 的 RestartManager 关不掉就会中止安装。
        """
        root = os.path.abspath(ROOT).replace("'", "''")
        self_pid = os.getpid()
        script = (
            "$root = '%s'; $self = %d; "
            "Get-CimInstance Win32_Process | Where-Object { "
            "$_.ProcessId -ne $self -and $_.CommandLine -and "
            "($_.CommandLine -like ('*' + $root + '*')) -and "
            "($_.Name -eq 'java.exe' -or $_.Name -eq 'python.exe' -or "
            "$_.Name -eq 'pythonw.exe' -or $_.Name -eq 'mysqld.exe') "
            "} | ForEach-Object { Stop-Process -Id $_.ProcessId -Force "
            "-ErrorAction SilentlyContinue }"
        ) % (root, self_pid)
        try:
            subprocess.run(["powershell", "-NoProfile", "-Command", script],
                           capture_output=True, timeout=25,
                           creationflags=CREATE_NO_WINDOW)
        except Exception:
            pass

    def _stop_services_for_update():
        """完整安装 OTA 的前置动作：安装器替换不了被 java/mysqld 锁定的文件。"""
        SVC.stop_app()
        SVC.stop_mysql()
        _kill_install_processes()
        time.sleep(1.5)

    UPDATER = _updater_mod.Updater(lambda: SETTINGS, ROOT, _stop_services_for_update)
except Exception:                     # updater 挂了不能影响主程序
    UPDATER = None

try:
    if UPDATER is not None:
        _updater_mod.start_auto_check(UPDATER)
except Exception:
    pass


def update_public_state():
    """给 /api/state 用的更新状态（不含内部对象）。"""
    if UPDATER is None:
        return {"available": False, "reason": "updater 未加载"}
    with UPDATER._lock:
        st = dict(UPDATER.state)
        st["available"] = True
        st.pop("log", None)
        info = st.get("info")
        if info:                        # 前端只需要这些字段
            st["info"] = {k: info.get(k) for k in
                          ("tag", "latest", "has_update", "same_version",
                           "published", "notes", "client_asset", "setup_asset")}
        return st


# ------------------------------------------------------------------ API


def status_of(lstatus, qstatus, errmsg, lstart, backend_started, lend=None):
    """把 (日志状态, 队列状态, 错误信息) 归并成界面上的一个状态标签。"""
    # 日志说「进行中」，但队列已经是「已完成」——这是僵尸行。
    # 后端跑完一门课会先更新 th_wk_queue.status=4，再写 th_wk_log；
    # 如果进程在中途被强杀（点停止服务 / OTA 更新 / 崩溃），日志那次写就丢了，
    # 于是日志永远停在 1。界面只看日志，就会一直显示「进行中」，
    # 点进课程里却什么任务都没有（因为它其实已经刷完了）。
    # 队列状态是后端流程真正走完才写的，比日志可靠，这里以它为准。
    if lstatus == 1 and qstatus == 4:
        return "ok", "已完成"
    if lstatus == 1 and lend is not None and qstatus != 1:
        # 有结束时间但状态没改，同样是写库丢了一次
        return "ok", "已完成"
    if lstatus == 1:
        return "run", "进行中"
    if (qstatus == 2 and lstart is not None and backend_started is not None
            and lstart < backend_started):
        return "run", "排队中"
    if errmsg and "未登录" in errmsg:
        return "err", "未登录"
    if errmsg and "已达自动重试上限" in errmsg:
        return "err", "已达上限"
    if errmsg and "未刷完" in errmsg:
        return "err", "未刷完"
    if lstatus == 4:
        return "ok", "已完成"
    if lstatus == 5:
        return "err", "异常"
    if qstatus in STATUS_TEXT:
        return {4: "ok", 5: "err", 1: "run", 2: "run"}.get(qstatus, "idle"), STATUS_TEXT[qstatus]
    return "idle", "—"


def build_state():
    mysql_up = port_open(DB_PORT)
    app_up = port_open(HTTP_PORT)

    state = {
        "services": {
            "mysql": mysql_up,
            "backend": app_up,
            "root": ROOT,
            "log": SVC.log_path(),
        },
        "account": None,
        "stats": {"total": 0, "running": 0, "done": 0, "failed": 0, "pending": 0},
        "queue": [],
        "live": [],
        "logs": [],
        "settings": SETTINGS.data,
        "quizPending": 0,
        "keep_awake": {
            "enabled": bool(SETTINGS.data.get("keep_awake", True)),
            "active": bool(_KEEP_AWAKE["active"]),
            "supported": bool(_KEEP_AWAKE["supported"]),
            "last_err": _KEEP_AWAKE.get("last_err"),
        },
    }

    if mysql_up:
        try:
            rows, login = SVC.fetch_progress()
        except Exception as exc:
            rows, login = None, None
            state["db_error"] = str(exc)
        if login:
            state["account"] = {"account": login[0], "logged_in": bool(login[1])}
        elif rows is not None:
            state["account"] = {"account": None, "logged_in": False}

        try:
            state["quizPending"] = SVC.pending_quiz()
        except Exception:
            state["quizPending"] = 0

        if rows:
            backend_started = None
            if app_up:
                backend_started = proc_start_time(pid_on_port(HTTP_PORT))
            for (qid, course_id, course_name, qstatus, priority, retry,
                 creat_time, chap, prog, lstatus, cur_job, errmsg, remark,
                 lstart, lend, chap_id, failed_chapters, video_pos) in rows:
                tag, text = status_of(lstatus, qstatus, errmsg, lstart, backend_started, lend)
                try:
                    pct = float(prog) if prog is not None else 0.0
                except (TypeError, ValueError):
                    pct = 0.0
                failed_list = [x for x in str(failed_chapters or "").split(",") if x.strip()]
                state["queue"].append({
                    "id": str(qid),
                    "courseId": course_id,
                    "courseName": course_name,
                    "status": qstatus,
                    "statusText": text,
                    "tag": tag,
                    "priority": priority,
                    "retry": retry or 0,
                    "chapter": chap,
                    "chapterId": chap_id,
                    "failedChapterCount": len(failed_list),
                    "resumeVideoSeconds": video_pos,
                    "retryLimitReached": (qstatus == 5 and (retry or 0) >= 5),
                    "progress": round(max(0.0, min(100.0, pct)), 1),
                    "currentJob": cur_job,
                    "remark": remark,
                    "error": errmsg,
                    "creatTime": str(creat_time) if creat_time else None,
                    "startTime": str(lstart) if lstart else None,
                })
            st = state["stats"]
            st["total"] = len(state["queue"])
            for c in state["queue"]:
                if c["tag"] == "ok":
                    st["done"] += 1
                elif c["tag"] == "err":
                    st["failed"] += 1
                elif c["tag"] == "run":
                    st["running"] += 1
                else:
                    st["pending"] += 1

    # 日志 + 直播跟踪
    log_path = SVC.log_path()
    state["logs"] = tail_lines(log_path)            # 界面日志视图：尾部 300 行
    fresh = read_log_incremental(log_path)          # tracker：只喂新增的行
    if fresh:
        TRACKER.feed(fresh)

    # 首次调用补一次历史完成记录（只补一次，之后靠增量）
    if not _BACKFILL["done"]:
        _BACKFILL["done"] = True
        hist = _scan_done_records([SVC.livelog, SVC.bootlog])
        if hist:
            TRACKER.recent = _dedup_recent(hist + TRACKER.recent)

    state["live"] = TRACKER.snapshot()
    state["recentVideos"] = TRACKER.recent[-12:]

    # 后端停了就没有「正在刷的视频」；立即清空，避免幽灵卡片
    if not app_up:
        TRACKER.active.clear()
        state["live"] = []

    # OTA 自更新状态（当前版本 / 是否有新版本 / 更新进度）
    state["update"] = update_public_state()

    return state


# ------------------------------------------------------------------ HTTP


class Handler(BaseHTTPRequestHandler):
    server_version = "SuperAutoStudyClient/2.0"
    protocol_version = "HTTP/1.1"

    # ---- 基础设施
    def log_message(self, fmt, *args):
        pass    # 静音默认访问日志

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        elif isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _json(self, obj, code=200):
        self._send(code, obj)

    def _read_json(self):
        try:
            n = int(self.headers.get("Content-Length") or 0)
            if n <= 0:
                return {}
            return json.loads(self.rfile.read(n).decode("utf-8"))
        except Exception:
            return {}

    def _static(self, path):
        rel = path.lstrip("/") or "index.html"
        full = os.path.normpath(os.path.join(WEB_DIR, rel))
        if not full.startswith(os.path.normpath(WEB_DIR)):
            return self._send(403, "forbidden", "text/plain; charset=utf-8")
        if os.path.isdir(full):
            full = os.path.join(full, "index.html")
        if not os.path.exists(full):
            full = os.path.join(WEB_DIR, "index.html")
            if not os.path.exists(full):
                return self._send(404, "not found", "text/plain; charset=utf-8")
        ext = os.path.splitext(full)[1].lower()
        ctype = {
            ".html": "text/html; charset=utf-8",
            ".js": "application/javascript; charset=utf-8",
            ".css": "text/css; charset=utf-8",
            ".svg": "image/svg+xml",
            ".png": "image/png",
            ".ico": "image/x-icon",
            ".json": "application/json; charset=utf-8",
        }.get(ext, "application/octet-stream")
        with open(full, "rb") as f:
            self._send(200, f.read(), ctype)

    # ---- 路由
    def do_GET(self):
        u = urlparse(self.path)
        p, q = u.path, parse_qs(u.query)
        try:
            if p.startswith("/api/"):
                return self._api_get(p, q)
            return self._static(p)
        except Exception as exc:
            return self._json({"ok": False, "error": str(exc)}, 500)

    def do_POST(self):
        u = urlparse(self.path)
        p = u.path
        try:
            if p.startswith("/api/"):
                return self._api_post(p, self._read_json())
            return self._json({"ok": False, "error": "not found"}, 404)
        except Exception as exc:
            return self._json({"ok": False, "error": str(exc)}, 500)

    def do_DELETE(self):
        u = urlparse(self.path)
        try:
            return self._api_delete(u.path)
        except Exception as exc:
            return self._json({"ok": False, "error": str(exc)}, 500)

    def do_PATCH(self):
        u = urlparse(self.path)
        try:
            return self._api_patch(u.path, self._read_json())
        except Exception as exc:
            return self._json({"ok": False, "error": str(exc)}, 500)

    # ---- GET
    def _api_get(self, p, q):
        if p == "/api/state":
            return self._json(build_state())

        if p == "/api/points":
            course_id = (q.get("courseId") or [""])[0]
            refresh = (q.get("refresh") or [""])[0]
            acc = SVC.fetch_account()
            if not acc:
                return self._json({"ok": False, "error": "本地没有账号"})
            url = "http://127.0.0.1:{}/api/points?account={}&courseId={}".format(
                HTTP_PORT, quote(acc[0]), quote(course_id))
            if refresh in ("true", "1"):
                url += "&refresh=true"
            try:
                return self._json(http_json(url, timeout=180))
            except Exception as exc:
                return self._json({"ok": False, "error": str(exc)})

        if p == "/api/courses":
            acc = SVC.fetch_account()
            if not acc:
                return self._json({"ok": False, "error": "本地没有账号"})
            url = "http://127.0.0.1:{}/api/courses?account={}".format(
                HTTP_PORT, quote(acc[0]))
            try:
                return self._json(http_json(url, timeout=60))
            except Exception as exc:
                return self._json({"ok": False, "error": str(exc)})

        # 章节测验：待确认列表。后端整份返回（题目 + AI 建议），这里只做转发。
        if p == "/api/quiz":
            acc = SVC.fetch_account()
            if not acc:
                return self._json({"ok": False, "error": "本地没有账号"})
            limit = (q.get("limit") or ["30"])[0]
            url = "http://127.0.0.1:{}/api/quiz?account={}&limit={}".format(
                HTTP_PORT, quote(acc[0]), quote(limit))
            try:
                return self._json(http_json(url, timeout=60))
            except urllib.error.HTTPError as exc:
                # 客户端先更新、jar 还是旧的（没有 QuizController）时会走到这里
                return self._json({"ok": False,
                                   "error": "后端不支持章节测验（HTTP {}）。"
                                            "需要连后端一起更新到含做题功能的新版本。".format(exc.code)})
            except Exception as exc:
                return self._json({"ok": False, "error": "连不上后端：" + str(exc)})

        if p == "/api/ai-config":
            return self._json({"ok": True, "config": ai_config_public(load_ai_config())})

        if p == "/api/update/check":
            if UPDATER is None:
                return self._json({"ok": False, "error": "updater 未加载"})
            qs = parse_qs(urlparse(self.path).query)
            force = (qs.get("force") or [""])[0] in ("1", "true")
            try:
                info = UPDATER.check(force=force)
                return self._json({"ok": True, "info": info})
            except Exception as exc:
                return self._json({"ok": False, "error": str(exc)})

        return self._json({"ok": False, "error": "not found"}, 404)

    # ---- POST
    def _api_post(self, p, body):
        if p == "/api/service/start":
            started_at = time.time()
            msgs = []

            def elapsed_ms():
                return int((time.time() - started_at) * 1000)

            def failed(result, code, reason, next_step, phase="startup"):
                return self._json({
                    "ok": False,
                    "messages": msgs + [reason],
                    "result": result,
                    "errorCode": code,
                    "phase": phase,
                    "reason": reason,
                    "nextStep": next_step,
                    "logPath": SVC.log_path(),
                    "elapsedMs": elapsed_ms(),
                })

            chk = environment_self_check()
            if not chk.get("ok"):
                return failed("failed", chk["errorCode"], chk["reason"],
                              chk.get("nextStep", ""), "env")

            state, _ = probe_backend()
            if state == "ready":
                return self._json({"ok": True, "messages": ["后端已在运行"],
                                   "result": "already", "elapsedMs": elapsed_ms(),
                                   "logPath": SVC.log_path()})
            if state == "busy":
                return failed("port-busy", "STARTUP-PORT-001",
                              "3030 已被其他程序占用", "关闭占用 3030 的程序后重试", "web")

            code, msg = SVC.start_mysql()
            msgs.append(msg)
            if code == "ok":
                for _ in range(40):
                    if port_open(DB_PORT):
                        break
                    time.sleep(0.5)
            if not port_open(DB_PORT):
                return failed("failed", "STARTUP-DB-001", "本地数据库未就绪",
                              "先启动 MySQL（确认 127.0.0.1:3306 正在监听）", "datasource")

            try:
                added = SVC.ensure_schema()
                if added:
                    msgs.append("升级数据表: {}".format("/".join(added)))
            except Exception as exc:
                msgs.append("升级数据表失败: {}".format(exc))
            try:
                n_reset, n_retry, n_done = SVC.reset_stuck_tasks(None)
                parts = []
                if n_reset:
                    parts.append("重置 {} 个中断任务".format(n_reset))
                if n_retry:
                    parts.append("重试 {} 个失败任务".format(n_retry))
                if n_done:
                    parts.append("同步 {} 个已完成".format(n_done))
                if parts:
                    msgs.append("，".join(parts))
            except Exception as exc:
                msgs.append("续刷失败: {}".format(exc))

            code, msg = SVC.start_app()
            msgs.append(msg)
            if code == "port-busy":
                return failed("port-busy", "STARTUP-PORT-001",
                              "3030 已被其他程序占用", "关闭占用 3030 的程序后重试", "web")
            if code not in ("ok", "already"):
                low = msg.lower()
                error_code = "STARTUP-JAR-001" if "jar" in low else (
                    "STARTUP-JDK-001" if "jdk" in low else "STARTUP-INTERNAL-001")
                return failed("failed", error_code, msg, "查看日志后重试", "env")

            # 30 秒三段式判定：就绪 / 进程退出 / 超时
            result = "timeout"
            deadline = time.time() + READY_TIMEOUT
            while time.time() < deadline:
                st, _ = probe_backend(timeout=1.0)
                if st == "ready":
                    result = "ready"
                    break
                if st == "busy":
                    result = "port-busy"
                    break
                proc = SVC.app_proc
                if proc is not None and proc.poll() is not None:
                    result = "failed"
                    break
                time.sleep(POLL_INTERVAL)

            if result == "port-busy":
                return failed("port-busy", "STARTUP-PORT-001",
                              "3030 已被其他程序占用", "关闭占用 3030 的程序后重试", "web")
            if result == "ready":
                try:
                    SVC.rescan_queue()
                except Exception as exc:
                    msgs.append("入队失败: {}".format(exc))
                msgs.append("服务已就绪")
                return self._json({"ok": True, "messages": msgs, "result": "ready",
                                   "elapsedMs": elapsed_ms(), "logPath": SVC.log_path()})
            if result == "failed":
                fields = parse_startup_failure(SVC.livelog) or \
                    parse_startup_failure(SVC.bootlog)
                if fields:
                    return failed("failed", fields.get("code", "STARTUP-INTERNAL-001"),
                                  fields.get("reason", "后端启动失败"),
                                  fields.get("next", "查看日志后重试"),
                                  fields.get("phase", "fail"))
                return failed("failed", "STARTUP-INTERNAL-001", "后端进程已退出",
                              "查看日志末尾的异常后重试", "fail")

            return failed("timeout", "STARTUP-TIMEOUT-001",
                          "后端进程存活但 30 秒内未就绪",
                          "查看日志确认是否卡在数据库或端口；进程与日志已保留", "ready")

        if p == "/api/service/stop":
            msgs = []
            _, m = SVC.stop_app()
            msgs.append(m)
            _, m = SVC.stop_mysql()
            msgs.append(m)
            return self._json({"ok": True, "messages": msgs})

        if p == "/api/queue/rescan":
            if not port_open(HTTP_PORT):
                return self._json({"ok": False, "error": "后端还没起来"})
            SVC.rescan_queue()
            return self._json({"ok": True, "messages": ["已触发队列扫描"]})

        if p == "/api/queue/reset":
            if not port_open(DB_PORT):
                return self._json({"ok": False, "error": "MySQL 还没起来"})
            running = port_open(HTTP_PORT)
            started = proc_start_time(pid_on_port(HTTP_PORT)) if running else None
            n_reset, n_retry, n_done = SVC.reset_stuck_tasks(started)
            parts = []
            if n_reset:
                parts.append("重置 {} 个中断任务".format(n_reset))
            if n_retry:
                parts.append("重试 {} 个失败任务".format(n_retry))
            if n_done:
                parts.append("同步 {} 个已完成".format(n_done))
            if not parts:
                parts.append("没有需要续刷的任务")
            if running and (n_reset or n_retry):
                try:
                    SVC.rescan_queue()
                    parts.append("已重新入队")
                except Exception:
                    pass
            print("[操作] 续刷 / 重试 -> {}".format("，".join(parts)))
            return self._json({"ok": True, "messages": parts})

        if p == "/api/login":
            acc = (body.get("account") or "").strip()
            pwd = body.get("password") or ""
            if not acc or not pwd:
                return self._json({"ok": False, "error": "账号和密码都要填"})
            if not port_open(HTTP_PORT):
                return self._json({"ok": False, "error": "后端还没起来，先点「启动服务」"})
            r = http_json("http://127.0.0.1:{}/chaoxing/course".format(HTTP_PORT),
                          {"account": acc, "password": pwd}, timeout=90)
            if r.get("code") == 200:
                courses = r.get("data") or []
                return self._json({"ok": True, "courses": courses})
            return self._json({"ok": False,
                               "error": r.get("msg") or "登录失败"})

        if p == "/api/enroll":
            if not port_open(HTTP_PORT):
                return self._json({"ok": False, "error": "后端还没起来"})
            cid = str(body.get("courseId") or "").strip()
            cname = (body.get("courseName") or "").strip()
            if not cid:
                return self._json({"ok": False, "error": "缺少 courseId"})
            r = SVC.enroll(cid, cname)
            if r.get("code") == 200:
                try:
                    SVC.rescan_queue()
                except Exception:
                    pass
                return self._json({"ok": True})
            return self._json({"ok": False, "error": r.get("msg") or "加课失败"})

        if p == "/api/settings":
            saved = SETTINGS.save(body)
            print("[操作] 保存设置 -> {}".format(saved))
            return self._json({"ok": True, "settings": saved})

        # 提交 / 跳过一份测验。一次提交就定分，所以这里不做任何自动重试。
        m = re.match(r"^/api/quiz/(\d+)/(submit|skip)$", p)
        if m:
            qid, action = m.group(1), m.group(2)
            url = "http://127.0.0.1:{}/api/quiz/{}/{}".format(HTTP_PORT, qid, action)
            print("[操作] {} 测验 #{}".format("提交" if action == "submit" else "跳过", qid))
            try:
                return self._json(http_json(url, body if body else {}, timeout=180))
            except Exception as exc:
                return self._json({"ok": False, "error": str(exc)})

        if p == "/api/ai-config":
            saved = save_ai_config(body)
            print("[操作] 保存 AI 配置 -> {} @ {}".format(saved["model"], saved["base_url"]))
            return self._json({"ok": True, "config": ai_config_public(saved)})

        # 「测一下能不能用」：用界面上的值临时拼一份配置去试，不用先保存。
        if p == "/api/ai-config/test":
            cfg = load_ai_config()
            if isinstance(body, dict):
                for k in ("base_url", "model"):
                    v = body.get(k)
                    if isinstance(v, str) and v.strip():
                        cfg[k] = v.strip()
                key = body.get("api_key")
                if isinstance(key, str) and key.strip() and "****" not in key:
                    cfg["api_key"] = key.strip()
            print("[操作] 测试 AI 配置 -> {} @ {}".format(cfg["model"], ai_endpoint(cfg)))
            return self._json(test_ai_config(cfg))

        if p == "/api/update/apply":
            if UPDATER is None:
                return self._json({"ok": False, "error": "updater 未加载"})
            mode = (body.get("mode") or "").strip()
            if mode not in ("client", "full"):
                return self._json({"ok": False, "error": "mode 必须是 client 或 full"})
            asset_url = (body.get("asset_url") or "").strip()
            asset_sha256 = (body.get("asset_sha256") or "").strip()
            if (asset_url and not asset_url.lower().startswith(("http://", "https://"))):
                return self._json({"ok": False, "error": "asset_url 只支持 http(s)"})
            if asset_url and not asset_sha256:
                return self._json({"ok": False, "error": "自定义 asset_url 必须同时提供 asset_sha256"})
            # 更新过程可能长达数分钟且结尾会重启进程，放后台线程，请求立即返回
            def run():
                try:
                    UPDATER.apply(mode, asset_url, asset_sha256)
                except Exception:
                    pass          # 错误已写进 UPDATER.state，前端轮询能看到
            threading.Thread(target=run, daemon=True, name="ota-apply").start()
            return self._json({"ok": True, "started": True})

        return self._json({"ok": False, "error": "not found"}, 404)

    # ---- DELETE / PATCH
    def _api_delete(self, p):
        m = re.match(r"^/api/course/(.+)$", p)
        if m:
            n = SVC.remove_course(m.group(1))
            return self._json({"ok": True, "removed": n})
        return self._json({"ok": False, "error": "not found"}, 404)

    def _api_patch(self, p, body):
        m = re.match(r"^/api/course/(.+)$", p)
        if m:
            qid = m.group(1)
            try:
                if "priority" in body:
                    SVC.set_priority(qid, body["priority"])
                if "status" in body:
                    SVC.set_status(qid, body["status"])
            except ValueError as exc:
                return self._json({"ok": False, "error": str(exc)})
            return self._json({"ok": True})
        return self._json({"ok": False, "error": "not found"}, 404)


def main():
    port = CLIENT_PORT
    if "--port" in sys.argv:
        try:
            port = int(sys.argv[sys.argv.index("--port") + 1])
        except Exception:
            pass
    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    # 防休眠守护线程：读 SETTINGS.data 的实时值，界面改开关立即生效
    # （模块导入时通常已启动过，这里是幂等兜底）
    start_keep_awake_thread()
    print("[SuperAutoStudy] client service on http://127.0.0.1:{}".format(port))
    print("[SuperAutoStudy] root = {}".format(ROOT))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()


if __name__ == "__main__":
    main()
