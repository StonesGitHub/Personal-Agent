(function (root) {
  'use strict';
  const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const limitText = (value, limit) => {
    let out = '';
    for (const c of String(value)) { if (out.length + c.length > limit) break; out += c; }
    return out;
  };
  function link(value) {
    try {
      const u = new URL(value);
      if (u.protocol === 'mozhi:' && u.hostname === 'artifact' && /^[a-f0-9]{32}$/.test(u.searchParams.get('id') || ''))
        return {artifact: u.searchParams.get('id')};
      if (u.protocol === 'mozhi:' && u.hostname === 'map' && u.searchParams.get('name'))
        return {url: 'https://maps.apple.com/?q=' + encodeURIComponent([u.searchParams.get('city'),u.searchParams.get('name'),u.searchParams.get('address')].filter(Boolean).join(' '))};
      if (['https:', 'http:'].includes(u.protocol) && u.hostname && !u.username && !u.password) return {url: u.href};
    } catch {}
    return null;
  }
  function inline(text) {
    const tokens = [];
    let html = escape(text).replace(/`([^`]+)`/g, (_, code) => {
      tokens.push('<code>' + code + '</code>'); return '\u0000' + (tokens.length - 1) + '\u0000';
    });
    html = html.replace(/\[([^\]\n]+)\]\(([^\s)]+)\)/g, (_, label, escapedURL) => {
      const decoded = escapedURL.replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>');
      const target = link(decoded);
      if (!target) return label;
      tokens.push(target.artifact ? '<a href="#" data-artifact="' + target.artifact + '">' + label + '</a>' :
        '<a href="' + escape(target.url) + '" rel="noreferrer">' + label + '</a>');
      return '\u0000' + (tokens.length - 1) + '\u0000';
    });
    html = html.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>').replace(/~~([^~\n]+)~~/g, '<s>$1</s>');
    return html.replace(/\u0000(\d+)\u0000/g, (_, i) => tokens[Number(i)] || '');
  }
  function markdown(text) {
    const lines = String(text ?? '').replace(/\u0000/g, '').replace(/\r\n/g, '\n').split('\n');
    let out = [], paragraph = [], code = null, list = null;
    const closeList = () => { if (list) {out.push('</' + list + '>'); list = null;} };
    const flush = () => {if (paragraph.length) {out.push('<p>' + paragraph.map(inline).join('<br>') + '</p>');paragraph = [];} };
    const cells = line => line.trim().replace(/^\||\|$/g, '').split('|').map(v => v.trim());
    for (let i = 0; i < lines.length; i++) {
      let line = lines[i];
      if (/^\s*```/.test(line)) {
        flush(); closeList();
        if (code !== null) {out.push('<pre><code>' + escape(code.join('\n')) + '</code></pre>');code = null;}
        else code = [];
        continue;
      }
      if (code !== null) {code.push(line);continue;}
      if (line.includes('|') && i + 1 < lines.length && /^\s*\|?\s*:?-{3,}:?\s*\|/.test(lines[i + 1])) {
        flush();closeList();
        out.push('<div class="table-wrap"><table><thead><tr>' + cells(line).map(v => '<th>' + inline(v) + '</th>').join('') + '</tr></thead><tbody>');
        i += 2;
        while (i < lines.length && lines[i].includes('|') && lines[i].trim()) {
          out.push('<tr>' + cells(lines[i]).map(v => '<td>' + inline(v) + '</td>').join('') + '</tr>');i++;
        }
        i--;out.push('</tbody></table></div>');continue;
      }
      if (!line.trim()) {flush();closeList();continue;}
      const heading = /^(#{1,6})\s+(.+)$/.exec(line);
      const item = /^\s*(?:([-*])|\d+\.)\s+(.+)$/.exec(line);
      if (heading) {flush();closeList();let n = Math.min(heading[1].length + 1, 6);out.push('<h' + n + '>' + inline(heading[2]) + '</h' + n + '>');}
      else if (item) {flush();let type = item[1] ? 'ul' : 'ol';if (list !== type) {closeList();list = type;out.push('<' + type + '>');}out.push('<li>' + inline(item[2]) + '</li>');}
      else if (/^>\s?/.test(line)) {flush();closeList();out.push('<blockquote>' + inline(line.replace(/^>\s?/, '')) + '</blockquote>');}
      else {closeList();paragraph.push(line);}
    }
    flush();closeList();if (code !== null) out.push('<pre><code>' + escape(code.join('\n')) + '</code></pre>');
    return out.join('');
  }
  const defaults = () => ({schema:1,chats:[],posts:[],decisions:[],preferences:{background:'',allowWeb:true,allowPersonal:true,timezone:'Asia/Shanghai'}});
  function hydrate(value) {
    const d = defaults();
    if (!value || typeof value !== 'object' || Array.isArray(value)) return d;
    if (value.schema && value.schema !== 1) throw new Error('记录来自更新版本，请先升级客户端。');
    for (const k of ['chats','posts','decisions','phoneMemories']) if (Array.isArray(value[k])) d[k] = value[k];
    d.preferences = {...d.preferences, ...value.preferences};
    return d;
  }
  function payload(chat, userText, attachments, preferences, requestID) {
    const content = String(userText).trim();
    if (!content || content.length > 20000) throw new Error('每条消息需要 1–20000 个字符。');
    if (attachments.length > 3 || attachments.some(a => !/^[a-f0-9]{32}$/.test(a.id))) throw new Error('附件无效，请重新选择。');
    let used = content.length, messages = [];
    // A failed/unconfirmed user turn must not be silently replayed as another task.
    const history = chat.messages.filter(m => ['user','assistant'].includes(m.role) && m.sendable !== false);
    for (let i = history.length - 1; i >= 0 && messages.length < 79; i--) {
      const m = history[i];
      const clipped = m.content.length > 20000 ? limitText(m.content, 19940) + '\n[较长的历史回复已截断]' : m.content;
      if (used + clipped.length > 48000) break;
      if (!clipped.trim()) continue;
      used += clipped.length;messages.unshift({role:m.role,content:clipped});
    }
    while (messages.length && messages[0].role !== 'user') messages.shift();
    messages.push({role:'user',content});
    return {client_request_id:requestID,conversation_id:chat.id,messages,
      background:limitText(preferences.background || '',16000),allow_web:!!preferences.allowWeb,
      allow_personal_data:!!preferences.allowPersonal,builtin_skills:['travel','reminders','health'],
      timezone:preferences.timezone || 'Asia/Shanghai',attachments:attachments.map(a => a.id)};
  }
  const terminal = state => ['completed','failed','cancelled','interrupted'].includes(state);
  const status = state => ({submitting:'正在提交',uncertain:'等待确认接收',queued:'等待执行',running:'正在执行',completed:'已完成',failed:'任务失败',cancelled:'已取消',interrupted:'执行中断'}[state] || state || '等待连接');
  function applyRun(chat, run) {
    chat.run = {...chat.run,...run};
    if (run.state === 'completed' && run.output) {
      const existing = chat.messages.find(m => m.runId === run.id && m.role === 'assistant');
      if (existing) existing.content = run.output;
      else chat.messages.push({id:'answer-' + run.id,role:'assistant',content:run.output,runId:run.id,created:run.updated,sendable:true,artifacts:run.artifacts || []});
      const user = chat.messages.find(m => m.requestID === run.client_request_id && m.role === 'user');
      if (user) user.sendable = true;
    }
    if (terminal(run.state)) delete chat.pending;
    chat.updated = run.updated || Date.now();
  }
  function importAndroid(current, rows) {
    if (!Array.isArray(rows) || rows.length > 50000) throw new Error('备份格式无效');
    const state = hydrate(JSON.parse(JSON.stringify(current))), seen = new Set();
    state.phoneMemories = state.phoneMemories || [];let added = 0;
    for (const r of rows) {
      if (!r || typeof r.id !== 'string' || !r.id || r.id.length>100 || seen.has(r.id) || typeof r.kind !== 'string' || !r.data || typeof r.data !== 'object') throw new Error('备份记录编号或内容无效');
      seen.add(r.id);
    }
    const byConversation = new Map();
    for (const m of rows.filter(r=>r.kind==='message')) {
      if (!byConversation.has(m.parent)) byConversation.set(m.parent,[]);
      byConversation.get(m.parent).push(m);
    }
    for (const r of rows.filter(r=>r.kind==='conversation')) {
      const id = 'phone-'+r.id;let chat = state.chats.find(c=>c.id===id);
      if (!chat) {chat = {id,title:String(r.data.title||'手机对话'),created:r.created,updated:r.created,messages:[]};state.chats.push(chat);added++;}
      const existing = new Set(chat.messages.map(m=>m.id));
      for (const m of byConversation.get(r.id)||[]) {
        if (existing.has('phone-'+m.id)||!['user','assistant'].includes(m.data.role)) continue;
        chat.messages.push({id:'phone-'+m.id,role:m.data.role,content:String(m.data.text||''),created:m.created,sendable:!['pending','interrupted','cancelled','error'].includes(m.data.status)});added++;
      }
      chat.messages.sort((a,b)=>a.created-b.created);
    }
    for (const r of rows) {
      const id='phone-'+r.id,d=r.data;
      if (r.kind==='decision'&&!state.decisions.some(x=>x.id===id)) {state.decisions.push({id,title:String(d.title||'手机决定'),text:[d.note,d.outcome].filter(Boolean).join('\n\n'),created:r.created});added++;}
      if (r.kind==='share'&&!state.posts.some(x=>x.id===id)) {state.posts.push({id,question:String(d.question||''),answer:String(d.answer||''),created:r.created});added++;}
      if (['memory','skill'].includes(r.kind)&&!state.phoneMemories.some(x=>x.id===id)) {state.phoneMemories.push({id,title:r.kind==='memory'?'手机背景':String(d.name||d.title||'手机技能'),text:String(d.text||d.body||d.instructions||d.prompt||''),created:r.created});added++;}
    }
    return {state,added};
  }
  const api = {escape,limitText,link,markdown,defaults,hydrate,payload,terminal,status,applyRun,importAndroid};
  root.MozhiCore = api;
  if (typeof module !== 'undefined') module.exports = api;
})(globalThis);
