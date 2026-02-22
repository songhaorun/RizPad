#!/usr/bin/env python3
"""
main.py — RizPC 主程序入口

用法:
    python main.py <iPad IP 地址>
    python main.py 192.168.1.100
    python main.py 127.0.0.1          # 配合 iproxy 走 USB
    python main.py 127.0.0.1 --debug  # 强制使用调试模式（只打印不注入）

功能:
    1. 连接到 iPad 上运行的 RizPad App (TCP 端口 24864)
    2. 实时接收触摸 → 键位映射数据
    3. 调用 KeyInjector 注入系统键盘事件
       - Windows: 自动使用 WindowsInjector（SendInput + 扫描码）
       - 其他平台 / --debug: 使用 DebugInjector（仅打印）
"""

import sys
import time
import platform
from tcp_client import RizPadClient
from key_injector import DebugInjector

DEFAULT_PORT = 24864


class FPSCounter:
    """
    实时帧率 + 延迟诊断计数器，每秒刷新一次。
    通信 = iPad发来的包总数 | 变化 = 有状态变化的帧 | 注入 = SendInput 调用次数
    """

    def __init__(self):
        self._recv_count = 0       # 总收包数
        self._change_count = 0     # 状态变化帧数
        self._inject_count = 0     # 注入事件数 (press+release)
        self._last_print = time.perf_counter()
        self._last_recv = time.perf_counter()
        self._intervals = []
        self._held_keys = []

    def tick_recv(self, has_change: bool, inject_events: int, held_keys: list):
        """收到一帧时调用。held_keys=当前按住的键ASCII列表."""
        now = time.perf_counter()
        self._intervals.append(now - self._last_recv)
        self._last_recv = now
        self._recv_count += 1
        if has_change:
            self._change_count += 1
        self._inject_count += inject_events
        self._held_keys = held_keys
        self._maybe_print()

    def _maybe_print(self):
        now = time.perf_counter()
        elapsed = now - self._last_print
        if elapsed >= 1.0:
            recv_fps = self._recv_count / elapsed
            change_fps = self._change_count / elapsed
            inject_fps = self._inject_count / elapsed
            dup_pct = (1 - self._change_count / max(self._recv_count, 1)) * 100
            if self._intervals:
                avg_ms = (sum(self._intervals) / len(self._intervals)) * 1000
                max_ms = max(self._intervals) * 1000
            else:
                avg_ms = max_ms = 0
            held_str = " ".join(chr(k) for k in self._held_keys) if self._held_keys else "-"
            print(
                f"\r通信 {recv_fps:5.0f}Hz | "
                f"注入 {inject_fps:4.0f}/s | "
                f"间隔 {avg_ms:4.1f}/{max_ms:4.1f}ms | "
                f"按住 [{held_str}]          ",
                end="", flush=True
            )
            self._recv_count = 0
            self._change_count = 0
            self._inject_count = 0
            self._intervals.clear()
            self._last_print = now


def is_admin() -> bool:
    """检查当前进程是否拥有管理员权限 (仅 Windows)."""
    if sys.platform != "win32":
        return True
    try:
        import ctypes
        return ctypes.windll.shell32.IsUserAnAdmin() != 0
    except Exception:
        return False


def create_injector(debug_mode: bool):
    """根据平台和参数选择合适的注入器."""
    if debug_mode:
        print("[Main] 调试模式：仅打印按键事件")
        return DebugInjector()

    if sys.platform == "win32":
        if not is_admin():
            print("[Main] ⚠ 警告: 未以管理员权限运行！")
            print("[Main]   全屏游戏可能无法接收注入的按键。")
            print("[Main]   请右键以\"管理员身份运行\"此程序。\n")

        from key_injector import WindowsInjector
        print("[Main] Windows 游戏模式：使用 SendInput + 扫描码注入")
        return WindowsInjector()
    else:
        print("[Main] 非 Windows 平台，使用调试模式（仅打印按键事件）")
        return DebugInjector()


def main():
    # 解析参数
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = [a for a in sys.argv[1:] if a.startswith("--")]
    debug_mode = "--debug" in flags

    if len(args) < 1:
        print("用法: python main.py <iPad IP> [端口] [--debug]")
        print("示例: python main.py 192.168.1.100")
        print("      python main.py 127.0.0.1        (配合 iproxy 走 USB)")
        print("      python main.py 127.0.0.1 --debug (强制调试模式)")
        sys.exit(1)

    host = args[0]
    port = int(args[1]) if len(args) > 1 else DEFAULT_PORT

    injector = create_injector(debug_mode)
    client = RizPadClient(host, port)
    fps = FPSCounter()

    # 收到键位状态时，更新注入器
    def on_key_state(msg):
        prev = set(injector._prev_keys)
        curr = set(msg.pressed_keys)
        events = len(curr - prev) + len(prev - curr)
        injector.update(msg.pressed_keys)
        fps.tick_recv(has_change=(events > 0), inject_events=events, held_keys=msg.pressed_keys)

    def on_connected():
        print("[Main] 连接成功，等待触摸数据...")

    def on_disconnected():
        injector.release_all()
        print("[Main] 已断开，所有键已松开")

    client.on_key_state = on_key_state
    client.on_connected = on_connected
    client.on_disconnected = on_disconnected

    # Windows: 提升进程优先级以减少调度延迟
    if sys.platform == "win32":
        try:
            import ctypes
            kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)
            HIGH_PRIORITY_CLASS = 0x0080
            handle = kernel32.GetCurrentProcess()
            if kernel32.SetPriorityClass(handle, HIGH_PRIORITY_CLASS):
                print("[Main] 已设置进程高优先级")
        except Exception as e:
            print(f"[Main] 设置进程优先级失败: {e}")

    print(f"[Main] RizPC — 连接到 {host}:{port}")
    print(f"[Main] 系统: {platform.system()} {platform.release()}")
    print("[Main] 按 Ctrl+C 退出\n")

    try:
        client.run_forever()
    except KeyboardInterrupt:
        print("\n[Main] 正在退出...")
        injector.release_all()
        client.stop()


if __name__ == "__main__":
    main()
