"""
tcp_client.py — TCP 客户端，连接到 iPad 上运行的 RizPad App

负责:
  1. 建立 TCP 连接（支持重连）
  2. 按协议读取数据帧（1 字节长度 + N 字节 payload）
  3. 解析后通过回调通知上层
"""

import socket
import time
from typing import Callable, Optional

from protocol import KeyStateMessage, parse_payload

# 默认端口（与 iPad 端 NetworkManager 一致）
DEFAULT_PORT = 24864

# TCP_NODELAY 已在 iPad 端开启；PC 端也开启以降低回传延迟
RECV_TIMEOUT = 10.0  # 秒，超时后视为断开
RECV_BUF_SIZE = 4096  # 小缓冲区减少内核排队延迟


class RizPadClient:
    """连接到 iPad RizPad App 的 TCP 客户端."""

    def __init__(self, host: str, port: int = DEFAULT_PORT):
        self.host = host
        self.port = port
        self._sock: Optional[socket.socket] = None
        self._running = False

        # 回调
        self.on_key_state: Optional[Callable[[KeyStateMessage], None]] = None
        self.on_connected: Optional[Callable[[], None]] = None
        self.on_disconnected: Optional[Callable[[], None]] = None

    # ------------------------------------------------------------------ #
    # 公开方法
    # ------------------------------------------------------------------ #

    def connect(self) -> bool:
        """尝试连接 iPad，成功返回 True."""
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, RECV_BUF_SIZE)
            sock.settimeout(RECV_TIMEOUT)
            sock.connect((self.host, self.port))
            self._sock = sock
            print(f"[TCP] 已连接到 {self.host}:{self.port}")
            if self.on_connected:
                self.on_connected()
            return True
        except (socket.error, OSError) as e:
            print(f"[TCP] 连接失败: {e}")
            return False

    def run_forever(self, reconnect_interval: float = 2.0):
        """
        阻塞式主循环：连接 → 读数据 → 断开后自动重连。
        按 Ctrl+C 退出。
        """
        self._running = True
        while self._running:
            if not self._sock:
                print(f"[TCP] 正在连接 {self.host}:{self.port} ...")
                if not self.connect():
                    print(f"[TCP] {reconnect_interval}s 后重试...")
                    time.sleep(reconnect_interval)
                    continue

            try:
                self._read_loop()
            except KeyboardInterrupt:
                self._running = False
            finally:
                self._close()

            if self._running:
                print(f"[TCP] 连接断开，{reconnect_interval}s 后重连...")
                time.sleep(reconnect_interval)

    def stop(self):
        """停止主循环."""
        self._running = False
        self._close()

    # ------------------------------------------------------------------ #
    # 内部方法
    # ------------------------------------------------------------------ #

    def _read_loop(self):
        """持续从 socket 读取帧并解析."""
        while self._running:
            # 1) 读 1 字节长度
            length_byte = self._recv_exact(1)
            if length_byte is None:
                return  # 断开
            length = length_byte[0]
            if length == 0:
                continue

            # 2) 读 N 字节 payload
            payload = self._recv_exact(length)
            if payload is None:
                return  # 断开

            # 3) 解析
            msg = parse_payload(payload)
            if msg is not None and isinstance(msg, KeyStateMessage):
                if self.on_key_state:
                    self.on_key_state(msg)

    def _recv_exact(self, n: int) -> Optional[bytes]:
        """精确读取 n 字节，连接断开或超时返回 None."""
        buf = bytearray()
        while len(buf) < n:
            try:
                chunk = self._sock.recv(n - len(buf))
                if not chunk:
                    return None  # 对端关闭
                buf.extend(chunk)
            except socket.timeout:
                # 超时 → 视为断开（iPad 可能休眠/退出）
                print("[TCP] 接收超时")
                return None
            except (socket.error, OSError):
                return None
        return bytes(buf)

    def _close(self):
        if self._sock:
            try:
                self._sock.close()
            except Exception:
                pass
            self._sock = None
            print("[TCP] 连接已关闭")
            if self.on_disconnected:
                self.on_disconnected()
