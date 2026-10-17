from dataclasses import dataclass
import os
from urllib.parse import urlsplit

TOOLS = ("update_plan", "calculate", "create_artifact", "list_artifacts", "read_artifact", "web_search", "read_url", "load_skill", "map_links", "create_reminder", "list_reminders", "update_reminder", "cancel_reminder", "save_health_record", "list_health_records", "delete_health_record")
DEFAULT_CONFIG = {
    "model": "deepseek-flash", "max_rounds": 10, "max_tool_calls": 20,
    "run_timeout_seconds": 300, "enabled_tools": list(TOOLS),
}


def service_url(value, *, allow_http=False):
    u = urlsplit(value)
    if u.scheme not in ({"http", "https"} if allow_http else {"https"}) or not u.hostname or u.username or u.password or u.query or u.fragment:
        raise ValueError("Service URL must be an absolute HTTPS URL without credentials or query")
    return value.rstrip("/")


@dataclass
class Settings:
    db_path: str = "data/agent.sqlite3"
    model_base: str = "https://api.deepseek.com"
    model_key: str = ""
    model: str = "deepseek-flash"
    admin_token: str = ""
    web_base: str = ""
    web_token: str = ""
    vision_model: str = "deepseek-flash"

    auth_secret: str = ""
    sms_access_key: str = ""
    sms_access_secret: str = ""
    sms_sign: str = ""
    sms_template: str = ""
    sms_daily_limit: int = 100
    new_account_run_limit: int = 20

    @classmethod
    def env(cls):
        web = os.getenv("MOZHI_WEB_BASE_URL", "")
        return cls(
            auth_secret=os.getenv("MOZHI_AUTH_SECRET", ""),
            sms_access_key=os.getenv("MOZHI_SMS_ACCESS_KEY_ID", ""),
            sms_access_secret=os.getenv("MOZHI_SMS_ACCESS_KEY_SECRET", ""),
            sms_sign=os.getenv("MOZHI_SMS_SIGN_NAME", ""),
            sms_template=os.getenv("MOZHI_SMS_TEMPLATE_CODE", ""),
            sms_daily_limit=max(1, int(os.getenv("MOZHI_SMS_DAILY_LIMIT", "100"))),
            new_account_run_limit=max(1, int(os.getenv("MOZHI_NEW_ACCOUNT_RUN_LIMIT", "20"))),
            db_path=os.getenv("MOZHI_AGENT_DB", "data/agent.sqlite3"),
            model_base=service_url(os.getenv("MOZHI_MODEL_BASE_URL", "https://api.deepseek.com")),
            model_key=os.getenv("MOZHI_MODEL_API_KEY", ""), model=os.getenv("MOZHI_MODEL", "deepseek-flash"),
            admin_token=os.getenv("MOZHI_ADMIN_API_TOKEN", ""),
            web_base=service_url(web, allow_http=True) if web else "", web_token=os.getenv("MOZHI_WEB_TOKEN", ""),
            vision_model=os.getenv("MOZHI_VISION_MODEL", "deepseek-flash"),
        )
