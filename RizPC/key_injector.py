"""
key_injector.py — 键盘注入接口（抽象层 + 平台实现）

- DebugInjector:   仅在终端打印按键事件（调试 / macOS）
- WindowsInjector: 使用 SendInput + 扫描码注入键盘（Windows 游戏模式）
"""

import sys
from typing import List
from protocol import diff_key_state


class KeyInjector:
    """
    键盘事件注入的基类。

    子类需实现:
      - press_key(ascii_code)
      - release_key(ascii_code)
    """

    def __init__(self):
        self._prev_keys: List[int] = []

    def update(self, current_keys: List[int]):
        """
        传入当前帧的按键列表 (ASCII 码)，
        自动计算 diff 并调用 press_key / release_key。
        """
        newly_pressed, newly_released = diff_key_state(self._prev_keys, current_keys)

        for key in newly_released:
            self.release_key(key)
        for key in newly_pressed:
            self.press_key(key)

        self._prev_keys = list(current_keys)

    def release_all(self):
        """松开所有当前按住的键."""
        for key in self._prev_keys:
            self.release_key(key)
        self._prev_keys = []

    def press_key(self, ascii_code: int):
        raise NotImplementedError

    def release_key(self, ascii_code: int):
        raise NotImplementedError


class DebugInjector(KeyInjector):
    """调试用：只在终端打印按键事件，不实际注入系统."""

    def press_key(self, ascii_code: int):
        print(f"  ↓ 按下 '{chr(ascii_code)}'")

    def release_key(self, ascii_code: int):
        print(f"  ↑ 松开 '{chr(ascii_code)}'")


# ──────────────────────────────────────────────────────────────
# Windows 实现 — SendInput + 硬件扫描码（适用于全屏游戏）
# ──────────────────────────────────────────────────────────────

if sys.platform == "win32":
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.WinDLL('user32', use_last_error=True)

    INPUT_KEYBOARD = 1
    KEYEVENTF_SCANCODE = 0x0008
    KEYEVENTF_KEYUP = 0x0002

    # ASCII → DirectInput 扫描码映射（全键盘字母 + 分号）
    ASCII_TO_SCANCODE = {
        # 第一行 QWERTYUIOP
        ord('q'): 0x10, ord('w'): 0x11, ord('e'): 0x12, ord('r'): 0x13,
        ord('t'): 0x14, ord('y'): 0x15, ord('u'): 0x16, ord('i'): 0x17,
        ord('o'): 0x18, ord('p'): 0x19,
        # 第二行 ASDFGHJKL;
        ord('a'): 0x1E, ord('s'): 0x1F, ord('d'): 0x20, ord('f'): 0x21,
        ord('g'): 0x22, ord('h'): 0x23, ord('j'): 0x24, ord('k'): 0x25,
        ord('l'): 0x26, ord(';'): 0x27,
        # 第三行 ZXCVBNM
        ord('z'): 0x2C, ord('x'): 0x2D, ord('c'): 0x2E, ord('v'): 0x2F,
        ord('b'): 0x30, ord('n'): 0x31, ord('m'): 0x32,
    }

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [
            ("wVk", wintypes.WORD),
            ("wScan", wintypes.WORD),
            ("dwFlags", wintypes.DWORD),
            ("time", wintypes.DWORD),
            ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong)),
        ]

    class MOUSEINPUT(ctypes.Structure):
        _fields_ = [
            ("dx", wintypes.LONG),
            ("dy", wintypes.LONG),
            ("mouseData", wintypes.DWORD),
            ("dwFlags", wintypes.DWORD),
            ("time", wintypes.DWORD),
            ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong)),
        ]

    class HARDWAREINPUT(ctypes.Structure):
        _fields_ = [
            ("uMsg", wintypes.DWORD),
            ("wParamL", wintypes.WORD),
            ("wParamH", wintypes.WORD),
        ]

    class INPUT(ctypes.Structure):
        class _INPUT_UNION(ctypes.Union):
            _fields_ = [
                ("ki", KEYBDINPUT),
                ("mi", MOUSEINPUT),
                ("hi", HARDWAREINPUT),
            ]
        _anonymous_ = ("_union",)
        _fields_ = [
            ("type", wintypes.DWORD),
            ("_union", _INPUT_UNION),
        ]

    # 设置 SendInput 函数签名
    user32.SendInput.argtypes = [
        wintypes.UINT,
        ctypes.POINTER(INPUT),
        ctypes.c_int,
    ]
    user32.SendInput.restype = wintypes.UINT

    def _send_key_event(scan_code: int, flags: int):
        """发送一个键盘扫描码事件."""
        inp = INPUT(type=INPUT_KEYBOARD)
        inp.ki = KEYBDINPUT(
            wVk=0,
            wScan=scan_code,
            dwFlags=flags,
            time=0,
            dwExtraInfo=None,
        )
        sent = user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))
        if sent != 1:
            err = ctypes.get_last_error()
            print(f"[WindowsInjector] SendInput 失败 (error={err})")

    class WindowsInjector(KeyInjector):
        """Windows 游戏模式：通过 SendInput 发送硬件扫描码."""

        def press_key(self, ascii_code: int):
            sc = ASCII_TO_SCANCODE.get(ascii_code)
            if sc is None:
                print(f"[WindowsInjector] 未映射的键: '{chr(ascii_code)}' (0x{ascii_code:02X})")
                return
            _send_key_event(sc, KEYEVENTF_SCANCODE)

        def release_key(self, ascii_code: int):
            sc = ASCII_TO_SCANCODE.get(ascii_code)
            if sc is None:
                return
            _send_key_event(sc, KEYEVENTF_SCANCODE | KEYEVENTF_KEYUP)
