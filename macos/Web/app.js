/* Local renderer only. Every network/file operation goes through the native allowlisted bridge. */
'use strict';
const C = MozhiCore;
const $ = id => document.getElementById(id);
const E = C.escape;
const icons = {
  chat:'<path d="M20 11a8 8 0 0 1-8 8H5l-3 3V11a9 9 0 0 1 18 0Z"/><path d="M7 10h8M7 14h5"/>',
  discover:'<circle cx="12" cy="12" r="9"/><path d="m16 8-2 6-6 2 2-6Z"/>',
  decision:'<path d="M9 4H4v16h16v-5M10 10l3 3 8-9"/>',
  me:'<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>',
  attach:'<path d="m8 13 7-7a3 3 0 0 1 4 4L9 20a5 5 0 0 1-7-7L13 2M6 15l9-9"/>',
  send:'<path d="M12 19V5m-6 6 6-6 6 6"/>',
  web:'<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a20 20 0 0 1 0 18 20 20 0 0 1 0-18Z"/>',
  travel:'<path d="m3 5 6-2 6 2 6-2v16l-6 2-6-2-6 2Zm6-2v16m6-14v16"/>',
  reminder:'<path d="M5 17h14l-2-3V9a5 5 0 0 0-10 0v5ZM10 21h4M12 2v2"/>',
  copy:'<rect x="8" y="8" width="12" height="13" rx="2"/><path d="M16 8V3H3v13h5"/>',
  share:'<path d="M12 15V2m-4 4 4-4 4 4M7 10H4v11h16V10h-3"/>',
  like:'<path d="M7 10v10H3V10Zm0 0 5-8c3 0 3 3 1 7h6c2 0 3 1 2 4l-2 7H7"/>',
  dislike:'<path d="M7 14V4H3v10Zm0 0 5 8c3 0 3-3 1-7h6c2 0 3-1 2-4l-2-7H7"/>',
  tasks:'<rect x="4" y="3" width="16" height="19" rx="2"/><path d="M8 8h8M8 12h8M8 16h5"/>',
  download:'<path d="M12 3v12m-4-4 4 4 4-4M4 16v5h16v-5"/>',
  trash:'<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7"/>',
};
const icon = name => '<svg viewBox="0 0 24 24" aria-hidden="true">' + (icons[name] || icons.chat) + '</svg>';
let state = C.defaults(), config = {connected:false,base:''}, selected = null, page = 'chat';
let generation = 0, saving = Promise.resolve(), locked = false, uploading = false, storageError = false, polling = false;
let health = null, toastTimer;
const drafts = new Map();
const bridge = async (action, payload = {}) => {
  if (!window.webkit?.messageHandlers?.mozhi) throw new Error('请使用墨知 Mac 应用打开。网页预览不连接真实服务。');
  return window.webkit.messageHandlers.mozhi.postMessage({action,payload});
};
async function api(path, method = 'GET', body) {
  const version = generation;
  const value = await bridge('api', {path,method,...(body ? {body} : {})});
  if (version !== generation) throw new Error('账号已改变，请重新操作。');
  return value;
}
const current = () => state.chats.find(c => c.id === selected);
const hasWork = chat => chat?.pending || ['queued','running','submitting','uncertain','interrupted'].includes(chat?.run?.state);
function toast(text) {clearTimeout(toastTimer);$('toast').textContent = text;$('toast').hidden = false;toastTimer = setTimeout(() => {$('toast').hidden = true;},3500);}
function banner(text) {$('error-banner').textContent = text || '';$('error-banner').hidden = !text;}
function save() {
  if (!config.connected || storageError) return Promise.reject(new Error('本机存储不可用，请先连接服务或处理存储错误。'));
  const payload = {scope:config.scope,state:JSON.parse(JSON.stringify(state))};
  saving = saving.catch(() => {}).then(() => bridge('save',payload));
  saving.catch(error => banner('记录保存失败：' + error.message + '。请保持应用打开，整理后重试。'));
  return saving;
}
function saveLater() {if (config.connected && !storageError) save().catch(() => {});}
function rememberDraft() {drafts.set(selected || 'new',$('prompt').value);}
function newChat() {
  if (locked || uploading) return;
  rememberDraft();selected = null;page = 'chat';render();$('prompt').focus();
}
function ensureChat() {
  if (!current()) {
    selected = crypto.randomUUID();state.chats.unshift({id:selected,title:'新的想法',created:Date.now(),updated:Date.now(),messages:[],attachments:[]});
    drafts.set(selected,drafts.get('new') || $('prompt').value);drafts.delete('new');
  }
  return current();
}
function selectChat(id) {if (uploading || locked) return;rememberDraft();selected = id;page = 'chat';render();}
function renderSidebar() {
  $('nav').innerHTML = [['chat','墨聊','chat'],['discover','发现','discover'],['decision','决定','decision'],['me','我','me']].map(([id,title,glyph]) =>
    '<button class="nav-button ' + (page === id ? 'active' : '') + '" data-page="' + id + '">' + icon(glyph) + title + '</button>').join('');
  const search = $('history-search').value.toLowerCase();
  const chats = [...state.chats].sort((a,b) => b.updated-a.updated).filter(c => c.title.toLowerCase().includes(search));
  $('chat-count').textContent = state.chats.length;
  $('history').innerHTML = chats.length ? chats.map(c => '<button class="history-item ' + (c.id === selected && page === 'chat' ? 'active' : '') + '" data-chat="' + E(c.id) + '" title="' + E(c.title) + '">' + (hasWork(c) ? '◦ ' : '') + E(c.title) + '</button>').join('') : '<p class="history-empty">' + (search ? '没有匹配的对话' : '你的想法会留在这里') + '</p>';
  $('account-name').textContent = config.connected ? config.nickname : '连接墨知';
  $('account-status').textContent = config.connected ? '本机加密保存' : '从这里开始';
  $('connection').textContent = health?.worker_ready && health?.model_configured ? '服务已就绪' : config.connected ? '已保存连接' : '未连接';
  $('connection').classList.toggle('online',!!(health?.worker_ready && health?.model_configured));
}
function welcome() {
  return '<div class="welcome"><img class="welcome-mascot" src="motuan.png" alt="小墨团"><div class="eyebrow">一点灵感 · 一点清晰</div><h2>把想法，慢慢想清楚。</h2><p class="intro">无论是一个小念头，还是一个重要的决定，<br>都可以从这里聊起。</p><div class="suggestions">' +
    [['travel','计划一次出游','把时间留给喜欢的地方','帮我计划一次周末出游，先了解我的出发地、时间和预算。'],['decision','梳理一个选择','一起看清真正重要的事','我正在面对一个选择，请帮我梳理目标、选项和取舍。'],['reminder','记下一件事','让重要的小事有着落','我想创建一个提醒，请先和我确认内容、时间和是否重复。']].map(([glyph,title,desc,prompt]) => '<button class="suggestion" data-prompt="' + E(prompt) + '">' + icon(glyph) + '<b>' + title + '</b><small>' + desc + '</small></button>').join('') + '</div><p class="welcome-foot">想到哪里，就从哪里开始。</p></div>';
}
function messageHTML(m) {
  if (m.role === 'user') return '<article class="message user">' + E(m.content) + (m.attachments?.length ? '<div class="message-meta">' + m.attachments.map(a => E(a.name)).join(' · ') + '</div>' : '') + '</article>';
  return '<article class="message assistant"><div class="markdown">' + C.markdown(m.content) + '</div>' + artifactButtons(m.artifacts) + '<div class="message-actions">' +
    [['like','有帮助'],['dislike','没帮助'],['copy','复制回复'],['share','分享到本机发现'],['decision','记为决定']].map(([action,title]) => '<button data-message="' + E(m.id) + '" data-message-action="' + action + '" class="' + (m.feedback === action ? 'selected' : '') + '" title="' + title + '" aria-label="' + title + '">' + icon(action) + '</button>').join('') + '</div></article>';
}
function artifactButtons(items = []) {return (items || []).map(a => '<button class="artifact" data-artifact="' + E(a.id) + '">' + icon('download') + E(a.name) + '</button>').join('');}
function renderChat() {
  const chat = current();
  $('content').innerHTML = chat?.messages.length ? '<div class="messages">' + chat.messages.map(messageHTML).join('') + '</div>' : welcome();
  renderRun();renderComposer();
}
function renderRun() {
  const run = current()?.run;
  if (!run) {$('run-bar').innerHTML = '';return;}
  const busy = ['submitting','queued','running'].includes(run.state);
  let actions = '';
  if (run.id) actions += '<button data-run="events">执行记录</button>';
  if (run.state === 'uncertain') actions += '<button data-run="retry">重试同一请求</button>';
  if (run.state === 'interrupted') actions += '<button data-run="resume">继续任务</button>';
  if (hasWork(current())) actions += '<button data-run="cancel">取消任务</button>';
  $('run-bar').innerHTML = '<div class="run-status"><span class="dot ' + (busy ? 'busy' : '') + '"></span><span>' + E(C.status(run.state)) + (run.rounds ? ' · ' + Number(run.rounds) + ' 轮 · ' + Number(run.calls || 0) + ' 次工具调用' : '') + '</span><div class="run-actions">' + actions + '</div></div>' +
    (run.error ? '<div class="run-error">' + E(run.error) + '</div>' : '') + (run.state !== 'completed' ? artifactButtons(run.artifacts) : '');
}
function renderComposer() {
  const chat = current();
  $('attachment-list').innerHTML = (chat?.attachments || []).map((a,i) => '<span class="chip"><span class="chip-label">' + E(a.name) + '</span><button data-remove-attachment="' + i + '" aria-label="移除附件">×</button></span>').join('');
  $('web-toggle').innerHTML = icon('web') + (state.preferences.allowWeb ? '联网已开' : '联网已关');
  $('web-toggle').classList.toggle('enabled',state.preferences.allowWeb);
  $('send').innerHTML = icon('send');
  $('send').disabled = locked || uploading || storageError || !!hasWork(chat);
  $('attach').disabled = uploading || locked || storageError || !!hasWork(chat) || (chat?.attachments?.length || 0) >= 3;
  $('prompt').disabled = locked || storageError;
  $('upload-state').textContent = uploading ? '正在上传并识别…' : '';
}
function renderDiscovery() {
  $('content').innerHTML = '<div class="page-width"><div class="section-heading"><div><h2>值得留下的对话</h2><p>本机发现 · 仅自己可见，暂未接入多人社区</p></div></div>' + (state.posts.length ? '<div class="cards">' + state.posts.map(post => '<article class="card"><small>我的分享 · ' + date(post.created) + '</small><h3>' + E(post.question) + '</h3><div class="markdown">' + C.markdown(C.limitText(post.answer,320)) + '</div><div class="actions"><button data-post="' + E(post.id) + '" data-post-action="read">阅读全文</button><button data-post="' + E(post.id) + '" data-post-action="delete">删除</button></div></article>').join('') + '</div>' : empty('discover','把有意思的回答，留在这里','在墨知回复下方点击分享，编辑并确认后，就能保存一段只属于你的问答。')) + '</div>';
}
function renderDecisions() {
  $('content').innerHTML = '<div class="page-width"><div class="section-heading"><div><h2>给选择一个落点</h2><p>本机决定 · 保存你的选择，以及当时的理由</p></div><button class="secondary" data-action="add-decision">＋ 记一个决定</button></div>' +
    (state.decisions.length ? '<div class="cards">' + state.decisions.map(d => '<article class="card"><small>' + date(d.created) + ' · ' + (d.done ? '已完成' : '已决定') + '</small><h3>' + E(d.title) + '</h3><p>' + E(d.text) + '</p><div class="actions"><button data-decision="' + E(d.id) + '" data-decision-action="toggle">' + (d.done ? '重新打开' : '标为完成') + '</button><button data-decision="' + E(d.id) + '" data-decision-action="edit">编辑</button><button data-decision="' + E(d.id) + '" data-decision-action="delete">删除</button></div></article>').join('') + '</div>' : empty('decision','想清楚之后，记下你的决定','你可以手动记录，也可以从墨知的回答中摘出一条，修改后保存。')) + '</div>';
}
function renderMe() {
  $('content').innerHTML = '<div class="page-width"><div class="section-heading"><div><h2>' + E(config.nickname || '很高兴，在这里遇见你') + '</h2><p>墨知 Mac · 0.1.0 预览版</p></div><button class="secondary" data-action="settings">连接设置</button></div><div class="info-grid"><article class="card"><h3>我的背景</h3><p>' + E(C.limitText(state.preferences.background || '告诉墨知你的偏好与背景，在每次对话时少一些重复。',130)) + '</p><button class="secondary" data-action="background">编辑背景</button></article><article class="card"><h3>服务端任务</h3><p>查找同一服务账号的任务、结果与文件。服务端暂不返回完整的历史聊天正文。</p><button class="secondary" data-action="remote">查看任务</button></article><article class="card"><h3>手机账号资料</h3><p>取回同一手机号下的手机备份。聊天、决定和分享补充到本机，背景可单独查看。</p><button class="secondary" data-action="archives">取回手机资料</button><button class="secondary" data-action="phone-background">查看手机背景</button></article><article class="card"><h3>提醒与日程</h3><p>查看服务端保存的提醒，或在墨聊中创建。此版尚未接入 Mac 系统通知。</p><button class="secondary" data-action="reminders">查看提醒</button></article><article class="card"><h3>健康观察</h3><p>查看同一账号已保存的饮食、睡眠和日常健康观察记录。</p><button class="secondary" data-action="health">查看记录</button></article></div><p class="help">对话、背景、决定和发现保存在本机加密文件中；用户凭证保存在系统钥匙串。发送消息时会上传本轮需要的对话与背景。手机可在「个人账号」中导入备份，再从 Mac 取回；两端之后的编辑不会自动同步。</p></div>';
}
function empty(glyph,title,text) {return '<div class="empty">' + icon(glyph) + '<h2>' + E(title) + '</h2><p>' + E(text) + '</p></div>';}
function date(value) {return new Date(value || Date.now()).toLocaleDateString('zh-CN',{month:'long',day:'numeric'});}
function render() {
  renderSidebar();
  const titles = {chat:'墨聊',discover:'发现',decision:'决定',me:'我'};
  $('page-title').textContent = page === 'chat' && current()?.messages.length ? current().title : titles[page];
  $('page-subtitle').textContent = page === 'chat' ? '留一点空间，给正在发生的生活' : '让每一次思考，都有所积累';
  $('composer-section').hidden = page !== 'chat';$('export-chat').hidden = page !== 'chat' || !current()?.messages.length;
  $('delete-chat').hidden = page !== 'chat' || !current();$('delete-chat').disabled = !!hasWork(current()) || locked || uploading;
  if (page === 'chat') {renderChat();$('prompt').value = drafts.get(selected || 'new') || '';}
  if (page === 'discover') renderDiscovery();if (page === 'decision') renderDecisions();if (page === 'me') renderMe();
}
function scrollBottom() {$('content').scrollTop = $('content').scrollHeight;}
async function send() {
  if (locked || uploading || hasWork(current())) return;
  if (!config.connected) {openSettings();return;}
  const chat = ensureChat();
  const text = $('prompt').value.trim() || (chat.attachments?.length ? '请帮我分析所附文件。' : '');
  if (!text) return;
  locked = true;renderComposer();
  try {
    const id = crypto.randomUUID();
    const body = C.payload(chat,text,chat.attachments || [],state.preferences,id);
    const message = {id:crypto.randomUUID(),role:'user',content:text,created:Date.now(),requestID:id,sendable:false,attachments:chat.attachments || []};
    chat.pending = body;chat.run = {state:'submitting',client_request_id:id};chat.messages.push(message);
    chat.attachments = [];chat.updated = Date.now();
    if (chat.messages.length === 1) chat.title = C.limitText(text,24);
    await save(); // No remote side effect until request identity is durable.
    drafts.delete(chat.id);$('prompt').value = '';render();scrollBottom();
    try {const run = await api('/v1/agent/runs','POST',body);C.applyRun(chat,run);}
    catch (error) {chat.run.state = 'uncertain';chat.run.error = '尚未确认服务端是否接收：' + error.message + '。可查询或重试同一请求。';}
    await save();
  } catch (error) {if (chat.pending) {chat.run.state = 'uncertain';chat.run.error = '本机记录尚未保存，重试前会再次保存。';}banner(error.message);}
  finally {locked = false;render();scrollBottom();}
}
async function poll() {
  if (polling || locked || !config.connected || storageError) return;
  polling = true;const version = generation;
  try {
    const chats = state.chats.filter(c => c.pending || ['queued','running'].includes(c.run?.state));
    for (const chat of chats) {
      if (version !== generation) break;
      try {
        const path = chat.run?.id ? '/v1/agent/runs/' + chat.run.id : '/v1/agent/requests/' + chat.pending.client_request_id;
        const run = await api(path);
        const changed = run.state !== chat.run?.state || run.updated !== chat.run?.updated;
        if (!changed) continue;
        C.applyRun(chat,run);await save();
        if (page === 'chat' && selected === chat.id) {
          const nearBottom = $('content').scrollHeight - $('content').scrollTop - $('content').clientHeight < 100;
          const scroll = $('content').scrollTop;renderChat();$('content').scrollTop = scroll;if (nearBottom) scrollBottom();
        }
        renderSidebar();
      } catch { /* Query failures never resubmit a request or discard a pending identity. */ }
    }
  } finally {polling = false;}
}
async function runAction(action) {
  const chat = current();if (!chat?.run || locked) return;
  if (action === 'events') {await showEvents(chat.run);return;}
  locked = true;renderComposer();
  try {
    let run;
    if (action === 'retry' && chat.pending) {await save();run = await api('/v1/agent/runs','POST',chat.pending);}
    else if (action === 'resume' && chat.run.state === 'interrupted') run = await api('/v1/agent/runs/' + chat.run.id + '/resume','POST');
    else if (action === 'cancel') run = await api('/v1/agent/requests/' + (chat.pending?.client_request_id || chat.run.client_request_id) + '/cancel','POST');
    if (run) {C.applyRun(chat,run);await save();}
  } catch (error) {toast(error.message);}
  finally {locked = false;renderRun();renderComposer();renderSidebar();}
}
async function attach() {
  if (!config.connected) {openSettings();return;}
  const chat = ensureChat();uploading = true;renderComposer();const version = generation;
  try {
    const result = await bridge('attachments',{remaining:3-(chat.attachments?.length || 0)});
    if (version !== generation) return;
    chat.attachments = [...(chat.attachments || []),...result.items];await save();
    if (result.errors?.length) banner(result.errors.join('\n'));
  } catch (error) {toast(error.message);}
  finally {uploading = false;renderComposer();renderSidebar();}
}
function modal(title, html, footer = '') {
  $('modal-content').innerHTML = '<div class="modal-title"><h2>' + E(title) + '</h2><button data-close aria-label="关闭">×</button></div>' + html + '<div id="modal-error" class="modal-error" role="alert"></div>' + (footer ? '<div class="modal-footer">' + footer + '</div>' : '');
  if (!$('modal').open) $('modal').showModal();
}
function closeModal() {if (!locked) $('modal').close();}
function modalFailure(error) {if ($('modal-error')) $('modal-error').textContent = error.message;else toast(error.message);}
function needConnection() {if (config.connected) return true;openSettings();return false;}
function openSettings() {
  if (locked || uploading) return;
  modal('手机号账号', '<p class="help">手机号 + 验证码登录，首次使用自动注册。</p>'+ '<label class="field"><span>手机号</span><input id="account-phone" type="tel" autocomplete="tel" placeholder="请输入手机号"></label><label class="field"><span>短信验证码</span><input id="account-code" inputmode="numeric" autocomplete="one-time-code" maxlength="6"></label><button class="secondary" id="phone-code">获取验证码</button><label class="checkbox"><input id="bind-legacy" type="checkbox" '+(config.connected && !config.phoneMasked ? 'checked' : '')+'>绑定当前服务账号，保留原任务和文件</label><p class="help">Mac 与手机共用服务端。这里填写个人服务凭证，模型由服务端统一配置。</p><label class="field"><span>服务地址</span><input id="server-base" type="url" value="' + E(config.base) + '" placeholder="https://api.example.com" autocomplete="off"><small>如服务有路径前缀，请填写完整 API 根地址。</small></label><label class="field"><span>个人服务凭证</span><input id="server-token" type="password" autocomplete="off" placeholder="' + (config.connected ? '留空保留当前凭证（同一服务地址）' : '粘贴管理后台发放的用户凭证') + '"><small>验证账号后保存到 Mac 钥匙串。</small></label>',
    (config.connected ? '<button class="secondary danger" id="logout">断开账号</button>' : '') + '<button class="secondary" id="connect">凭证连接（旧版）</button><button class="primary" id="phone-verify">手机号登录</button>');
  let challenge = '', sentPhone = '', sentBase = '', retryAt = 0;
  $('bind-legacy').disabled = !config.connected || !!config.phoneMasked;
  $('phone-code').onclick = async () => {
    if (locked) return;
    if (Date.now() < retryAt) {modalFailure(new Error('请等待 60 秒后再获取验证码。'));return;}
    const phone = $('account-phone').value.trim(), base = $('server-base').value.trim();
    locked = true;$('phone-code').disabled = true;
    try {const result = await bridge('phoneCode',{base,phone});challenge = result.challenge_id;sentPhone = phone;sentBase = base;retryAt = Date.now() + 60000;toast('验证码已发送，有效期 5 分钟');}
    catch(error){modalFailure(error);}finally{locked = false;$('phone-code').disabled = false;}
  };
  $('phone-verify').onclick = async () => {
    if (locked) return;
    const phone = $('account-phone').value.trim(), base = $('server-base').value.trim();
    if (!challenge || sentPhone !== phone || sentBase !== base) {modalFailure(new Error('请先为此手机号获取验证码。'));return;}
    locked = true;$('phone-verify').disabled = true;
    try {
      await saving.catch(() => {});
      const next = await bridge('phoneVerify',{base,phone,code:$('account-code').value,challenge,bindLegacy:$('bind-legacy').checked});
      generation++;config = next.config;state = C.hydrate(next.state);selected = null;health = null;storageError = false;drafts.clear();banner('');
      locked = false;$('modal').close();render();checkStatus();toast('已登录 '+config.phoneMasked);
    }catch(error){modalFailure(error);}finally{locked = false;if($('phone-verify')) $('phone-verify').disabled = false;renderComposer();}
  };
  $('connect').onclick = async () => {
    $('connect').disabled = true;locked = true;
    try {
      await saving.catch(() => {});
      const next = await bridge('configure',{base:$('server-base').value,token:$('server-token').value});
      generation++;config = next.config;state = C.hydrate(next.state);selected = null;health = null;storageError = false;drafts.clear();banner('');
      $('server-token').value = '';locked = false;$('modal').close();render();checkStatus();toast('已连接 ' + config.nickname);
    } catch (error) {modalFailure(error);}
    finally {locked = false;if ($('connect')) $('connect').disabled = false;renderComposer();}
  };
  if ($('logout')) $('logout').onclick = async () => {
    modal('断开当前账号？','<p class="help">本机会移除服务凭证并隐藏此账号的记录。加密记录保留，重新连接同一账号后可继续查看。已提交的服务端任务继续执行。</p>', '<button class="secondary" data-close>返回</button><button class="primary" id="confirm-logout">断开账号</button>');
    $('confirm-logout').onclick = async () => {
      try {await saving.catch(() => {});await bridge('logout');generation++;state = C.defaults();config = {connected:false,base:''};selected = null;health = null;drafts.clear();$('modal').close();render();}
      catch (error) {modalFailure(error);}
    };
  };
}
async function showArchives() {
  if (!needConnection()) return;
  modal('手机账号资料','<p class="help">正在读取…</p>');
  try {
    const result = await api('/v1/account/archives');
    modal('选择手机备份','<p class="help">仅补充本机没有的聊天、决定和分享，不覆盖现有内容。账号保留最近 5 份备份。</p><div class="detail-list">'+result.items.map(a=>'<div class="detail-item">'+date(a.created)+' · '+a.total+' 条 <button data-archive="'+E(a.id)+'">取回</button></div>').join('')+(result.items.length?'':'<p class="help">请先在手机「个人账号」中导入本机资料。</p>')+'</div>');
    for (const button of document.querySelectorAll('[data-archive]')) button.onclick = async () => {
      if (locked) return;locked = true;button.disabled = true;
      try {
        let rows = [], bytes = 0;
        for(let seq=0;seq<=50000;seq++) {
          const part = await api('/v1/account/archives/'+button.dataset.archive+'/chunks?seq='+seq);
          if(!part.items.length) break;
          bytes += new TextEncoder().encode(JSON.stringify(part.items)).length;
          if(bytes>24*1024*1024||rows.length+part.items.length>50000) throw new Error('备份过大');
          rows.push(...part.items);
        }
        const next = C.importAndroid(state, rows);state = next.state;await save();locked = false;$('modal').close();render();toast('已补充 '+next.added+' 条资料；手机背景可在「我」中查看');
      }catch(error){modalFailure(error);}finally{locked = false;button.disabled = false;}
    };
  }catch(error){modalFailure(error);}
}
function showPhoneBackground() {
  modal('手机背景与技能','<p class="help">这是导入时的副本，不会自动加入 Mac 对话背景。需要时可复制到「我的背景」。</p><div class="detail-list">'+(state.phoneMemories||[]).map(m=>'<article class="detail-item"><b>'+E(m.title)+'</b><div class="markdown">'+C.markdown(m.text)+'</div></article>').join('')+'</div>');
}
function openBackground() {
  if (!needConnection()) return;
  modal('我的背景', '<label class="field"><span>你希望墨知了解的背景与偏好</span><textarea id="background" maxlength="16000" rows="7" placeholder="例如：我的工作、长期目标、回答偏好…">' + E(state.preferences.background) + '</textarea><small>随每次请求发送至服务端。请只填写你愿意用于对话的信息。</small></label><label class="field"><span>提醒时区</span><input id="timezone" value="' + E(state.preferences.timezone) + '" placeholder="Asia/Shanghai"></label><label class="checkbox"><input type="checkbox" id="allow-personal" ' + (state.preferences.allowPersonal ? 'checked' : '') + '>允许本轮 Agent 使用健康观察工具</label>', '<button class="primary" id="save-background">保存</button>');
  $('save-background').onclick = async () => {
    try {
      const zone = $('timezone').value.trim();new Intl.DateTimeFormat('zh-CN',{timeZone:zone}).format();
      state.preferences.background = $('background').value;state.preferences.timezone = zone;state.preferences.allowPersonal = $('allow-personal').checked;await save();$('modal').close();render();toast('背景已保存');
    } catch (error) {modalFailure(error);}
  };
}
function openDecision(existing, source = '') {
  if (!needConnection()) return;
  modal(existing ? '编辑决定' : '记下你的决定', '<label class="field"><span>你选择了什么？</span><input id="decision-title" maxlength="100" value="' + E(existing?.title || '') + '" placeholder="用一句话写下你的选择"></label><label class="field"><span>理由与后续行动</span><textarea id="decision-text" rows="6" maxlength="12000">' + E(existing?.text || source) + '</textarea></label><p class="help">先改成你自己的选择，再保存。助手的建议不会自动成为你的决定。</p>', '<button class="primary" id="save-decision">保存决定</button>');
  $('save-decision').onclick = async () => {
    const title = $('decision-title').value.trim();if (!title) {modalFailure(new Error('请填写你的选择。'));return;}
    const value = {...existing,id:existing?.id || crypto.randomUUID(),title,text:$('decision-text').value.trim(),created:existing?.created || Date.now(),done:existing?.done || false};
    if (existing) state.decisions[state.decisions.findIndex(d => d.id === existing.id)] = value;else state.decisions.unshift(value);
    try {await save();$('modal').close();if (page === 'decision') renderDecisions();toast('决定已保存');}catch(error){modalFailure(error);}
  };
}
async function messageAction(id, action) {
  const chat = current(), m = chat?.messages.find(m => m.id === id);if (!m) return;
  if (action === 'copy') {await bridge('copy',{text:m.content});toast('已复制');}
  else if (['like','dislike'].includes(action)) {m.feedback = m.feedback === action ? null : action;await save();renderChat();}
  else if (action === 'decision') openDecision(null,m.content);
  else if (action === 'share') {
    const index = chat.messages.indexOf(m);const question = [...chat.messages.slice(0,index)].reverse().find(m => m.role === 'user')?.content || chat.title;
    modal('保存一段有意思的问答','<span class="label">仅自己可见 · 本机发现</span><label class="field"><span>问题</span><textarea id="share-question" maxlength="3000">' + E(C.limitText(question,3000)) + '</textarea></label><label class="field"><span>回答</span><textarea id="share-answer" maxlength="50000" rows="6">' + E(C.limitText(m.content,50000)) + '</textarea></label><p class="help">只保存这里显示的问答，不包含隐藏背景或工具记录。</p>','<button class="primary" id="save-share">保存到本机发现</button>');
    $('save-share').onclick = async () => {
      const q = $('share-question').value.trim(), a = $('share-answer').value.trim();if (!q || !a) {modalFailure(new Error('问题和回答不能为空。'));return;}
      state.posts.unshift({id:crypto.randomUUID(),question:q,answer:a,created:Date.now()});
      try {await save();$('modal').close();toast('已保存到本机发现');}catch(error){modalFailure(error);}
    };
  }
}
async function confirmRemove(kind,id) {
  modal('删除这条本机记录？','<p class="help">只删除当前 Mac 保存的这一条记录。</p>', '<button class="secondary" data-close>返回</button><button class="primary" id="confirm-remove">删除</button>');
  $('confirm-remove').onclick = async () => {state[kind] = state[kind].filter(x => x.id !== id);try {await save();$('modal').close();render();}catch(error){modalFailure(error);}};
}
async function checkStatus() {
  if (!config.connected) return;
  try {health = await api('/v1/agent/status');renderSidebar();if (!health.worker_ready || !health.model_configured) banner('服务已连接，但执行进程或模型尚未就绪，请联系服务管理员。');}
  catch (error) {health = null;renderSidebar();banner('暂时无法验证服务状态：' + error.message);}
}
async function showEvents(run) {
  modal('执行记录','<p class="help">正在读取…</p>');
  try {
    const result = await api('/v1/agent/runs/' + run.id + '/events?after=0');
    modal('执行记录','<p class="help">' + E(C.status(run.state)) + ' · ' + E(run.id.slice(0,8)) + '</p><div class="detail-list">' +
      (run.plan || []).map(p => '<div class="plan-step">' + E(typeof p === 'string' ? p : (p.title || p.text || JSON.stringify(p))) + '</div>').join('') +
      result.items.map(e => '<div class="event"><time>' + E(new Date(e.created).toLocaleTimeString()) + '</time>' + E(e.message) + '</div>').join('') + (result.items.length ? '' : '<p class="help">还没有执行事件。</p>') + '</div>' + (result.items.length === 100 ? '<p class="help">显示最早 100 条事件。</p>' : ''));
  } catch (error) {modalFailure(error);}
}
let runCursor = 0;
async function showRemote(before = 0) {
  if (!needConnection()) return;
  modal('服务端任务','<p class="help">正在同步…</p>');
  try {
    const result = await api('/v1/agent/runs?limit=20' + (before ? '&before=' + before : ''));runCursor = result.next_before;
    modal('服务端任务','<p class="help">同一账号所有设备提交的任务。仅查看结果，不会自动补全手机的聊天记录。</p><div class="detail-list">' + result.items.map(r => '<div class="detail-item"><b>' + E(C.status(r.state)) + '</b> · ' + date(r.created) + '<small>任务 ' + E(r.id.slice(0,12)) + '</small><button data-remote-run="' + E(r.id) + '">查看结果</button></div>').join('') + (result.items.length ? '' : '<p class="help">还没有服务端任务。</p>') + '</div>', (before ? '<button class="secondary" id="run-first">最新任务</button>' : '') + (runCursor ? '<button class="secondary" id="run-next">更早任务</button>' : ''));
    if ($('run-next')) $('run-next').onclick = () => showRemote(runCursor);if ($('run-first')) $('run-first').onclick = () => showRemote();
  } catch (error) {modalFailure(error);}
}
async function showRemoteRun(id) {
  try {
    const run = await api('/v1/agent/runs/' + id);
    modal('任务结果', '<p class="help">' + E(C.status(run.state)) + ' · ' + date(run.created) + '</p><div class="detail-list"><div class="markdown">' + C.markdown(run.output || run.error || '任务尚未产生回复，可稍后刷新。') + '</div>' + artifactButtons(run.artifacts) + '</div>', '<button class="secondary" id="run-refresh">刷新</button><button class="secondary" id="remote-events">执行记录</button>' + (run.state === 'interrupted' ? '<button class="primary" id="remote-resume">继续任务</button>' : '') + (['queued','running','interrupted'].includes(run.state) ? '<button class="secondary" id="remote-cancel">取消任务</button>' : ''));
    $('run-refresh').onclick = () => showRemoteRun(id);$('remote-events').onclick = () => showEvents(run);
    for (const action of ['resume','cancel']) if ($('remote-' + action)) $('remote-' + action).onclick = async () => {
      $('remote-' + action).disabled = true;try {await api('/v1/agent/runs/' + id + '/' + action,'POST');showRemoteRun(id);}catch(error){modalFailure(error);}
    };
  } catch(error){modalFailure(error);}
}
async function showRecords(type) {
  if (!needConnection()) return;
  modal(type === 'reminders' ? '提醒与日程' : '健康观察','<p class="help">正在读取…</p>');
  try {
    const result = await api(type === 'reminders' ? '/v1/reminders' : '/v1/health-records');
    modal(type === 'reminders' ? '提醒与日程' : '健康观察','<p class="help">' + (type === 'reminders' ? '在墨聊中创建或修改提醒。本版不发送 Mac 系统通知。' : '显示服务端返回的最近一页观察记录。') + '</p><div class="detail-list">' + (result.items || []).map(r => '<div class="detail-item"><b>' + E(r.title || r.subject || r.category || '观察记录') + '</b><small>' + E(r.start_at || r.occurred_at || r.timezone || '') + '</small><p>' + E(r.body || r.text || '') + '</p></div>').join('') + ((result.items || []).length ? '' : '<p class="help">还没有记录。</p>') + '</div>');
  } catch(error){modalFailure(error);}
}
async function exportChat() {
  const chat = current();if (!chat?.messages.length) return;
  const text = '# ' + chat.title + '\n\n' + chat.messages.map(m => '## ' + (m.role === 'user' ? '我' : '墨知') + '\n\n' + m.content).join('\n\n');
  const r = await bridge('export',{name:'墨知-' + chat.title + '.md',text});if (r.saved) toast('对话已导出');
}
document.addEventListener('click', async event => {
  const el = event.target.closest('button,a');if (!el) return;
  try {
    if (el.matches('[data-close]')) {closeModal();return;}
    if (el.matches('[data-artifact]')) {event.preventDefault();const r = await bridge('download',{id:el.dataset.artifact});if (r.saved) toast('文件已保存');return;}
    if (el.tagName === 'A') {event.preventDefault();const target = C.link(el.getAttribute('href'));if (target?.url) await bridge('external',{url:target.url});return;}
    if (el.dataset.page && !locked && !uploading) {rememberDraft();page = el.dataset.page;render();}
    if (el.dataset.chat) selectChat(el.dataset.chat);
    if (el.dataset.prompt) {$('prompt').value = el.dataset.prompt;rememberDraft();$('prompt').focus();}
    if (el.dataset.removeAttachment !== undefined && !uploading && !locked) {current().attachments.splice(Number(el.dataset.removeAttachment),1);await save();renderComposer();}
    if (el.dataset.messageAction) await messageAction(el.dataset.message,el.dataset.messageAction);
    if (el.dataset.run) await runAction(el.dataset.run);
    if (el.dataset.remoteRun) await showRemoteRun(el.dataset.remoteRun);
    if (el.dataset.action) {
      const f = {settings:openSettings,background:openBackground,remote:showRemote,archives:showArchives,'phone-background':showPhoneBackground,reminders:()=>showRecords('reminders'),health:()=>showRecords('health'),'add-decision':()=>openDecision()};
      if (f[el.dataset.action]) await f[el.dataset.action]();
    }
    if (el.dataset.postAction) {
      const p = state.posts.find(x => x.id === el.dataset.post);
      if (el.dataset.postAction === 'delete') await confirmRemove('posts',p.id);
      else modal(p.question,'<span class="label">仅自己可见</span><div class="detail-list markdown">' + C.markdown(p.answer) + '</div>');
    }
    if (el.dataset.decisionAction) {
      const d = state.decisions.find(x => x.id === el.dataset.decision);
      if (el.dataset.decisionAction === 'delete') await confirmRemove('decisions',d.id);
      else if (el.dataset.decisionAction === 'edit') openDecision(d);
      else {d.done = !d.done;await save();renderDecisions();}
    }
  } catch(error) {toast(error.message);}
});
$('new-chat').onclick = newChat;$('account').onclick = openSettings;$('send').onclick = send;$('attach').onclick = attach;
$('history-search').oninput = renderSidebar;$('remote-runs').onclick = () => showRemote();$('export-chat').onclick = () => exportChat().catch(e => toast(e.message));
$('delete-chat').onclick = () => {
  const chat = current();if (!chat || hasWork(chat) || locked || uploading) return;
  modal('删除这段本机对话？','<p class="help">本机聊天将被删除。此操作不会删除已提交到服务端的任务、文件，也不会删除你另存的发现或决定。需要保留时，请先导出对话。</p>','<button class="secondary" data-close>返回</button><button class="primary" id="confirm-delete-chat">删除本机对话</button>');
  $('confirm-delete-chat').onclick = async () => {
    state.chats = state.chats.filter(c => c.id !== chat.id);
    try {await save();drafts.delete(chat.id);selected = null;$('modal').close();render();}catch(error){modalFailure(error);}
  };
};
$('web-toggle').onclick = () => {state.preferences.allowWeb = !state.preferences.allowWeb;saveLater();renderComposer();};
$('prompt').oninput = rememberDraft;
$('prompt').onkeydown = event => {if (event.key === 'Enter' && (event.metaKey || event.ctrlKey) && !event.isComposing) {event.preventDefault();send();}};
$('modal').addEventListener('cancel',event => {if (locked) event.preventDefault();});
window.desktopAction = action => {if (action === 'settings') openSettings();if (action === 'new') newChat();};
for (const [id,glyph] of [['attach','attach'],['remote-runs','tasks'],['export-chat','download'],['delete-chat','trash']]) $(id).innerHTML = icon(glyph);
async function boot() {
  try {
    const value = await bridge('bootstrap');config = value.config;state = C.hydrate(value.state);
    if (value.storageError) {storageError = true;banner(value.storageError);}
  } catch(error) {banner(error.message);storageError = true;}
  render();if (config.connected) {checkStatus();poll();}
}
boot();setInterval(poll,3000);
