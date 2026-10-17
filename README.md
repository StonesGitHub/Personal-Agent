# Personal-Agent · 墨知

墨知是一套可自行部署的个人 Agent：Android 和 macOS 客户端负责交互，服务端负责模型调用、工具执行与持续任务，独立管理后台负责用户和运行配置。

**本仓库为开发版本的独立开源快照，采用 MIT 许可证。** 模型密钥、短信凭证和个人服务配置由部署者自行提供。客户端首次启动不会预填任何生产服务地址。

## 功能

- **Agent 任务**：多轮工具调用、执行计划、持久队列、取消与中断恢复；客户端关闭后，已提交任务仍可在服务端继续。
- **个人上下文**：本机背景、聊天、决定、技能与加密备份；服务端按用户隔离任务、附件和账号快照。
- **附件**：Word（DOCX）、Excel（XLSX）、图片；图片识别需要配置支持视觉输入的模型。
- **手机号账号**：短信验证码登录、绑定已有服务账号、手机资料备份与跨端取回。需要自行配置短信服务。
- **内置技能**：出游规划、定时提醒、健康观察记录；联网、地图和文件工具按权限开放。
- **发现**：默认使用明确标注的示例问答和本机分享；另提供可选社区服务。
- **独立管理后台**：用户、额度、工具权限、任务与运行配置管理。

Android 开发版本为 0.9.0，macOS 客户端为 0.2.0。提醒依赖手机同步和系统授权；尚未接入厂商实时推送。手机和 Mac 的本机资料不会自动双向同步，账号快照需要主动上传、取回。草稿中的通知/日历扩展及另一条分享读取修复未合入此快照。

## 项目结构

| 目录 | 内容 |
| --- | --- |
| `app/` | 原生 Android 客户端（Java） |
| `macos/` | macOS 客户端（Swift / AppKit / WebKit） |
| `server/` | Agent API、执行 worker、手机号账号与附件服务 |
| `admin/` | 独立管理站点，通过内部管理 API 连接服务端 |
| `community/` | 可选发现社区和联网网关 |
| `skills/` | 内置技能源文件及同步说明 |
| `design/` | 应用图标与标识 |

## 快速开始

### 1. 获取代码

```sh
git clone https://github.com/StonesGitHub/Personal-Agent.git
cd Personal-Agent
```

### 2. 配置并启动服务端

需要 Docker Engine 与 Docker Compose v2。

```sh
cd server
python3 scripts/init-env.py
# 在本机编辑 .env，填入自己的模型密钥、模型名称及可选短信配置。
sh scripts/deploy.sh
curl --fail http://127.0.0.1:8091/health
```

初始化脚本生成私有的管理凭证和账号认证密钥，不覆盖已有 `.env`。模型 API Key 仅保存在服务端；示例模型名称需按供应商账号能力调整。管理 API 只用于内部网络，客户端通过你自己的 HTTPS 公共 API 接入。

完整说明：[服务端部署](server/DEPLOY.md) · [API 与配置](server/README.md) · [管理后台](admin/README.md) · [手机号账号与资料迁移](PHONE_ACCOUNTS.md)。没有短信服务时，可先在管理后台创建用户并通过个人服务凭证连接。

### 3. 构建 Android

需要 Java 17、Android SDK 35。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

产物为 `app/build/outputs/apk/debug/app-debug.apk`，默认测试包名 `com.mozhi.assistant.dev`。在「我」的服务设置或账号登录页填写自行部署的 HTTPS 地址与个人凭证。模型 API Key 不应填入客户端。

[预览构建](PREVIEW.md) · [自行签名发布](RELEASE.md)。覆盖安装需要相同包名和签名；更换签名前先导出并核对个人备份。

### 4. 构建 macOS

在安装 Xcode Command Line Tools 的 Mac 上运行：

```sh
bash macos/scripts/build.sh
```

首次连接填写自己的 HTTPS 服务地址。详细功能、构建和签名说明见 [macos/README.md](macos/README.md)。

## 开发与数据边界

- 测试命令见 [TESTING.md](TESTING.md)。仓库包含 Android、服务端和 macOS 的 CI 配置；构建成功以各次 Actions 结果为准。
- 示例问答和测试夹具为演示内容。运行数据、聊天备份、附件、日志、密钥、签名材料和历史验收截图不属于源码。
- Agent 调用会把选中的上下文发送给配置的模型服务；账号快照由服务端运营者保存，**不是端到端加密云盘**。
- 密钥与配置保护说明见 [SECURITY.md](SECURITY.md)。提交前运行 `python3 scripts/check-public-files.py`。

## 许可证

本项目原创代码及随附原创资源使用 [MIT License](LICENSE)。第三方组件继续遵循各自许可证，说明见 [THIRD_PARTY.md](THIRD_PARTY.md) 与 `app/src/main/assets/licenses/`。
