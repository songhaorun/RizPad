# RizPad + RizPC

将 iPad 变为低延迟触控键盘，用于全屏音游。

参考项目：<br>https://github.com/esterTion/Brokenithm-iOS
<br>https://github.com/pinapelz/brokenithm-evolved-ios-umi

主要构造人员：Claude Opus 4.6

## 工作原理

```
iPad (RizPad App)                    PC (RizPC)
┌──────────────┐    TCP/USB     ┌──────────────────┐
│ 多指触摸      │ ──────────→  │ 接收触摸数据       │
│ 按顺序分配键位 │   端口24864   │ 注入系统键盘事件   │
│ 圆点跟随手指  │ ←(可选LED等)  │ 全屏游戏可用       │
└──────────────┘               └──────────────────┘
```

- 第1根手指按下 → `a`，第2根 → `s`，第3根 → `d` ... 最多10根手指
- 手指移动不会改变键位，只有松开才释放
- 松开后该槽位空出，下一根新手指复用最小空闲槽

## 键位映射

| 槽位 | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|------|---|---|---|---|---|---|---|---|---|---|
| 键   | a | s | d | f | g | h | j | k | l | ; |

注：加入按键冷却后，为防止多指情况下按键不够用，后续又添加了更多键位

## 通信协议

```
[1字节 长度N] [N字节 payload]

payload = [命令] [数据...]

命令 0x01 KEY_STATE:
  数据 = 当前按下的键的 ASCII 码（排序后）
  空数据 = 全部松开

示例:
  按下 a 和 s  → 03 01 61 73
  全部松开      → 01 01
```

---

## iPad 端 (RizPad)

### 环境要求

- macOS + Xcode（当前使用 Xcode 26.2）
- iOS 17.0+
- iPad 真机（需用 USB 连接 Mac 部署）

### 项目结构

```
RizPad/RizPad/RizPad/
├── ViewController.swift     # 全屏触控、圆点可视化、触摸事件处理
├── NetworkManager.swift     # TCP Server (NWListener)，端口 24864
├── TouchKeyMapper.swift     # 槽位分配逻辑（按触下顺序）
├── AppDelegate.swift        # App 生命周期（默认模板）
├── SceneDelegate.swift      # Scene 生命周期（默认模板）
└── Info.plist
```

### 部署

1. 用 Xcode 打开 `RizPad/RizPad/RizPad.xcodeproj`
2. 选择你的 iPad 作为目标设备
3. 点 ▶ Run 部署到设备
4. iPad 上出现全屏黑色界面 + 底部键位指示器 = 成功

> 首次运行如果弹出"是否允许本地网络访问"，请点**允许**。

---

## PC 端 (RizPC)

### 环境要求

- Python 3.7+
- 无需安装第三方库（纯标准库 + ctypes）
- Windows 上需以**管理员权限**运行（全屏游戏需要）

### 项目结构

```
RizPC/
├── main.py            # 主入口
├── tcp_client.py      # TCP 客户端，连接 iPad，自动重连
├── protocol.py        # 协议定义与解析
└── key_injector.py    # 键盘注入（DebugInjector / WindowsInjector）
```

### 使用方式

#### 方式一：Wi-Fi 连接

确保 iPad 和 PC 在同一局域网：

```bash
python3 main.py <iPad的IP地址>
# 例如:
python3 main.py 192.168.1.100
```

#### 方式二：USB 连接（推荐，更低延迟）

1. 安装 libimobiledevice（提供 iproxy）：
```bash
# macOS
brew install libimobiledevice

# Windows (chocolatey)
choco install libimobiledevice
```
后注：貌似choco不再提供iproxy的包了，可以从Github上下载安装

2. USB 连接 iPad，运行端口转发：
```bash
iproxy 24864:24864
```

3. 另开终端运行：
```bash
python3 main.py 127.0.0.1
```

### macOS 调试模式

当前默认使用 `DebugInjector`，只在终端打印按键事件：

```
  ↓ 按下 'a'
  ↓ 按下 's'
  ↑ 松开 's'
  ↑ 松开 'a'
```

### Windows 游戏模式

需要在 `key_injector.py` 中启用 `WindowsInjector`（使用 `SendInput` + 扫描码），并修改 `main.py` 使其在 Windows 上自动切换。

扫描码映射：

| 键 | a | s | d | f | g | h | j | k | l | ; |
|----|-----|-----|-----|-----|-----|-----|-----|-----|-----|-----|
| 码 | 0x1E | 0x1F | 0x20 | 0x21 | 0x22 | 0x23 | 0x24 | 0x25 | 0x26 | 0x27 |

---

## 完整流程（快速开始）

1. Xcode 部署 RizPad 到 iPad → 打开 App
2. USB 连接 iPad 到电脑
3. 终端 1：`iproxy 24864:24864`
4. 终端 2：`python3 main.py 127.0.0.1`
5. 触摸 iPad 屏幕 → PC 终端显示按键事件

## 开发者

- iPad App: Swift + UIKit + Network.framework
- PC Client: Python 3（标准库）
- 协议: 自定义二进制，TCP，低延迟（TCP_NODELAY）
