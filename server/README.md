# 墨知 Agent 服务 0.8.0

可聊天、可连续使用工具执行任务的独立服务。Android 通过 HTTPS 提交任务并轮询结果；执行由持久队列和独立 worker 驱动，与手机网络连接和页面生命周期分开。

## 内置技能与新增接口

出游、定时任务提醒、健康管理已接入运行时，详细语义见 [技能说明](../skills/README.md)。新增的所有接口使用当前用户凭证：

| 方法 / 路径 | 行为 |
| --- | --- |
| GET `/v1/skills` | 已打包内置方法目录 |
| GET/POST `/v1/reminders` | 有效提醒完整快照（最多100条）/幂等创建 |
| POST `/v1/reminders/{id}/update` | 完整替换时间及文字，保持ID |
| POST `/v1/reminders/{id}/cancel` | 取消本人提醒 |
| GET `/v1/reminders/inbox` | 最早的200条未确认到期记录，保留30天 |
| POST `/v1/reminders/inbox/ack` | `{"ids":[1,2]}`，按用户确认已接收 |
| GET/POST `/v1/health-records` | 观察记录分页（`?before=`）/幂等保存 |
| POST `/v1/health-records/{id}/delete` | 删除本人记录，幂等 |

提醒写入字段：`title`、`body`、`start_at`（带偏移ISO时间）、`timezone`（IANA）、`repeat`（once/daily/weekly）、`client_request_id`。健康观察写入字段：`subject`、`category`（diet/sleep/symptom/activity/measurement）、`text`、`occurred_at`、`client_request_id`。健康观察必须已发生，不能记录未来计划。

任务输入新增 `builtin_skills`（ID列表：travel/reminders/health，空列表停用全部）、`timezone`（默认Asia/Shanghai）、`allow_personal_data`（false关闭健康记录工具）。缺省保持兼容；工具最终仍受全局与用户白名单约束。已有用户与配置不会自动开启新增工具，须管理员勾选。

这些接口不调用模型。到期事件由现有worker产生；不是手机厂商推送，手机需同步并获得通知权限。

## 能做的事

- 简单聊天直接回复；复杂工作可以记录计划、搜索/读取网页、计算、创建 Markdown/TXT/CSV/JSON 文件、继续读取已有成果。
- 每用户独立身份、工具白名单、每日额度（含聊天，按北京时间零点计算）、最多三个排队或执行中的任务。同一对话串行执行。
- 提交使用 `client_request_id` 幂等；网络重试不创建第二个任务，内容变更返回 409。
- 任务、工具事件、检查点、输出和文件均持久保存。取消支持在提交响应丢失时按请求编号撤销，提前到达的取消也阻止延迟提交。
- worker 退出/重启后，执行中的任务标记为 `interrupted`，等待用户主动继续；不会自动再发模型请求计费。排队任务在 worker 恢复后正常执行。
- 已生成文件在失败或取消后保留。工具参数严格验证，不执行模型提供的 Shell/Python、任意路径或自定义网络目标。网页读取交给既有网关的公网地址校验。
- 模型可多轮调用工具，保留 DeepSeek 所需的 `reasoning_content` 以回传模型，但 UI 和管理列表不返回思考正文。

工具执行是实际操作。`create_artifact` 成功意味着文件已在 SQLite 中保存，可通过下载接口获取；文件名不是服务器路径。

## 独立部署边界

| 目录 / 进程 | 职责 | 能访问的数据 |
| --- | --- | --- |
| `app/` | Android 客户端，直连或服务端执行方式 | 本机加密数据、对应用户的远端任务 |
| `server/` 公共 API | 用户认证、任务提交、查询与取回文件 | Agent 数据库 |
| `server/` worker | 持久队列、模型与工具执行 | Agent 数据库、模型与联网凭证 |
| `server/` 管理 API | 用户、配额、配置、任务取消与审计 | Agent 数据库；单独端口和管理凭证 |
| `admin/` | 独立管理站点与 BFF | 仅通过管理 API；不挂载 Agent 数据库 |
| `community/` | 原有社区、搜索和网页读取网关 | 原社区数据库；不导入 Agent 模块 |

`admin/` 可以独立复制、构建、部署到另一台机器；不引用 `app/`、`server/` 或 `community/` 的源代码、依赖或数据库。公开 Agent API 不注册 `/admin/v1/*` 路由。

## 附件上传与图片识别

服务端 Agent 模式允许在墨聊中通过回形针选择附件；Android 手机直连模型模式暂不支持附件。每条消息最多引用3份附件，每份最大8MiB。

- Word `.docx`：提取段落与表格；Excel `.xlsx`：读取所有工作表的部分行列数据，自动截断超长内容。旧格式 `.doc/.xls` 需转换后上传。
- PNG/JPEG/GIF/WebP：经服务器使用 `MOZHI_VISION_MODEL=deepseek-flash` 进行图像理解和 OCR，后续任务使用识别结果。视觉模型费用在上传时产生；失败会返回错误，不会静默跳过。
- `POST /v1/agent/attachments`：`multipart/form-data`，字段为 `file`，返回 `{id,name,kind,characters}`。在 `POST /v1/agent/runs` 提交时传入可选的 `attachments: ["id"]`；所有ID必须属于当前用户。服务器只存提取的文本/识别结果，不保留原文件和图片二进制；识别文本仍作为用户私有内容保存在数据库中。
- 解析后的上下文最多每份24000字符；图片OCR及文档解析是信息提取，不是无限制电子表格计算引擎。若需要精准分析整表，应分页或增加后续的结构化数据分析工具。
- 上传表内提示/图片中的文字一律视作不可信资料，不能提升工具权限。数据库附件记录在后续上传时清理30天之前的数据，现阶段不提供独立附件删除界面。

