# 墨知独立管理后台

本目录可单独复制和部署，**不导入其他项目目录、不共用数据库、不把管理页面打入 Android 或 Agent API 镜像**。

## 页面

- 用户管理：搜索、分页、新建、昵称、启停、每日额度、每用户工具权限、重置用户凭证。
- 任务记录：状态筛选、轮次/工具调用/Token统计、取消未完成任务。
- Agent 配置：模型ID、轮次、工具次数、超时、全局工具开关，revision 防覆盖。
- 操作记录：用户与配置变更的时间、动作、对象和字段。

中文界面适配桌面和手机；空数据、加载、错误、登录过期均有对应状态。名称等服务端文本通过 `textContent` / DOM文本节点渲染，不能执行 HTML。凭证仅在创建/重置弹窗显示，关闭即清除，不写入浏览器持久存储。

## 结构

| 文件 | 职责 |
| --- | --- |
| `app.py` | 管理员登录、会话、CSRF、限制路由的 BFF 代理 |
| `static/index.html` / `app.js` / `style.css` | 无构建步骤的独立前端 |
| `requirements.txt` | 本服务自己的依赖 |
| `Dockerfile` / `compose.yaml` | 与执行服务同机、不同 Docker 项目部署 |
| `compose.remote.yaml` | 部署到另一台机器，无共享 Docker 网络依赖 |
| `scripts/init-env.py` / `deploy.sh` | 初始化和部署 |

浏览器 → 本站点 `/api/*` → 私有管理 API。内部管理凭证仅存在 BFF 环境，不返回浏览器。后端API合约是唯一跨项目依赖，路径为 `/admin/v1/{overview,users,runs,config,audit}`。

## 启动

Python3.12、单 worker：

```sh
python3 scripts/init-env.py
# 在 .env 中填写 API 地址、内部凭证、后台完整 HTTPS Origin
sh scripts/deploy.sh
# 部署在其他主机时改用：
sh scripts/deploy.sh --remote
```

默认监听主机127.0.0.1:8093。生产环境需要 HTTPS；默认 Secure Cookie 不会在普通 HTTP 中发送。仅本机开发可以配置 `MOZHI_ADMIN_ORIGIN=http://127.0.0.1:端口` 和 `MOZHI_ADMIN_SECURE_COOKIE=0`，代码拒绝对非本机域名使用不安全 Cookie。

环境变量见 `.env.example`。管理账号密码用 PBKDF2-SHA256 310000轮，不保存原文；Cookie使用随机会话标识，8小时过期，退出立即撤销，进程重启会退出所有登录。初版单管理员，不支持多实例共享会话。

管理API不能作为公共API直接开放。异机部署通过私网/VPN或受限HTTPS连接，按完整部署交接配置入口。

## 单独测试

```sh
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements-test.txt
python -m pytest -q tests
```

测试使用模拟管理API；即使没有 `server/`、`app/` 和 `community/` 目录，也可以运行后台及测试。


## 0.7.0 新工具配置

用户编辑与Agent配置已加入加载内置技能、地图链接、创建/查看/修改/取消提醒、保存/查看/删除健康观察的开关。旧用户和既有全局配置不会默认获得新增工具，需要管理员在两处启用。后台仍只展示用户、权限和任务元信息，不加入用户健康记录正文页面。
