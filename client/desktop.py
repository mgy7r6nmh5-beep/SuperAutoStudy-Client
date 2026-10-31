# -*- coding: utf-8 -*-
"""SuperAutoStudy 客户端 —— 桌面窗口

启动本地服务（server.py），然后用系统自带的 Edge WebView2 打开一个
原生窗口（无地址栏、无浏览器外壳），看起来就是一个正经桌面程序。

用法：
    pythonw desktop.py      （无控制台，正常使用）
    python  desktop.py      （带控制台，排查问题用）

启动脚本用的是 pythonw，出错了什么都看不见 —— 所以这里把致命错误
弹成 Windows 消息框，而不是让它静默退出。
"""

import ctypes
import os
import socket
import sys
import threading
import time
import urllib.request
from http.server import ThreadingHTTPServer

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)

# 模块级：本进程正在使用的 HTTP 服务实例。
# updater 在 OTA 重启前会主动 shutdown 它，让 3080 立刻释放 —— 这是
# 「更新后新实例起不来」那个竞态的治本手段（详见 updater.restart_client）。
HTTPD = None


def fatal(title, message):
    """弹一个系统消息框（不依赖 tkinter）。"""
    try:
        ctypes.windll.user32.MessageBoxW(None, str(message), str(title), 0x10)
    except Exception:
        pass
    try:
        if sys.stderr is not None:
            sys.stderr.write("{}: {}\n".format(title, message))
    except Exception:
        pass


def fallback_to_browser(port, reason):
    """窗口后端起不来时的退路：改用系统浏览器，本地服务继续跑。

    触发条件就是那句「窗口启动失败：failed to load ...Python.Runtime.dll」——
    pywebview 要靠 pythonnet 加载 .NET，机器上 .NET 版本不够、或者安装路径
    带中文导致路径传给 .NET 时乱码，都会走到这里。
    这时候数据库和后端其实已经正常启动了，没必要让用户白等一场。
    """
    import webbrowser

    url = "http://127.0.0.1:{}".format(port)
    opened = False
    try:
        opened = bool(webbrowser.open(url))
    except Exception:
        opened = False

    msg = ("{}\n\n"
           "本地服务已经启动，界面可以直接用浏览器打开：\n"
           "    {}\n").format(reason, url)
    if not opened:
        msg += "\n浏览器没能自动打开，请手动把上面的地址复制到浏览器。\n"
    msg += ("\n点「确定」后程序会继续在后台运行。\n"
            "要彻底退出，请在任务管理器里结束 pythonw.exe。")
    fatal("SuperAutoStudy 窗口打不开（已改用浏览器）", msg)

    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass



def wait_port(port, timeout=12.0):
    t0 = time.time()
    while time.time() - t0 < timeout:
        with socket.socket() as s:
            s.settimeout(0.3)
            try:
                if s.connect_ex(("127.0.0.1", port)) == 0:
                    return True
            except OSError:
                pass
        time.sleep(0.15)
    return False


def http_ok(port, timeout=1.5):
    """端口上是否有一个「能正常应答」的本地服务。

    只探测 TCP 端口是不够的：OTA 更新重启时，旧实例的端口在退出前一直可连，
    但它已经不再服务了。真正请求一次接口才能区分「活着的实例」和「将死的僵尸」。
    """
    try:
        with urllib.request.urlopen(
                "http://127.0.0.1:{}/api/state".format(port), timeout=timeout) as r:
            return 200 <= r.status < 300
    except Exception:
        return False


def main():
    global HTTPD

    try:
        import server as svc
    except Exception as exc:  # 依赖缺失 / 语法错误
        fatal("SuperAutoStudy 启动失败",
              "加载本地服务模块失败：\n{}\n\n"
              "请确认压缩包已完整解压（python 与 client 目录都在）。".format(exc))
        return 1

    port = svc.CLIENT_PORT
    httpd = None

    reuse = False
    if wait_port(port, timeout=0.4):
        # 端口上有服务。但 OTA 更新重启时，它可能是「正在退出」的旧实例
        # （updater 先拉起新客户端，自己稍后才 os._exit）。所以不能看一眼就复用：
        # 连续做 HTTP 健康检查，只有自始至终都能正常应答，才认定真有实例在跑；
        # 中途失去响应就说明是僵尸，由本进程接管服务，否则 3080 会彻底没人监听。
        for _ in range(4):                     # 约 6 秒
            time.sleep(1.5)
            if not http_ok(port, 1.5):
                break
        else:
            reuse = True

    if not reuse:
        try:
            httpd = ThreadingHTTPServer(("127.0.0.1", port), svc.Handler)
        except OSError as exc:
            fatal("SuperAutoStudy 启动失败",
                  "本地端口 {} 被占用，且不像是本程序的服务：\n{}".format(port, exc))
            return 1
        HTTPD = httpd
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        if not wait_port(port):
            fatal("SuperAutoStudy 启动失败", "本地服务没能起来（端口 {}）。".format(port))
            return 1

    try:
        import webview
    except Exception as exc:
        fallback_to_browser(port, "缺少 WebView2 运行环境，无法打开窗口：\n{}".format(exc))
        return 0

    webview.create_window(
        "SuperAutoStudy",
        "http://127.0.0.1:{}".format(port),
        width=1380, height=900,
        min_size=(1024, 680),
        background_color="#0E1116",
        text_select=True,
    )
    try:
        webview.start()
    except Exception as exc:
        fallback_to_browser(port, "窗口启动失败：\n{}".format(exc))
        return 0
    finally:
        if httpd is not None:
            httpd.shutdown()
    return 0


if __name__ == "__main__":
    sys.exit(main())
