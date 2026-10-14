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

DEFAULT_ROOT = ""  # 留空：部署根目录交给 find_root() 自动探测；打包分发时绝不能写死个人路径

CREATE_NO_WINDOW = 0x08000000
CREATE_NEW_PROCESS_GROUP = 0x00000200
FLAGS_HIDDEN = CREATE_NO_WINDOW | CREATE_NEW_PROCESS_GROUP

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
WEB_DIR = os.path.join(BASE_DIR, "web")
SETTINGS_PATH = os.path.join(BASE_DIR, "settings.json")

# 与 com.tihai.enums.WkTaskStatusEnum 一一对应
STATUS_TEXT = {0: "待处理", 1: "进行中", 2: "队列中", 3: "考试中",
               4: "已完成", 5: "异常", 6: "暂停"}

DEFAULT_SETTINGS = {
    "video_report_pace_ms": 60000,
    "core_size": 3,
    "video_max_rounds": 2,
    "skip_job_types": ["workid"],
    "video_only_course_ids": [],
}

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
            if isinstance(saved, dict):
                self.data.update(saved)
        except Exception:
            pass
        return self.data

    def save(self, patch):
        for k in DEFAULT_SETTINGS:
            if k in patch:
                self.data[k] = patch[k]
        # 归一化
        try:
            self.data["video_report_pace_ms"] = max(3000, int(self.data["video_report_pace_ms"]))
            self.data["core_size"] = max(1, min(10, int(self.data["core_size"])))
            self.data["video_max_rounds"] = max(1, min(5, int(self.data["video_max_rounds"])))
            self.data["skip_job_types"] = [str(x) for x in self.data["skip_job_types"] if str(x).strip()]
            self.data["video_only_course_ids"] = [str(x) for x in self.data["video_only_course_ids"] if str(x).strip()]
        except Exception:
            pass
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.data, f, ensure_ascii=False, indent=2)
        os.replace(tmp, self.path)
        return self.data

    def backend_args(self):
        """把设置翻译成 Spring Boot 命令行覆盖参数（jar 内的 yml 不改）。

        列表用「逗号分隔的单值」而不是 `key[0]=a&key[1]=b`：
        下标写法只能覆盖前 N 项，yml 里多出来的项会残留 —— 用户在界面上
        **清空**列表时，yml 的值又冒出来，看起来像没生效。逗号写法是整值替换，
        空列表就传空串（Spring 会把 "" 绑成空集合），语义干净。
        """
        d = self.data
        return [
            "--study.chaoxing.video-report-pace-ms={}".format(d["video_report_pace_ms"]),
            "--custom.thread-pool.core-size={}".format(d["core_size"]),
            "--study.chaoxing.video-max-rounds={}".format(d["video_max_rounds"]),
            "--study.chaoxing.skip-job-types={}".format(",".join(d["skip_job_types"])),
            "--study.chaoxing.video-only-course-ids={}".format(",".join(d["video_only_course_ids"])),
        ]


SETTINGS = Settings(SETTINGS_PATH)


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

    def log_path(self):
        """后端日志：取 app.log / live.log 里更新的那个。"""
        cands = []
        for p in (self.livelog, self.applog):
            try:
                cands.append((os.path.getmtime(p), p))
            except OSError:
                pass
        if not cands:
            return self.livelog
        return max(cands)[1]

    def jar(self):
        d = os.path.join(self.appdir, "target")
        if not os.path.isdir(d):
            return None
        jars = sorted(f for f in os.listdir(d)
                      if f.startswith("SuperAuto-") and f.endswith(".jar"))
        return os.path.join(d, jars[-1]) if jars else None

    # ---- 启动
    def start_mysql(self):
        if port_open(DB_PORT):
            return "already", "MySQL 已在运行"
        if not os.path.exists(self.mysqld):
            return "err", "找不到 {}".format(self.mysqld)
        logpath = os.path.join(self.root, "mysql57", "console.log")
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
        if port_open(HTTP_PORT):
            return "already", "后端已在运行"
        jar = self.jar()
        if not jar:
            return "err", "jar 还没编译"
        if not os.path.exists(self.java):
            return "err", "找不到 {}".format(self.java)
        args = [self.java, "-Dfile.encoding=UTF-8", "-jar", jar,
                "--server.port={}".format(HTTP_PORT)]
        args.extend(SETTINGS.backend_args())
        try:
            self._app_fh = open(self.livelog, "ab", buffering=0)
            self.app_proc = subprocess.Popen(
                args, cwd=self.appdir, stdin=subprocess.DEVNULL,
                stdout=self._app_fh, stderr=self._app_fh,
                creationflags=FLAGS_HIDDEN)
        except Exception as exc:
            return "err", "启动后端失败: {}".format(exc)
        return "ok", "后端启动中（约 7 秒）"

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
       l.current_job, l.error_message, l.remark, l.start_time, l.end_time
