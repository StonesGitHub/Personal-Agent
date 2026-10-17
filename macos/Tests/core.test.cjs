const {test} = require('node:test');
const assert = require('node:assert/strict');
const C = require('../Web/core.js');
const id = 'abc12345-1234-1234-1234-abcdefabcdef';
test('rendered untrusted content never becomes scripts, image requests, or privileged links', () => {
  const html = C.markdown('<img src=x onerror=alert(1)>\n<script>alert(1)</script>\n\n[x](javascript:alert%281%29) [local](file:///etc/passwd) [safe](https://example.com/?x=1&y=2)');
  assert.ok(!html.includes('<script>'));assert.ok(!html.includes('<img'));assert.ok(!html.includes('href="javascript:'));assert.ok(!html.includes('href="file:'));
  assert.ok(html.includes('href="https://example.com/?x=1&amp;y=2"'));
});
test('artifact links are isolated from external URLs', () => {
  assert.deepEqual(C.link('mozhi://artifact?id=' + 'a'.repeat(32)),{artifact:'a'.repeat(32)});
  assert.equal(C.link('mozhi://artifact?id=../../etc'),null);
  assert.equal(C.link('https://user:secret@example.com'),null);
  assert.equal(C.link('data:text/html,<script>'),null);
});
test('Markdown supports tables and escaped fenced code without HTML execution', () => {
  const html = C.markdown('| 名称 | 价格 |\n| --- | --- |\n| A | 10 |\n\n```js\n<a onclick="boom()">\n```');
  assert.ok(html.includes('<table>'));assert.ok(html.includes('<td>10</td>'));assert.ok(html.includes('&lt;a onclick='));
});
test('payload excludes unfinished turns and credentials and obeys server limits', () => {
  const chat = {id,messages:Array.from({length:100},(_,i)=>({role:i%2?'assistant':'user',content:'字'.repeat(19000),sendable:true}))};
  chat.messages.push({role:'user',content:'未成功提交的内容',sendable:false});
  const p = C.payload(chat,'新的问题',[],{...C.defaults().preferences,token:'SECRET',background:'B'.repeat(18000)},id);
  assert.ok(p.messages.length <= 80);assert.ok(p.messages.reduce((n,m)=>n+m.content.length,0) <= 50000);
  assert.ok(p.messages.every(m=>m.content.length <= 20000));assert.equal(p.messages.at(-1).role,'user');
  assert.equal(p.background.length,16000);assert.ok(!JSON.stringify(p).includes('SECRET'));assert.ok(!JSON.stringify(p).includes('未成功提交'));
});
test('Unicode clipping does not split surrogate pairs', () => {
  assert.equal(C.limitText('中😀文',2),'中');assert.equal(C.limitText('中😀文',3),'中😀');
});
test('attachment validation and user-message length fail before submission', () => {
  assert.throws(()=>C.payload({id,messages:[]},'x',[{id:'bad'}],C.defaults().preferences,id));
  assert.throws(()=>C.payload({id,messages:[]},'x'.repeat(20001),[],C.defaults().preferences,id));
});
test('repeated polling or restart recovery only inserts one reply', () => {
  const c = {id,messages:[{id:'u',role:'user',content:'问题',requestID:id,sendable:false}],pending:{client_request_id:id}};
  const run = {id:'b'.repeat(32),client_request_id:id,state:'completed',output:'回答',updated:10,artifacts:[]};
  C.applyRun(c,run);C.applyRun(c,run);assert.equal(c.messages.length,2);assert.equal(c.messages[0].sendable,true);assert.equal(c.pending,undefined);
});
test('interrupted and failed tasks retain usable run IDs and never become successful history', () => {
  const c = {messages:[{role:'user',content:'问题',requestID:id,sendable:false}],pending:{client_request_id:id}};
  C.applyRun(c,{id:'c'.repeat(32),client_request_id:id,state:'interrupted'});
  assert.equal(c.run.state,'interrupted');assert.equal(c.messages[0].sendable,false);assert.equal(c.messages.length,1);
});
test('phone imports merge missing records, retain edits, survive reload and never replay pending jobs', () => {
  const rows = [{id:'c',kind:'conversation',parent:'',created:1,data:{title:'手机对话'}},{id:'m',kind:'message',parent:'c',created:2,data:{role:'user',text:'旧问题',status:'cancelled',remote_payload:'secret'}},{id:'memory',kind:'memory',parent:'',created:2,data:{text:'我的背景'}}];
  let result = C.importAndroid(C.defaults(),rows);assert.equal(result.added,3);
  assert.equal(result.state.chats[0].messages[0].sendable,false);assert.equal(result.state.chats[0].pending,undefined);
  result.state.chats[0].title='本机修改';result=C.importAndroid(C.hydrate(result.state),rows);
  assert.equal(result.added,0);assert.equal(result.state.chats[0].title,'本机修改');assert.equal(result.state.phoneMemories.length,1);
  result=C.importAndroid(result.state,[...rows,{id:'answer',kind:'message',parent:'c',created:3,data:{role:'assistant',text:'旧回答'}}]);
  assert.equal(result.added,1);assert.equal(result.state.chats[0].messages.length,2);
  assert.throws(()=>C.importAndroid(result.state,[rows[0],rows[0]]));
});
