"""Shipped methods only; client IDs may restrict, never replace, trusted methods."""
import json
from pathlib import Path
from datetime import datetime
from zoneinfo import ZoneInfo

CATALOG = {s["id"]: s for s in json.loads(Path(__file__).with_name("builtin_skills.json").read_text())["skills"]}
IDS = tuple(CATALOG)
HEALTH_BOUNDARY = "健康相关内容仅提供信息与生活管理支持，不自行确诊、开中药处方或更改药物；分清传统经验与临床证据。不要把助手建议保存为健康事实。"


def enabled(request):
    return [sid for sid in request.get("builtin_skills", IDS) if sid in CATALOG]


def instructions(request):
    zone = request.get("timezone", "Asia/Shanghai")
    clock = datetime.now(ZoneInfo(zone)).isoformat(timespec="seconds")
    available = enabled(request)
    parts = [f"当前服务端时间：{clock}；用户时区：{zone}。解析相对日期以此为准。", HEALTH_BOUNDARY,
             "内置技能是方法说明，不增加工具权限。按需调用 load_skill；不能声称执行了未成功的操作。",
             "已启用技能目录：" + json.dumps([{k: CATALOG[s][k] for k in ("id", "name", "description")} for s in available], ensure_ascii=False)]
    if not request.get("allow_personal_data", True):
        parts.append("本轮关闭了个人背景，不可读取或保存健康记录，仅使用当前对话。")
    query = request["messages"][-1]["content"].lower()
    for sid in available:
        skill = CATALOG[sid]
        if any(word.lower() in query for word in skill["trigger"].split()):
            parts.append("自动加载内置技能：\n" + skill["body"])
    return "\n".join(parts)