FROM th_wk_queue q
LEFT JOIN (
    SELECT t.login_account, t.course_name, t.current_chapter_index,
           t.current_progress, t.status, t.current_job,
           t.error_message, t.remark, t.start_time, t.end_time
    FROM th_wk_log t
    JOIN (SELECT login_account, course_name, MAX(id) AS mid
          FROM th_wk_log GROUP BY login_account, course_name) m
      ON t.login_account = m.login_account
     AND t.course_name  = m.course_name
     AND t.id           = m.mid
) l ON l.course_name = q.course_name
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
            return n
        finally:
            conn.close()

    def set_priority(self, queue_id, priority):
        conn = self.connect()
        try:
            with conn.cursor() as cur:
                n = cur.execute("UPDATE th_wk_queue SET priority = %s WHERE id = %s",
                                (int(priority), queue_id))
            conn.commit()
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
                    "      SELECT t.course_name, t.status AS lstatus, t.start_time, t.end_time "
                    "        FROM th_wk_log t JOIN (SELECT course_name, MAX(id) AS mid "
                    "                FROM th_wk_log GROUP BY course_name) m ON t.id = m.mid"
                    "  ) l ON l.course_name = q.course_name WHERE q.status IN (2, 5)")
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

                def upd(status, ids):
                    if not ids:
                        return 0
                    return cur.executemany(
                        "UPDATE th_wk_queue SET status = %s WHERE id = %s",
                        [(status, i) for i in ids])

                n_done = upd(4, finished)
                n_retry = upd(0, failed)
                n_reset = upd(0, stuck)
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
        r"(?: 上报步长:\d+s 墙钟间隔:\d+ms)?(?: 起始:(\d+))?")
    DONE_RE = re.compile(r"(priority-pool-\d+)(视频已计分|视频未计分|视频被超星风控拦截): (.*?)[（(]")
    POS_RE = re.compile(
        r"(priority-pool-\d+)播放位置: (.*?) (\d+)s/(\d+)s (?:第)?(\d+)遍 第(\d+)报")

    def __init__(self):
        self.active = {}     # thread -> dict
        self.recent = []     # 最近完成的视频
        self._seen = set()   # 已处理过的行（避免重复计数）

    def feed(self, lines):
        for ln in lines:
            if ln in self._seen:
                continue
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
                self._seen.add(ln)
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
                self._seen.add(ln)
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
                self._seen.add(ln)
        # _seen 会无限增长，定期裁剪
        if len(self._seen) > 5000:
            self._seen = set(list(self._seen)[-1500:])

    def snapshot(self):
        now = time.time()
        # 上报节流是全局的：任意两次上报间隔 >= paceMs，N 个线程排队 → 每条
        # 视频的真实播放位置只以 1/N 的速度前进。拿不到精确位置时按此折算。
        try:
            n_threads = max(1, int(SETTINGS.data.get("core_size", 1) or 1))
        except (TypeError, ValueError):
            n_threads = 1
        out = []
        for v in self.active.values():
            # 某个池不再产日志时（后端重启、线程崩了、课程跑完被回收），条目
            # 会永远留在 active 里 —— 表现为一张永不消失的「正在播放」幽灵卡片
            # （曾见 priority-pool-3 elapsed=50200s）。这里按最后一次见到该线程
            # 日志的时间过期：正常每 core_size×paceMs 秒必有上报，留 5 分钟足够，
            # 远大于最慢的 10×60s，不会误杀真正在跑的池。
            if now - v.get("last_seen", v.get("started", now)) > 300:
                continue
            dur = v["duration"] or 0
            elapsed = max(0.0, now - v["started"])
            pos = v.get("position")
            if pos is None:
                pos = int(elapsed / n_threads)
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


# ------------------------------------------------------------------ API


def status_of(lstatus, qstatus, errmsg, lstart, backend_started):
    """把 (日志状态, 队列状态, 错误信息) 归并成界面上的一个状态标签。"""
    if lstatus == 1:
        return "run", "进行中"
    if (qstatus == 2 and lstart is not None and backend_started is not None
            and lstart < backend_started):
        return "run", "排队中"
    if errmsg and "未登录" in errmsg:
        return "err", "未登录"
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

        if rows:
            backend_started = None
            if app_up:
                backend_started = proc_start_time(pid_on_port(HTTP_PORT))
            for (qid, course_id, course_name, qstatus, priority, retry,
                 creat_time, chap, prog, lstatus, cur_job, errmsg, remark,
                 lstart, lend) in rows:
                tag, text = status_of(lstatus, qstatus, errmsg, lstart, backend_started)
                try:
                    pct = float(prog) if prog is not None else 0.0
                except (TypeError, ValueError):
                    pct = 0.0
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
    lines = tail_lines(log_path)
    state["logs"] = lines
    if lines:
        TRACKER.feed(lines)
    state["live"] = TRACKER.snapshot()
    state["recentVideos"] = TRACKER.recent[-12:]

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

        return self._json({"ok": False, "error": "not found"}, 404)

    # ---- POST
    def _api_post(self, p, body):
        if p == "/api/service/start":
            msgs = []
            backend_was_running = port_open(HTTP_PORT)
            code, msg = SVC.start_mysql()
            msgs.append(msg)
            if code == "ok":
                for _ in range(40):
                    if port_open(DB_PORT):
                        break
                    time.sleep(0.5)
            if not backend_was_running and port_open(DB_PORT):
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
            if code in ("ok", "already"):
                for _ in range(40):
                    if port_open(HTTP_PORT):
                        break
                    time.sleep(0.5)
            if port_open(HTTP_PORT):
                try:
                    SVC.rescan_queue()
                except Exception as exc:
                    msgs.append("入队失败: {}".format(exc))
            return self._json({"ok": True, "messages": msgs})

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
            return self._json({"ok": True, "settings": SETTINGS.save(body)})

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
            if "priority" in body:
                SVC.set_priority(qid, body["priority"])
            if "status" in body:
                SVC.set_status(qid, body["status"])
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
