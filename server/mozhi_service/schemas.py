import re
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, StrictBool, model_validator
from .settings import TOOLS
from .skills import IDS
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class Message(Input):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=20000)


class RunInput(Input):
    client_request_id: str = Field(pattern=r"^[a-zA-Z0-9_-]{16,80}$")
    conversation_id: str = Field(default="", max_length=80, pattern=r"^[a-zA-Z0-9_-]*$")
    messages: list[Message] = Field(min_length=1, max_length=80)
    background: str = Field(default="", max_length=16000)
    allow_web: StrictBool = True
    builtin_skills: list[Literal[*IDS]] = Field(default_factory=lambda: list(IDS), max_length=len(IDS))
    timezone: str = Field(default="Asia/Shanghai", max_length=80)
    allow_personal_data: StrictBool = True
    attachments: list[str] = Field(default_factory=list, max_length=3)

    @model_validator(mode="after")
    def valid_messages(self):
        try:
            ZoneInfo(self.timezone)
        except (ZoneInfoNotFoundError, ValueError):
            raise ValueError("Invalid IANA timezone") from None
        if len(set(self.attachments)) != len(self.attachments) or any(not re.fullmatch(r"[a-f0-9]{32}", aid) for aid in self.attachments):
            raise ValueError("Invalid attachment IDs")
        if self.messages[-1].role != "user" or sum(len(m.content) for m in self.messages) > 50000:
            raise ValueError("Last message must be user; total content limit is 50000 characters")
        return self


class UserCreate(Input):
    nickname: str = Field(min_length=1, max_length=50)
    daily_run_limit: int = Field(default=50, ge=0, le=10000, strict=True)
    enabled_tools: list[Literal[*TOOLS]] = Field(default_factory=lambda: list(TOOLS), max_length=len(TOOLS))


class UserPatch(Input):
    nickname: str | None = Field(default=None, min_length=1, max_length=50)
    status: Literal["active", "suspended"] | None = None
    daily_run_limit: int | None = Field(default=None, ge=0, le=10000, strict=True)
    enabled_tools: list[Literal[*TOOLS]] | None = Field(default=None, max_length=len(TOOLS))


class RuntimeConfig(Input):
    model: str = Field(min_length=1, max_length=100, pattern=r"^[a-zA-Z0-9_./:-]+$")
    max_rounds: int = Field(ge=1, le=20, strict=True)
    max_tool_calls: int = Field(ge=0, le=40, strict=True)
    run_timeout_seconds: int = Field(ge=30, le=900, strict=True)
    enabled_tools: list[Literal[*TOOLS]] = Field(max_length=len(TOOLS))


class ConfigUpdate(Input):
    revision: int = Field(ge=1, strict=True)
    config: RuntimeConfig