## API

除 `/health` 外，公共 API 均要求 `Authorization: Bearer <用户凭证>`。用户由管理后台创建，凭证仅显示一次，数据库保存 SHA-256 摘要。

| 方法 / 路径 | 行为 |
| --- | --- |
| GET `/health` | 进程与数据库存活 |
| GET `/v1/me` | 当前用户信息 |
| GET `/v1/agent/status` | worker、模型配置与工具状态 |
| POST `/v1/agent/runs` | 保存并排队任务，返回任务对象（HTTP 200） |
| GET `/v1/agent/runs?before=&limit=` | 当前用户任务列表，游标分页 |
| GET `/v1/agent/runs/{id}` | 状态、计划、结果、文件元信息、用量 |
| GET `/v1/agent/runs/{id}/events?after=0` | 顺序读取执行事件，最多100条；不返回思考正文 |
| POST `/v1/agent/runs/{id}/cancel` | 幂等取消，已有文件保留 |
| POST `/v1/agent/runs/{id}/resume` | 仅从中断状态继续；保留已用轮次和工具预算 |
| GET `/v1/agent/requests/{client_request_id}` | 提交响应丢失后的查询 |
| POST `/v1/agent/requests/{client_request_id}/cancel` | 持久取消此请求，即使提交尚未到达 |
| GET `/v1/agent/artifacts/{id}` | 当前用户文件内容与元信息 |
| GET `/v1/agent/artifacts/{id}/download` | 认证下载 UTF-8 文件 |

提交示例：

```json
{
  "client_request_id": "550e8400-e29b-41d4-a716-446655440000",
  "conversation_id": "my-conversation-1",
  "messages": [{"role": "user", "content": "计算1000条数据每条0.02元、100次接通每次0.15元的总成本，生成CSV"}],
  "background": "我按人民币统计成本。",
  "allow_web": false
}
```

历史消息只接受 `user`、`assistant` 文字。背景是用户选择上传的资料，不是高优先级指令。网络关闭时在服务端同步禁用搜索和读网页。

状态：`queued → running → completed / failed / cancelled / interrupted`。`interrupted → queued` 需要主动恢复。失败后如需重新开始，用新的请求编号提交。模型响应丢失后人工恢复可能重复一次模型费用，不能承诺第三方调用 exactly-once；文件操作以任务和轮次内工具编号幂等。

## 管理接口

管理 API 是另一个 ASGI 工厂 `mozhi_service.api:create_admin_api`，使用独立监听端口。仅独立后台的 BFF 持有 `MOZHI_ADMIN_API_TOKEN`，不能填入 Android，也不能放进前端构建变量。

- `GET /admin/v1/overview`：计数与服务状态。
- `GET /admin/v1/users?q=&offset=&limit=`、`POST /admin/v1/users`、`PATCH /admin/v1/users/{id}`。
- `POST /admin/v1/users/{id}/rotate-token`：撤销旧凭证，同时取消未完成任务。
- `GET/PUT /admin/v1/config`：全量校验，使用 revision 防止覆盖其他管理员修改。
- `GET /admin/v1/runs?state=&user_id=&offset=&limit=`、`POST /admin/v1/runs/{id}/cancel`。
- `GET /admin/v1/audit?before=`：管理变更；不记录凭证、聊天正文或生成文件。

初版管理员为运营者单账号，不提供多管理员角色分工。管理页面只显示任务运行元信息，不提供浏览全部私人聊天的入口。

## 数据与能力边界

- 当前是单主机、SQLite WAL、单 worker 的邀请内测架构，不把数据库文件放在网络共享盘，也不扩容 worker 副本。进程锁阻止第二个 worker 误接管任务。
- 每任务默认最多10轮模型请求、20次工具调用、300秒。最多10个文件，每文件10万字符，每用户文件合计2000万字符。
- 服务端持有运营者的模型 API Key。用户仅持个人服务凭证，不能传入任意模型地址或工具代码。
- 本次不提供任意代码执行、SSH、数据库直连、邮件发送、自动下单、定时运行模型任务或手机控制。这些需要另接受限执行器与授权流程。
- 服务端保存上传上下文、任务检查点和文件。数据库文件与备份需使用主机访问控制与磁盘保护；本版没有数据库字段加密。
- Android 服务端模式发送当前对话与所选背景，不自动全量同步本机记忆/历史/技能，也不会自动写回本机记忆或决定。原手机 Agent 模式保留这些本机功能。
- 本版用户为独立的 Agent 内测账号，不自动合并原匿名社区身份，不含短信登录或账号找回。

运行与部署见 [DEPLOY.md](DEPLOY.md)。参考协议：[DeepSeek Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/)、[Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode/)。
