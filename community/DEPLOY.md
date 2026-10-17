# 墨知 0.5.0 · 给部署 Agent 的交接

代码仓库：`StonesGitHub/Personal-Agent`。请使用自己的主机、域名、凭证以及独立的 Docker 项目和数据卷。

## 服务和数据边界

- `community/app.py`：现有发现社区（SQLite、问答、赞、评论、举报）。
- `community/web_service.py`：新增搜索、公开网页读取、DeepSeek 分享读取/导入预览。无模型代理，也不保存网页/聊天正文；数据库仅保存社区主动发布的数据和匿名化限流计数。
- Android 默认手机直连。`我 → 设置 → 联网服务` 可填写部署后的 HTTPS 根地址和该设备的服务凭证，无需重打包。配置网关时，只提交关键词或目标 URL；模型 API Key、记忆库、GPS 对象、聊天文件不会整体上传。搜索词和分享 URL 本身仍可能含个人信息。
- 社区仍默认本机 demo。**配置联网服务不会自动开启公网社区或上传已有分享。** 需要多人社区时，构建前显式设置 `MOZHI_COMMUNITY_URL=https://域名`。部署服务和开放社区是两个步骤。

## 部署前需要准备

Linux 主机、Docker Engine 与 Docker Compose v2（支持 `up --wait`）、域名 A/AAAA 记录、公网 80/443。服务出站需允许 HTTPS 和 DNS。不要把 8090 暴露公网。

搜索提供两种方式：

| 配置 | 用途 |
| --- | --- |
| `MOZHI_SEARCH_PROVIDER=duckduckgo` | 不需要搜索密钥；HTML 搜索可能遇到验证、限流、地区网络限制。失败会明确报错 |
| `MOZHI_SEARCH_PROVIDER=brave` + `BRAVE_SEARCH_API_KEY` | 官方搜索 API；密钥由运营者申请并只放服务器。请按自己的方案设置调用预算 |

