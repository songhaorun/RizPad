"""
protocol.py — RizPad 通信协议定义与解析

协议格式（iPad → PC）:
    [1 byte 长度 N] [N bytes payload]

payload 第一字节为命令:
    0x01  KEY_STATE  — 后续字节为当前按下的键的 ASCII 码（排序后）
                       空列表表示全部松开

示例:
    按下 a 和 s  → bytes: 03 01 61 73
    全部松开      → bytes: 01 01
"""

from dataclasses import dataclass
from typing import List, Optional

# 命令常量
CMD_KEY_STATE = 0x01


@dataclass
class KeyStateMessage:
    """表示从 iPad 收到的一帧按键状态."""
    pressed_keys: List[int]    # ASCII 码列表, 已排序

    @property
    def pressed_chars(self) -> List[str]:
        """返回按下的键的字符列表."""
        return [chr(k) for k in self.pressed_keys]

    @property
    def is_all_released(self) -> bool:
        return len(self.pressed_keys) == 0

    def __repr__(self) -> str:
        if self.is_all_released:
            return "KeyState(none)"
        keys_str = " ".join(self.pressed_chars)
        return f"KeyState({keys_str})"


def parse_payload(payload: bytes) -> Optional[KeyStateMessage]:
    """
    解析一个完整的 payload（不含前面的长度字节）。
    返回 KeyStateMessage 或 None（未知命令）。
    """
    if len(payload) < 1:
        return None

    cmd = payload[0]

    if cmd == CMD_KEY_STATE:
        keys = list(payload[1:])
        return KeyStateMessage(pressed_keys=keys)

    # 未知命令
    print(f"[Protocol] 未知命令: 0x{cmd:02X}")
    return None


def diff_key_state(prev: List[int], curr: List[int]):
    """
    比较前后两帧按键状态，返回 (newly_pressed, newly_released)。
    用于 PC 端决定发送 key_down / key_up 事件。

    Returns:
        newly_pressed:  本帧新增按下的键 (ASCII 码列表)
        newly_released: 本帧新增松开的键 (ASCII 码列表)
    """
    prev_set = set(prev)
    curr_set = set(curr)
    newly_pressed = sorted(curr_set - prev_set)
    newly_released = sorted(prev_set - curr_set)
    return newly_pressed, newly_released
