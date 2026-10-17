const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const {pathToFileURL} = require('node:url');

(async () => {
  const output = path.resolve(__dirname,'../test-results');fs.mkdirSync(output,{recursive:true});
  const browser = await chromium.launch({headless:true,...(process.env.MOZHI_TEST_CHROME ? {executablePath:process.env.MOZHI_TEST_CHROME} : {})});
  const page = await browser.newPage({viewport:{width:1220,height:820},deviceScaleFactor:1});
  const errors = [];page.on('pageerror', e => errors.push(e.message));
  let saved = {}, who = 'one', failOnce = false, denyLookup = false, submits = [];
  const runs = new Map();
  const config = () => ({connected:true,base:'https://example.com',scope:who,nickname:who === 'one' ? '测试账号' : '第二账号'});
  const clone = obj => JSON.parse(JSON.stringify(obj));
  await page.exposeFunction('__testBridge', async ({action,payload:p}) => {
    if (action === 'bootstrap') return {config:config(),state:clone(saved)};
    if (action === 'save') {assert.equal(p.scope,who);saved = clone(p.state);return {ok:true};}
    if (action === 'phoneCode') {assert.equal(p.phone,'13800138000');return {challenge_id:'a'.repeat(32)};}
    if (action === 'phoneVerify') {assert.equal(p.code,'123456');return {config:{...config(),phoneMasked:'138****8000'},state:clone(saved)};}
    if (action === 'configure') {who = 'two';saved = {};return {config:config(),state:{}};}
    if (action === 'attachments') return {items:[{id:'a'.repeat(32),name:'旅行预算.xlsx',kind:'document',characters:99}],errors:[]};
    if (action === 'copy') return {ok:true};
    if (action === 'download' || action === 'export') return {saved:true};
    if (action !== 'api') throw new Error('Unexpected action: ' + action);
    if (p.path === '/v1/account/archives') return {items:[{id:'a'.repeat(32),created:1,total:2}]};
    if (p.path.startsWith('/v1/account/archives/')) return {items:p.path.endsWith('seq=0')?[{id:'phone-conv',kind:'conversation',parent:'',created:1,data:{title:'旧手机记录'}},{id:'phone-msg',kind:'message',parent:'phone-conv',created:2,data:{role:'user',text:'这是旧聊天'}}]:[]};
    if (p.path === '/v1/agent/status') return {worker_ready:true,model_configured:true};
    if (p.path === '/v1/agent/runs' && p.method === 'POST') {
      const request = p.body;
      assert.ok(saved.chats.some(c => c.pending?.client_request_id === request.client_request_id),'request identity must be saved before remote submission');
      submits.push(clone(request));
      if (!runs.has(request.client_request_id)) runs.set(request.client_request_id,{id:String(runs.size+1).padStart(32,'b'),client_request_id:request.client_request_id,state:'queued',rounds:0,calls:0,updated:Date.now(),artifacts:[],plan:[]});
      if (failOnce) {failOnce = false;throw new Error('simulated lost submit response');}
      return clone(runs.get(request.client_request_id));
    }
    if (p.path.startsWith('/v1/agent/requests/')) {
      if (denyLookup) throw new Error('simulated offline');
      const request = p.path.split('/')[4], run = runs.get(request);
      if (!run) throw new Error('not found');
      if (p.path.endsWith('/cancel')) {run.state = 'cancelled';run.updated++;}
      return clone(run);
    }
    if (p.path.startsWith('/v1/agent/runs/')) {
      const id = p.path.split('/')[4];const run = [...runs.values()].find(r => r.id === id);
      if (p.path.endsWith('/events?after=0')) return {items:[{created:Date.now(),message:'完成受控测试',kind:'completed'}]};
      if (!run) throw new Error('not found');return clone(run);
    }
    if (p.path.startsWith('/v1/agent/runs?')) return {items:[...runs.values()],next_before:0};
    throw new Error('Unexpected path: ' + p.path);
  });
  await page.addInitScript(() => {window.webkit = {messageHandlers:{mozhi:{postMessage:body => window.__testBridge(body)}}};});
  try {
    await page.goto(pathToFileURL(path.resolve(__dirname,'../Web/index.html')).href);
    await page.waitForSelector('.welcome');
    assert.equal(await page.locator('#error-banner').isVisible(),false);
    await page.screenshot({path:path.join(output,'home.png')});
    await page.setViewportSize({width:1024,height:650});
    const fits = await page.evaluate(() => document.querySelector('.suggestions').getBoundingClientRect().bottom <= document.getElementById('content').getBoundingClientRect().bottom);
    assert.equal(fits,true,'starter cards must fit in a compact Mac window');
    await page.setViewportSize({width:1220,height:820});
    await page.locator('#attach').click();await page.waitForSelector('.chip');
    await page.locator('#prompt').fill('请帮我比较周末去城市博物馆和城市湖畔的安排。');
    await page.evaluate(() => {send();send();});
    await page.waitForFunction(() => document.getElementById('run-bar').textContent.includes('等待执行'));
    assert.equal(submits.length,1);assert.deepEqual(submits[0].attachments,['a'.repeat(32)]);
    const first = [...runs.values()][0];first.state = 'completed';first.updated++;first.output = '## 周末安排\n\n先选一个更符合你当前需要的地方。\n\n| 选择 | 更适合 |\n| --- | --- |\n| 城市博物馆 | 雨天、文化散步 |\n| 城市湖畔 | 晴天、户外放松 |\n\n**我的建议：** 确认天气和预约后，再做决定。';
    await page.evaluate(() => poll());await page.waitForSelector('.message.assistant');
    assert.equal(await page.locator('.message.assistant').count(),1);
    await page.evaluate(() => poll());assert.equal(await page.locator('.message.assistant').count(),1);
    await page.screenshot({path:path.join(output,'chat.png')});
    await page.locator('[data-message-action="share"]').click();await page.locator('#save-share').click();
    await page.locator('[data-page="discover"]').click();await page.waitForSelector('.card');
    assert.match(await page.locator('#content').textContent(),/本机发现/);assert.equal(saved.posts.length,1);
    await page.locator('[data-page="decision"]').click();await page.locator('[data-action="add-decision"]').click();
    await page.locator('#decision-title').fill('周六先去博物馆');await page.locator('#decision-text').fill('先确认预约和天气。');await page.locator('#save-decision').click();
    await page.waitForSelector('[data-decision-action="toggle"]');assert.equal(saved.decisions.length,1);
    // A lost response and a user retry keep exactly the same request identity and payload.
    await page.locator('#new-chat').click();denyLookup = true;failOnce = true;
    await page.locator('#prompt').fill('这条请求的首次响应会丢失');await page.locator('#send').click();
    await page.waitForSelector('[data-run="retry"]');const lost = clone(submits.at(-1));
    await page.locator('[data-run="retry"]').click();
    await page.waitForFunction(() => document.getElementById('run-bar').textContent.includes('等待执行'));
    assert.deepEqual(submits.at(-1),lost);assert.equal(runs.size,2);
    const beforeReload = submits.length;const second = runs.get(lost.client_request_id);second.state = 'completed';second.output = '已找回原任务';second.updated++;denyLookup = false;
    await page.reload();await page.waitForSelector('.history-item');await page.locator('.history-item').first().click();
    await page.waitForFunction(() => document.getElementById('content').textContent.includes('已找回原任务'));
    assert.equal(submits.length,beforeReload,'restart must query rather than resubmit');
    // Switch account and verify private records disappear from the renderer.
    await page.locator('#account').click();await page.locator('#server-token').fill('different-account');await page.locator('#connect').click();
    await page.waitForFunction(() => document.getElementById('account-name').textContent === '第二账号');
    assert.equal(await page.locator('.history-item').count(),0);
    await page.locator('[data-page="discover"]').click();assert.equal(await page.locator('.card').count(),0);
    await page.locator('#account').click();await page.locator('#account-phone').fill('13800138000');await page.locator('#phone-code').click();
    await page.waitForFunction(()=>!locked);await page.locator('#account-code').fill('123456');
    await page.screenshot({path:path.join(output,'phone-login.png')});await page.locator('#phone-verify').click();
    await page.waitForFunction(()=>!document.getElementById('modal').open);
    await page.evaluate(()=>showArchives());await page.locator('[data-archive]').click();
    await page.waitForFunction(()=>!document.getElementById('modal').open);assert.equal(saved.chats.length,1);
    await page.evaluate(()=>showArchives());await page.locator('[data-archive]').click();
    await page.waitForFunction(()=>!document.getElementById('modal').open);assert.equal(saved.chats.length,1);assert.equal(saved.chats[0].messages.length,1);
    assert.deepEqual(errors,[]);
    console.log('UI passed: startup, attachments, duplicate send, completion, Markdown tables, local sharing, decisions, lost response retry, restart recovery, account isolation');
  } finally {await browser.close();}
})().catch(error => {console.error(error);process.exit(1);});
