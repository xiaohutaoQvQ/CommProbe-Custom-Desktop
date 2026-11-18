# CommProbe-Custom-Desktop

面向 Apple 芯片 Mac 的桌面通信调试工具，基于 Tauri、Vue 和 Java，支持 TCP 客户端、TCP 服务端、UDP 和串口。

## 技术架构

**Tauri + Vue + Java**

- **Tauri**：提供 macOS 桌面窗口和应用打包，管理 Java 后端进程的启动与退出。
- **Vue**：实现操作界面、连接配置、报文发送和通信日志展示。
- **Java**：实现 TCP、UDP、串口通信及报文处理，使用 jSerialComm 支持串口。

Tauri 与 Java 后端通过进程标准输入、输出交换消息。打包后的应用无需单独启动后端或安装 Java 运行环境。

## 功能

- 支持 TCP 客户端、TCP 服务端广播、UDP 收发和串口通信。
- 按空闲时间分帧，支持 HEX 与文本显示、过滤、重复帧折叠和会话 JSONL 记录。
- 文本发送和日志显示可分别选择 UTF-8、GBK 或 GB18030；HEX 按输入的字节原样发送。
- 支持多行文本、常用报文、周期和序列发送，以及 CRC16、SUM8、XOR8 校验。
- 自动记忆连接参数、快捷发送草稿和操作台布局；重新打开后需手动连接，循环发送不会自动启动。
- Java 内核由桌面程序自动启动，界面与内核通过进程管道通信，不开放本地后端服务端口。

发送使用 GB18030 编码的 ZPL 时，请将发送编码设为 GB18030，并确保打印机模板的编码设置和字体与之匹配。日志的显示编码独立于发送编码。发送前请自行替换模板变量。

## 开发环境

需要 macOS、Apple 芯片、Node.js 20 或以上、Java 17 JDK、Maven、Rust 和 Xcode Command Line Tools；运行内核测试还需要 Python 3。安装依赖并启动开发版：

```bash
npm ci
npm run desktop:dev
```

开发脚本会编译 Java 内核并准备运行时，然后打开桌面窗口。生产构建不需要用户单独安装 Java。

## 测试与构建

```bash
npm run typecheck
npm run test:web
npm run test:backend
npm run desktop:build
```

`test:backend` 使用本机回环 TCP、UDP 和伪终端验证通信，不需要实体串口。真实设备兼容性仍需连接设备实测。

构建结果位于 `src-tauri/target/release/bundle/macos/CommProbe-Custom-Desktop.app`。应用内置精简 Java 运行时，不需用户启动终端或单独安装 Java。未签名和公证的构建在其他 Mac 上可能需要通过 macOS 的安全提示。

## 数据与架构

- `web/`：Vue 界面和前端共享逻辑。
- `desktop-backend/`：Java 通信内核及串口支持。
- `src-tauri/`：桌面窗口、Java 进程生命周期和资源配置。
- `tests/`：前端单元测试及通信内核集成测试。

界面偏好和常用报文保存在桌面 WebView 的本地存储，与原浏览器版隔离。会话记录位于 `~/.commprobe/sessions/`。
