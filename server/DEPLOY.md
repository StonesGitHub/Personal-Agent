# Agent 与独立管理后台部署交接

本仓库提供自行部署所需的代码与模板。请准备自己的主机、域名和模型密钥，各服务使用独立目录与数据卷。

## 1. 执行服务

```sh
cd Personal-Agent/server
python3 scripts/init-env.py
```

初始化只新建权限 0600 的 `.env`，已有文件不会覆盖。编辑 `.env`：

| 字段 | 用途 |
| --- | --- |
| `MOZHI_MODEL_BASE_URL` | 固定 HTTPS 模型根地址，兼容 Chat Completions；默认 DeepSeek |
| `MOZHI_MODEL_API_KEY` | 运营者模型密钥，必须填写 |
| `MOZHI_MODEL` | 首次建库的默认模型ID；以后由管理页面配置 |
| `MOZHI_VISION_MODEL` | 图片识别模型，默认 `deepseek-flash`，需使用支持图像理解的模型 API |
| `MOZHI_ADMIN_API_TOKEN` | 自动生成的管理 API 凭证；只交给独立后台服务端 |
| `MOZHI_WEB_BASE_URL` / `MOZHI_WEB_TOKEN` | 可选的现有 `community/` 网关地址与联网凭证；未配时不开放网页工具 |

**模型配置：** 示例模型名需要按你所使用的供应商和账号能力调整。文本模型须支持工具调用，视觉模型须支持图像输入。已有数据库中的模型配置请通过管理后台修改；升级前备份数据库。

模型必须支持 function tool calling。模型 API 地址不在管理页面编辑，修改环境后重建进程。联网网关可以先按 `community/DEPLOY.md` 部署；它仍独立运行，Agent 镜像不复制其代码。

```sh
sh scripts/deploy.sh
curl --fail http://127.0.0.1:8091/health
```

三个容器：`api`、`admin-api`、`worker`。公共 API 绑定主机 `127.0.0.1:8091`，管理 API 绑定 `127.0.0.1:8092`；内部管理网络名称 `mozhi-agent-management`。SQLite 位于独立 volume `mozhi-agent_agent-data`，属主10001。根文件系统只读，应用仅写 `/data` 和临时目录。必须单 worker、单主机。

配置现有 HTTPS 反向代理，把 Agent 域名转发至 `127.0.0.1:8091`。普通接口请求体上限至少300KB；`/v1/agent/attachments` 的反向代理请求体上限须至少9MiB，上传超时建议120秒，不记录 Authorization、正文或查询参数。用户长任务采用轮询，普通 API 超时设30秒即可。不要把 `8092` 或管理 API 路由挂到公共 Agent 域名。

## 2. 独立管理站点

`admin/` 完全独立：自己的 Python 依赖、静态页面、登录会话、Dockerfile、Compose 和环境配置；不挂载 Agent 数据库。

```sh
cd ../admin
python3 scripts/init-env.py
```

脚本交互设置至少12位密码，保存 PBKDF2 哈希，原文不落盘。编辑 `.env`：

- `MOZHI_ADMIN_API_TOKEN`：填写上一步生成的内部管理凭证。
- `MOZHI_ADMIN_API_URL`：同机两个独立 Docker 项目默认 `http://admin-api:8081`，通过内部管理网络通信。
- `MOZHI_ADMIN_ORIGIN`：浏览器实际访问的完整 HTTPS Origin，例如 `https://admin.your-domain.example`，不带路径。
- `MOZHI_ADMIN_USERNAME`：登录用户名，默认 `admin`。
- 哈希值中的 `$` 保持单引号包裹，避免 Compose 插值。

```sh
sh scripts/deploy.sh
```

后台绑定 `127.0.0.1:8093`，用另一个 HTTPS 域名转发。后台进程单 worker，会话仅保存在进程内，重启会退出登录。Cookie 为 Secure + HttpOnly + SameSite=Strict，写操作校验 CSRF；浏览器不会得到内部管理密钥。

### 管理站点部署到另一台机器

1. 仅复制 `admin/` 目录即可。
2. 将 `MOZHI_ADMIN_API_URL` 指向通过 VPN/私网可达的管理 API，或带访问限制的 HTTPS 代理地址；跨公网传输必须 HTTPS。
3. 使用独立的远程部署文件，不依赖执行服务的 Docker 网络：

```sh
sh scripts/deploy.sh --remote
```

不要在公网直接开放8092。浏览器始终只访问管理站点同源 `/api/*`，BFF 再调用管理 API；不需要允许公共 API 的跨域管理操作。

## 3. 用户与手机接入