Brave 官方接口：[Web Search](https://api-dashboard.search.brave.com/api-reference/web/search/get)、[认证](https://api-dashboard.search.brave.com/documentation/guides/authentication)。未提供真实 Brave 密钥，因此该适配器经过模拟响应验证，尚需部署方实测。

## 1. 检出并验证

```bash
git clone https://github.com/StonesGitHub/Personal-Agent.git
cd Personal-Agent/community
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements-test.txt
pytest -q
python3 scripts/init-env.py
```

`init-env.py` 只新建权限 0600 的 `.env`，已有文件不会被覆盖，也不会在终端打印凭证。编辑 `.env`：填入 `MOZHI_DOMAIN`（仅域名，不含协议）、`MOZHI_ACME_EMAIL`，选择搜索提供方。

- `MOZHI_MODERATOR_TOKEN` 只给社区维护者，不能填入 App。
- `MOZHI_WEB_TOKENS` 是联网专用凭证，可按设备生成多个、逗号分隔。App 填其中一个。删除某个 token 并重建服务即可撤销；不要共用模型密钥。
- 生成额外设备凭证：在安全终端运行 `python3 -c 'import secrets; print(secrets.token_urlsafe(36))'`，私下交给设备使用者。
- 不要把 `.env`、数据库、备份、签名密钥提交 Git，也不要在部署报告中贴出值。

## 2. 启动

已有 HTTPS 反向代理：

```bash
./scripts/deploy.sh
python3 scripts/smoke.py http://127.0.0.1:8090 --web
```

代理转发 `127.0.0.1:8090`，请求体上限 150 KB、响应超时至少 50 秒；不记录请求正文、Authorization 或查询参数。

新主机使用附带 Caddy 自动证书：

```bash
./scripts/deploy.sh --https
python3 scripts/smoke.py https://你的域名 --web
```

如果 80/443 已被占用，使用已有代理方式；不要停止其它项目。Caddy 镜像使用 `caddy:2-alpine`，部署时记录镜像 digest。首次需能连接 ACME。Compose 配置检查用 `docker compose config --quiet`，不要把含凭证的完整配置贴到日志。

脚本使用固定 Compose 项目名 `mozhi`，数据库 volume 是 `mozhi_discovery-data`；Caddy 证书保存在独立 volume。容器以非 root 用户运行，根文件系统只读，应用只写 `/data` 与 `/tmp`。保持单实例、单 worker；SQLite 与当前限流/并发模型面向邀请内测。

## 3. 接口验收

| 接口 | 认证与行为 |
| --- | --- |
| GET `/health` | 无认证；检查数据库连接，返回版本 |
| GET `/v1/web/status` | `Authorization: Bearer <联网凭证>`；供 App 测试连接 |
| POST `/v1/web/search` | `{"query":"搜索词"}`；最多 5 条真实搜索结果与 URL、摘要 |
| POST `/v1/web/read` | `{"url":"https://..."}`；正文、来源、截断标志；DeepSeek 另返回原始角色的可见消息 |
| POST `/v1/web/import` | 同上；只返回 `preview_only`，不写数据库、不发布社区；手机确认后才本地保存 |

未设置 `MOZHI_WEB_TOKENS` 时接口返回 503；无效 token 返回 401；超额 429；上游失败 502。每个 token 每分钟 30、每天 500 次；网关最多 4 个并发读取。Brave 凭证不会跟随重定向。网页仅 HTTPS/443；拒绝内网和本机、混合私网 DNS、私网跳转；连接固定到本次检查的 IP 并核验原域名 TLS。

读取上限 2 MiB、30 秒和 4 次重定向，输出正文最多 16000 字并标记截断。分享记录最多 2000 条/100 万字，单条 40000 字。需要登录、验证、任意 JavaScript 执行的网站不支持绕过；提示用户导出 JSON/TXT/Markdown。DeepSeek 使用其公开分享接口，非稳定合作 API，网站变动时可能需要更新适配。

上线验收：

1. `smoke.py --web` 成功，未认证请求被拒绝；不粘贴 token 到命令参数。
2. App 配置服务地址并显示连接成功；问一个需联网的问题，来源链接可点开。
3. 用部署方自己的公开 DeepSeek 分享验证“读取 → 导入预览 → 确认 → 本地历史”，重复导入不重复新增。
4. 关闭联网后不再搜索/读链接；本地文件导入仍可用。
5. 如要开放社区，用两台测试设备验收发帖、赞、评论、删除；保持当前匿名内测限制，不宣称已具备账号恢复与完整审核系统。

## 4. 备份、更新与回滚

```bash
./scripts/backup.sh
```

脚本通过 SQLite 在线备份 API 生成一致快照，执行完整性检查，复制至 `backups/`，权限 0600。备份含社区内容，应另存受限的异机位置；不会备份手机记忆。备份 `.env` 与证书 volume 的策略由部署方安排。

更新前记录 `git rev-parse HEAD` 与当前镜像 ID，执行备份；拉取目标提交后再次运行部署脚本。**不要运行 `docker compose down -v`**。本次没有改动旧社区数据库表结构，保留原数据。

代码回滚：检出记录的旧提交，恢复与该版本匹配的 `.env` 配置并重建，保留 volume。若只需关闭新网关，清空 `MOZHI_WEB_TOKENS` 后重建即可，社区仍可运行。

需要数据恢复时，先备份当前库并暂停写入，停止 `discovery`，通过受控的一次性容器把已验证快照复制到 `/data/community.sqlite3`、属主 `10001:10001`；在服务停止时移走旧 `-wal`/`-shm`，再启动并跑健康检查。不要在运行中的 SQLite 文件上直接覆盖快照；恢复操作须由部署负责人明确选择备份版本。

## 交回给开发者的结果

部署提交 SHA、HTTPS 服务根地址、健康检查与 smoke 的通过/失败（不含正文或凭证）、采用的搜索提供方、镜像 ID、备份位置。联网凭证通过私密渠道给 App 使用者。服务上线前，本次 APK 的默认手机直连和本机发现仍可用。
