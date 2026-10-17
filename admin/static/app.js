"use strict";
const $ = (id) => document.getElementById(id);
const state = { csrf: "", tab: "users", offset: 0, q: "", filter: "", generation: 0, auditBefore: "" };
const tools = {
  update_plan: "拆解与跟进计划", calculate: "算术计算", create_artifact: "生成文件",
  list_artifacts: "查看文件列表", read_artifact: "读取已有文件", web_search: "联网搜索", read_url: "读取网页",
  load_skill: "加载内置技能", map_links: "生成地图链接", create_reminder: "创建提醒",
  list_reminders: "查看提醒", update_reminder: "修改提醒", cancel_reminder: "取消提醒",
  save_health_record: "保存健康观察", list_health_records: "查看健康观察", delete_health_record: "删除健康观察"
};
const titles = {
  users: ["用户管理", "为每一位用户配置合适的 Agent 能力。"],
  runs: ["任务记录", "关注执行进展、调用用量和需要处理的异常。"],
  config: ["Agent 配置", "调整模型与执行边界，让任务按预期完成。"],
  audit: ["操作记录", "查看用户和配置的管理变更。"]
};
function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
    else if (k === "class") node.className = v;
    else if (k in node) node[k] = v;
    else node.setAttribute(k, v);
  }
  for (const child of children.flat(Infinity)) if (child !== null && child !== undefined) node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  return node;
}
function button(text, action, cls = "secondary") { return el("button", { type: "button", class: cls, onclick: action }, text); }
function notify(text) { $("toast").textContent = text; $("toast").hidden = false; clearTimeout(notify.timer); notify.timer = setTimeout(() => $("toast").hidden = true, 3500); }
function error(text) { $("global-error").textContent = text; $("global-error").hidden = !text; }
function date(value) { return new Date(value).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false }); }
function loggedOut() { state.csrf = ""; state.generation++; $("workspace").hidden = true; $("login-screen").hidden = false; $("view").replaceChildren(); $("stats").replaceChildren(); closeModal(); }
async function api(path, method = "GET", body) {
  const response = await fetch("/api/" + path, {
    method, credentials: "same-origin", headers: { "Content-Type": "application/json", "X-CSRF-Token": state.csrf },
    ...(body === undefined ? {} : { body: JSON.stringify(body) })
  });
  let value;
  try { value = await response.json(); } catch { throw new Error("服务响应无效，请稍后重试"); }
  if (!response.ok) {
    if (response.status === 401 && path !== "login") loggedOut();
    throw new Error(typeof value.detail === "string" ? value.detail : "操作失败，请重试");
  }
  return value;
}
function showWorkspace(session) { state.csrf = session.csrf; $("admin-name").textContent = session.username; $("login-screen").hidden = true; $("workspace").hidden = false; load(); }
$("login-form").addEventListener("submit", async (event) => {
  event.preventDefault(); const form = event.currentTarget, submit = form.querySelector("button"); submit.disabled = true; $("login-error").textContent = "";
  try { const data = Object.fromEntries(new FormData(form)); const session = await api("login", "POST", data); form.elements.password.value = ""; showWorkspace(session); }
  catch (e) { $("login-error").textContent = e.message; }
  finally { submit.disabled = false; }
});
$("logout").onclick = async () => { try { await api("logout", "POST", {}); loggedOut(); } catch (e) { error(e.message); } };
$("refresh").onclick = () => load();
document.querySelectorAll("[data-tab]").forEach((node) => node.onclick = () => {
  state.tab = node.dataset.tab; state.offset = 0; state.q = ""; state.filter = ""; state.auditBefore = "";
  document.querySelectorAll("[data-tab]").forEach((n) => n.classList.toggle("selected", n === node)); load();
});
function badge(value) {
  const names = { active: "使用中", suspended: "已停用", queued: "排队中", running: "执行中", completed: "已完成", failed: "失败", cancelled: "已取消", interrupted: "已中断" };
  const kind = ["running", "queued", "interrupted"].includes(value) ? "warning" : value === "failed" ? "error" : ["suspended", "cancelled"].includes(value) ? "off" : "";
  return el("span", { class: "badge " + kind }, names[value] || value);
}
function renderStats(data) {
  const runs = data.runs;
  const items = [
    ["用户总数", data.users, `${data.active_users} 位用户已启用`, false],
    ["待处理任务", (runs.queued || 0) + (runs.running || 0), `排队 ${runs.queued || 0} · 执行中 ${runs.running || 0}`, true],
    ["已完成任务", runs.completed || 0, "累计完成的聊天与执行任务", false],
    ["执行服务", data.worker_ready ? "运行中" : "未连接", data.model_configured ? "模型凭证已配置" : "等待配置模型凭证", false]
  ];
  $("stats").replaceChildren(...items.map(([label, value, note, emphasis]) => el("div", { class: "stat" + (emphasis ? " emphasis" : "") },
    el("div", { class: "stat-label" }, label, el("span", {}, "↗")), el("div", { class: "stat-value" }, value), el("div", { class: "stat-note" }, note))));
}
async function load() {
  const ticket = ++state.generation, tab = state.tab;
  error(""); $("page-title").textContent = titles[tab][0]; $("page-description").textContent = titles[tab][1]; $("breadcrumb").textContent = "工作空间 / " + titles[tab][0];
  $("view").replaceChildren(el("div", { class: "panel skeleton" }, "正在读取…"));
  const path = tab === "users" ? `users?q=${encodeURIComponent(state.q)}&offset=${state.offset}&limit=20` : tab === "runs" ? `runs?state=${state.filter}&offset=${state.offset}&limit=20` : tab === "audit" ? "audit" + (state.auditBefore ? "?before=" + state.auditBefore : "") : "config";
  try {
    const [overview, data] = await Promise.all([api("overview"), api(path)]);
    if (ticket !== state.generation) return;
    renderStats(overview);
    ({ users: renderUsers, runs: renderRuns, config: renderConfig, audit: renderAudit })[tab](data, overview);
  } catch (e) {
    if (ticket !== state.generation) return;
    $("view").replaceChildren(el("div", { class: "panel empty" }, "暂时无法读取数据。", el("br"), button("重新加载", load))); error(e.message);
  }
}
function panel(title, subtitle, actions, body, footer) {
  return el("div", { class: "panel" }, el("div", { class: "panel-header" }, el("div", {}, el("h2", {}, title), subtitle ? el("p", {}, subtitle) : null), actions), body, footer);
}
function table(headers, rows, emptyText) {
  if (!rows.length) return el("div", { class: "empty" }, emptyText);
  return el("div", { class: "table-wrap" }, el("table", {}, el("thead", {}, el("tr", {}, headers.map((h) => el("th", {}, h)))), el("tbody", {}, rows.map((cells) => el("tr", {}, cells.map((cell) => el("td", {}, cell)))))));
}
function pagination(data) {
  const previous = button("上一页", () => { state.offset = Math.max(0, state.offset - 20); load(); }); previous.disabled = state.offset === 0;
  const next = button("下一页", () => { state.offset += 20; load(); }); next.disabled = state.offset + data.items.length >= data.total;
  return el("div", { class: "pagination" }, `共 ${data.total} 条 · ${data.total ? state.offset + 1 : 0}–${state.offset + data.items.length}`, el("div", { class: "actions" }, previous, next));
}
function renderUsers(data) {
  const search = el("input", { placeholder: "搜索昵称或用户 ID", value: state.q, "aria-label": "搜索用户", onkeydown: (e) => { if (e.key === "Enter") { state.q = search.value.trim(); state.offset = 0; load(); } } });
  const actions = el("div", { class: "actions" }, search, button("搜索", () => { state.q = search.value.trim(); state.offset = 0; load(); }), button("＋ 添加用户", () => editUser(), "primary"));
  const rows = data.items.map((u) => [
    el("div", { class: "user-cell" }, el("div", { class: "avatar" }, Array.from(u.nickname)[0]), el("div", {}, el("div", { class: "user-name" }, u.nickname), el("div", { class: "subtext", title: u.id }, u.id.slice(0, 12) + "…"))),
    badge(u.status), `${u.daily_run_limit} 次 / 天`, `${u.run_count} 次`, date(u.created),
    el("div", { class: "row-actions" }, button("管理", () => editUser(u), "text-button"), button("重置凭证", () => rotate(u), "text-button"))
  ]);
  $("view").replaceChildren(panel("用户列表", "配置用户权限与每日额度", actions, table(["用户", "状态", "任务额度", "累计任务", "创建时间", "操作"], rows, state.q ? "没有匹配的用户，试试其他关键词。" : "还没有用户，添加第一位用户开始使用。"), pagination(data)),
    el("p", { class: "panel-note" }, "用户凭证仅在创建或重置时显示一次。每日额度包含聊天与任务，按北京时间零点重新计算。"));
}
function field(title, attrs) { const input = el("input", attrs); return { input, node: el("label", {}, title, input) }; }
function toolChoices(selected) {
  const nodes = Object.entries(tools).map(([name, title]) => el("label", { class: "tool-option" }, el("input", { type: "checkbox", name: "tool", value: name, checked: selected.includes(name) }), title));
  return el("div", { class: "tools-grid" }, nodes);
}
function checked(container) { return [...container.querySelectorAll("input[name=tool]:checked")].map((n) => n.value); }
function openModal(title, body) { $("modal-title").textContent = title; $("modal-body").replaceChildren(body); if (!$("modal").open) $("modal").showModal(); }
function closeModal() { $("modal").close(); $("modal-body").replaceChildren(); }
$("close-modal").onclick = closeModal;
$("modal").addEventListener("cancel", (e) => { e.preventDefault(); closeModal(); });
function secret(token) {
  openModal("用户凭证已生成", el("div", {}, el("p", { class: "notice" }, "请立即复制，通过私密渠道交给对应用户。关闭后无法再次查看。用户在 App 的“设置 → 执行方式”填写此凭证。"),
    el("code", { class: "secret" }, token), el("div", { class: "form-footer" }, button("完成", closeModal), button("复制凭证", async () => { try { await navigator.clipboard.writeText(token); notify("已复制用户凭证"); } catch { notify("无法自动复制，请手动选择凭证复制"); } }, "primary"))));
}
function editUser(user) {
  const nickname = field("昵称", { value: user?.nickname || "", required: true, maxlength: 50, autocomplete: "off" });
  const quota = field("每日任务额度", { type: "number", value: user?.daily_run_limit ?? 50, min: 0, max: 10000, required: true });
  const status = el("select", {}, el("option", { value: "active" }, "使用中"), el("option", { value: "suspended" }, "已停用")); status.value = user?.status || "active";
  const choices = toolChoices(user?.enabled_tools || Object.keys(tools)), message = el("p", { class: "error", role: "alert" });
  const submit = el("button", { type: "submit", class: "primary" }, user ? "保存修改" : "创建用户");
  const form = el("form", {}, nickname.node, quota.node, user ? el("label", {}, "用户状态", status) : null,
    el("p", { class: "field-help" }, "额度设为 0 将停止接收新任务。停用用户会立即取消其未完成任务。"), el("div", {}, "允许使用的工具"), choices, message,
    el("div", { class: "form-footer" }, button("取消", closeModal), submit));
  form.onsubmit = async (e) => {
    e.preventDefault(); submit.disabled = true; message.textContent = "";
    const body = { nickname: nickname.input.value.trim(), daily_run_limit: Number(quota.input.value), enabled_tools: checked(choices) };
    if (user) body.status = status.value;
    try {
      const result = await api(user ? "users/" + user.id : "users", user ? "PATCH" : "POST", body);
      closeModal(); await load(); if (user) notify("用户设置已保存"); else secret(result.token);
    } catch (e) { message.textContent = e.message; } finally { submit.disabled = false; }
  };
  openModal(user ? "管理用户" : "添加用户", form);
}
function confirmAction(title, text, action) {
  const message = el("p", { class: "error", role: "alert" });
  const run = button("确认", async () => { run.disabled = true; try { await action(); } catch (e) { message.textContent = e.message; run.disabled = false; } }, "danger");
  openModal(title, el("div", {}, el("p", { class: "muted" }, text), message, el("div", { class: "form-footer" }, button("取消", closeModal), run)));
}
function rotate(user) {
  confirmAction("重置用户凭证", `将重置“${user.nickname}”的凭证。旧凭证立即失效，未完成的任务会被取消。`, async () => {
    const result = await api("users/" + user.id + "/rotate-token", "POST", {}); await load(); secret(result.token);
  });
}
function renderRuns(data) {
  const select = el("select", { "aria-label": "筛选任务状态", onchange: () => { state.filter = select.value; state.offset = 0; load(); } },
    ...Object.entries({ "": "全部状态", queued: "排队中", running: "执行中", completed: "已完成", failed: "失败", cancelled: "已取消", interrupted: "已中断" }).map(([v, t]) => el("option", { value: v }, t))); select.value = state.filter;
  const rows = data.items.map((r) => [el("div", {}, el("span", { class: "subtext", title: r.id }, r.id.slice(0, 14) + "…"), r.error ? el("div", { class: "subtext", title: r.error }, r.error.slice(0, 24)) : null),
    r.nickname, badge(r.state), `${r.rounds} / ${r.calls}`, (r.prompt_tokens + r.completion_tokens).toLocaleString(), date(r.created),
    ["queued", "running", "interrupted"].includes(r.state) ? button("取消任务", () => confirmAction("取消任务", "将停止此任务的后续执行。已生成的文件会保留，已发生的模型调用仍可能计费。", async () => { await api("runs/" + r.id + "/cancel", "POST", {}); closeModal(); load(); notify("任务已取消"); }), "text-button") : "—"]);
  $("view").replaceChildren(panel("执行记录", "按任务查看状态与用量", el("div", { class: "actions" }, select), table(["任务 ID / 异常", "用户", "状态", "模型轮次 / 工具", "Token", "创建时间", "操作"], rows, "当前筛选下没有任务。"), pagination(data)),
    el("p", { class: "panel-note" }, "本页显示运行信息。中断的任务由用户在 App 中确认继续；模型用量为服务商返回的统计，网络中断时可能不完整。"));
}
function renderConfig(data, overview) {
  const c = data.config;
  const model = field("模型 ID", { value: c.model, required: true, maxlength: 100, pattern: "[a-zA-Z0-9_./:\\-]+" });
  const rounds = field("每个任务最多模型轮次", { type: "number", value: c.max_rounds, min: 1, max: 20, required: true });
  const calls = field("每个任务最多工具调用", { type: "number", value: c.max_tool_calls, min: 0, max: 40, required: true });
  const timeout = field("任务最长执行时间（秒）", { type: "number", value: c.run_timeout_seconds, min: 30, max: 900, required: true });
  const choices = toolChoices(c.enabled_tools), message = el("p", { class: "error", role: "alert" });
  const submit = el("button", { type: "submit", class: "primary" }, "保存配置");
  const form = el("form", { class: "config-form" }, el("div", { class: "notice" }, "模型与预算修改对新任务生效。关闭工具会在后续调用前生效；同时受用户各自的工具权限约束。"),
    el("div", { class: "config-grid" }, model.node, rounds.node, calls.node, timeout.node), el("h3", {}, "全局工具权限"), choices,
    el("p", { class: "field-help" }, "模型 API 地址、密钥和搜索网关凭证由部署环境管理。此页面不会读取或显示服务密钥。"),
    el("div", { class: "status-strip" }, el("span", {}, `模型凭证：${overview.model_configured ? "已配置" : "未配置"}`), el("span", {}, `搜索网关：${overview.web_configured ? "已配置" : "未配置"}`), el("span", {}, `配置版本：${data.revision}`)),
    message, el("div", { class: "form-footer" }, submit));
  form.onsubmit = async (e) => {
    e.preventDefault(); submit.disabled = true; message.textContent = "";
    try { await api("config", "PUT", { revision: data.revision, config: { model: model.input.value.trim(), max_rounds: Number(rounds.input.value), max_tool_calls: Number(calls.input.value), run_timeout_seconds: Number(timeout.input.value), enabled_tools: checked(choices) } }); await load(); notify("Agent 配置已保存"); }
    catch (e) { message.textContent = e.message; } finally { submit.disabled = false; }
  };
  $("view").replaceChildren(panel("执行策略", "设置所有用户共享的能力边界", null, form));
}
function renderAudit(data) {
  const names = { "user.create": "创建用户", "user.update": "更新用户", "user.rotate_token": "重置用户凭证", "config.update": "更新 Agent 配置", "run.cancel": "取消任务" };
  const rows = data.items.map((r) => [date(r.created), names[r.action] || r.action, el("span", { class: "subtext", title: r.target }, r.target.length > 20 ? r.target.slice(0, 20) + "…" : r.target), r.detail || "—"]);
  const more = button("更早的记录", () => { state.auditBefore = String(data.next_before); load(); }); more.disabled = !data.next_before;
  $("view").replaceChildren(panel("管理操作", "时间均为北京时间", button("最新记录", () => { state.auditBefore = ""; load(); }), table(["时间", "操作", "对象", "变更字段 / 版本"], rows, "暂无操作记录。"), el("div", { class: "pagination" }, `本页 ${data.items.length} 条`, more)));
}
api("session").then(showWorkspace).catch(() => loggedOut());