1. 在后台确认“执行服务运行中”“模型凭证已配置”。
2. 创建测试用户，复制只展示一次的用户凭证。按需求配置额度和工具。
3. 安装从本次代码构建的 Android 0.8.1。在 `我 → 设置 → 执行方式` 填公共 Agent HTTPS 根地址和用户凭证，选择“连接服务端”。旧客户端不具备此次新增的附件能力，也仍可能存在手机直连模型模式。0.8.1 只保留服务端连接入口，启动时移除本地旧模型 API Key / 模型地址 / 模型 ID。
4. 首次发送会说明上下文与任务保存的位置，确认后正常聊天。
5. 测试：“计算3本书每本20元、2支笔每支5元的总费用，生成CSV”。应产生70元结果与实际文件链接；点击可预览、复制、通过系统文件选择器保存。
6. 执行期间关闭手机应用，再打开或点“同步服务端任务”，应取回同一任务，不生成重复任务。
7. 点停止应取消服务端任务。服务端重启导致的中断需在消息下点“同步或继续服务端任务”。

失败或取消时已有文件仍在服务端，可通过对应用户的文件接口取回。管理页面的任务记录不显示私人文件正文。

## 4. 验证

```sh
cd server
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements-test.txt
python -m pytest -q tests
cd ../admin
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements-test.txt
python -m pytest -q tests
```

Android 构建与签名请参见 [RELEASE.md](../RELEASE.md) 和 [PREVIEW.md](../PREVIEW.md)。请使用自己的应用 ID 和签名密钥。覆盖更新需要相同包名及签名。

部署验收还需真实模型的 function calling、真实搜索网关、中国大陆设备网络及 HTTPS 访问。代码测试使用模拟模型，不能替代这些线上验证。

## 5. 备份和更新

更新前记录 git commit 与当前镜像ID，并备份 Agent 数据库（包含上下文和生成文件）：

```sh
cd server
mkdir -p backups
chmod 700 backups
docker compose exec -T api python scripts/backup.py /tmp/agent-backup.sqlite3
docker compose cp api:/tmp/agent-backup.sqlite3 backups/agent-backup.sqlite3
chmod 600 backups/agent-backup.sqlite3
```

示例目标已存在时先归档为带时间戳的备份，不能覆盖旧备份。另行备份服务端 `.env` 到受限位置。更新可重新构建服务，重启 worker 后运行中的任务需用户恢复。只更新后台不会停止执行中的 Agent 任务。

本次使用全新的 Agent 数据库，不修改原社区数据库。回退到升级前的手机直连方式时可停用新服务；不要删除其 volume。数据库恢复应先停止 `api`、`admin-api` 和 `worker`，由部署负责人明确选择备份后进行，禁止运行 `docker compose down -v` 当作更新步骤。

完成部署后交回：提交SHA、公共Agent地址、独立后台地址、worker健康状态、模型/搜索实测结果、镜像ID与备份位置。凭证不写在公开报告或聊天记录中。


## 0.7.0 内置技能升级

更新公共API、管理API、worker及独立后台静态资源。数据库启动时自动增加提醒、到期事件、健康观察和幂等回执表；升级前照常备份。Android需使用0.7.0（versionCode14）。

既有全局配置和既有用户的白名单不自动扩大。在后台两处勾选新工具，否则界面虽能显示技能，Agent仍无执行权限。技能包已复制到server包内，部署时不用挂载仓库根目录的skills目录。

手机通知依赖本机同步与Android授权；无FCM/厂商推送接入。详见 [技能交付与限制](../skills/README.md) 以及 [验证说明](../TESTING.md)。提醒接口和健康记录使用现有公共API，不需要增加开放端口，管理后台仍独立部署。

## 0.8.1 Android 服务端统一模型配置（无需更改公网域名）

- 服务器 `.env` 负责 `MOZHI_MODEL_API_KEY` 与 `MOZHI_MODEL_BASE_URL`；任务推理模型通过独立管理后台的运行时配置管理，视觉模型通过 `MOZHI_VISION_MODEL` 配置。**切勿把服务端的模型 API Key 写入 APK 或发给用户。**
- Android「我 → 设置 → Agent 服务」只保留公共 HTTPS Agent 根地址和经后台生成的用户凭证，不提供模型 Key / 模型 ID 编辑或手机直连模式。背景整理与所有聊天任务统一走 `/v1/agent/runs`。
- 安装包见仓库 [PREVIEW.md](../PREVIEW.md) 的 GitHub Actions 构建流程；在 Actions 成功后才可下载，签名 preview 包仅在已配置旧密钥的情况下生成。
- 这次代码改动**尚未部署**。部署前备份 Agent SQLite，更新 API + worker，检查真实模型、附件上传和 Android 服务端连接流程。不要仅更新 APK 而遗漏服务端的 0.8.0 附件接口。
