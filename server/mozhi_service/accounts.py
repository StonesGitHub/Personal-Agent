"""Verified phone identities, revocable sessions and private migration snapshots."""
import base64
from datetime import datetime, timezone
from hashlib import sha256, sha1
import hmac
import json
import re
import secrets
from urllib.parse import quote
import uuid

import httpx
from pydantic import BaseModel, Field, ConfigDict

from .settings import TOOLS
from .store import Problem, encode, now

SCHEMA = """
CREATE TABLE IF NOT EXISTS phone_accounts(phone TEXT PRIMARY KEY,user_id TEXT NOT NULL UNIQUE REFERENCES users(id));
CREATE TABLE IF NOT EXISTS auth_sessions(token_hash TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),expires INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS session_user ON auth_sessions(user_id);
CREATE TABLE IF NOT EXISTS sms_challenges(id TEXT PRIMARY KEY,phone TEXT NOT NULL,ip_hash TEXT NOT NULL,code_hash TEXT NOT NULL,created INTEGER NOT NULL,expires INTEGER NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,used INTEGER NOT NULL DEFAULT 0,delivered INTEGER NOT NULL DEFAULT 0);
CREATE INDEX IF NOT EXISTS sms_phone ON sms_challenges(phone,created);
CREATE INDEX IF NOT EXISTS sms_ip ON sms_challenges(ip_hash,created);
CREATE TABLE IF NOT EXISTS account_archives(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),source TEXT NOT NULL,created INTEGER NOT NULL,complete INTEGER NOT NULL DEFAULT 0,total INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS account_archive_chunks(archive_id TEXT NOT NULL REFERENCES account_archives(id) ON DELETE CASCADE,seq INTEGER NOT NULL,body TEXT NOT NULL,count INTEGER NOT NULL,PRIMARY KEY(archive_id,seq));
"""


def phone_number(value):
    value = re.sub(r'[\s()-]', '', value)
    if value.startswith('+86'):
        value = value[3:]
    if not re.fullmatch(r'1[3-9][0-9]{9}', value):
        raise Problem(422, '请输入有效的中国大陆手机号')
    return '+86' + value


def masked(phone):
    return phone[3:6] + '****' + phone[-4:]


class CodeInput(BaseModel):
    model_config = ConfigDict(extra='forbid')
    phone: str = Field(min_length=11, max_length=30)


class VerifyInput(CodeInput):
    challenge_id: str = Field(pattern=r'^[a-f0-9]{32}$')
    code: str = Field(pattern=r'^[0-9]{6}$')
    bind_legacy: bool = False


class ChunkInput(BaseModel):
    model_config = ConfigDict(extra='forbid')
    source: str = Field(pattern=r'^[A-Za-z0-9_-]{16,80}$')
    seq: int = Field(ge=0, le=50000)
    items: list[dict] = Field(min_length=1, max_length=100)


class CompleteInput(BaseModel):
    model_config = ConfigDict(extra='forbid')
    chunks: int = Field(ge=1, le=50000)
    total: int = Field(ge=1, le=50000)


class AliyunSMS:
    """RPC signature documented by Aliyun; HTTPS only, no automatic send retries."""
    def __init__(self, settings):
        self.settings = settings

    def send(self, phone, code):
        s = self.settings
        if not all((s.sms_access_key, s.sms_access_secret, s.sms_sign, s.sms_template)):
            raise Problem(503, '短信注册尚未启用，请联系服务管理员配置短信服务')
        p = dict(Action='SendSms', Version='2017-05-25', Format='JSON', RegionId='cn-hangzhou',
                 AccessKeyId=s.sms_access_key, SignatureMethod='HMAC-SHA1', SignatureVersion='1.0',
                 SignatureNonce=uuid.uuid4().hex, Timestamp=datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
                 PhoneNumbers=phone[3:], SignName=s.sms_sign, TemplateCode=s.sms_template,
                 TemplateParam=encode({'code': code}))
        esc = lambda v: quote(str(v), safe='~')
        query = '&'.join(esc(k) + '=' + esc(v) for k, v in sorted(p.items()))
        signature = hmac.new((s.sms_access_secret + '&').encode(), ('POST&%2F&' + esc(query)).encode(), sha1).digest()
        p['Signature'] = base64.b64encode(signature).decode()
        try:
            with httpx.Client(timeout=10, follow_redirects=False, trust_env=False) as client:
                response = client.post('https://dysmsapi.aliyuncs.com/', data=p)
                response.raise_for_status()
                if response.json().get('Code') != 'OK':
                    raise ValueError('SMS rejected')
        except Exception:
            # Provider messages, request URLs, credentials and phone numbers never reach client/logs.
            raise Problem(503, '短信暂未发送成功，请稍后再试或联系管理员') from None


class Accounts:
    def __init__(self, store, settings, sms=None):
        self.store, self.settings = store, settings
        self.sms = sms or AliyunSMS(settings)
        with store.db() as c:
            c.executescript(SCHEMA)

    def digest(self, text):
        if len(self.settings.auth_secret) < 32:
            raise Problem(503, '短信注册尚未启用，请联系服务管理员配置账号密钥')
        return hmac.new(self.settings.auth_secret.encode(), text.encode(), sha256).hexdigest()

    def send_code(self, phone, ip):
        phone = phone_number(phone)
        ident, code, timestamp = secrets.token_hex(16), f'{secrets.randbelow(1000000):06d}', now()
        ip_hash = self.digest('ip:' + ip)
        hashed = self.digest(ident + ':' + phone + ':' + code)
        with self.store.db() as c:
            c.execute('BEGIN IMMEDIATE')
            c.execute('DELETE FROM sms_challenges WHERE created<?', (timestamp - 86400000,))
            rows = c.execute('SELECT created FROM sms_challenges WHERE phone=?', (phone,)).fetchall()
            if rows and timestamp - max(r[0] for r in rows) < 60000 or len(rows) >= 10 or sum(r[0] > timestamp-3600000 for r in rows) >= 5:
                raise Problem(429, '验证码发送频繁，请稍后再试')
            if c.execute('SELECT count(*) FROM sms_challenges WHERE ip_hash=? AND created>?', (ip_hash,timestamp-3600000)).fetchone()[0] >= 30 or c.execute('SELECT count(*) FROM sms_challenges').fetchone()[0] >= self.settings.sms_daily_limit:
                raise Problem(429, '短信发送暂时达到上限，请稍后再试')
            c.execute('INSERT INTO sms_challenges(id,phone,ip_hash,code_hash,created,expires) VALUES(?,?,?,?,?,?)', (ident,phone,ip_hash,hashed,timestamp,timestamp+300000))
        self.sms.send(phone, code)
        with self.store.db() as c:
            c.execute('UPDATE sms_challenges SET delivered=1 WHERE id=?', (ident,))
            c.execute('UPDATE sms_challenges SET used=1 WHERE phone=? AND id<>? AND created<=?', (phone,ident,timestamp))
        return {'challenge_id': ident, 'expires_in': 300, 'retry_after': 60}

    def verify(self, value, token=''):
        phone = phone_number(value.phone)
        supplied = self.digest(value.challenge_id + ':' + phone + ':' + value.code)
        legacy = self.store.user_by_token(token) if value.bind_legacy else None
        secret, timestamp = secrets.token_urlsafe(36), now()
        error, uid, bound = None, None, False
        with self.store.db() as c:
            c.execute('BEGIN IMMEDIATE')
            row = c.execute('SELECT * FROM sms_challenges WHERE id=?', (value.challenge_id,)).fetchone()
            if not row or row['phone'] != phone or row['used'] or not row['delivered'] or row['expires'] < timestamp or row['attempts'] >= 5:
                error = Problem(400, '验证码无效或已过期，请重新获取')
            elif not hmac.compare_digest(row['code_hash'], supplied):
                c.execute('UPDATE sms_challenges SET attempts=attempts+1 WHERE id=?', (row['id'],))
                error = Problem(400, '验证码不正确')
            else:
                c.execute('UPDATE sms_challenges SET used=1 WHERE id=?', (row['id'],))
                account = c.execute('SELECT user_id FROM phone_accounts WHERE phone=?', (phone,)).fetchone()
                previous = c.execute('SELECT phone FROM phone_accounts WHERE user_id=?', (legacy['id'],)).fetchone() if legacy else None
                if legacy and (previous and previous[0] != phone or account and account[0] != legacy['id']):
                    error = Problem(409, '手机号与现有服务账号已分别绑定，不能自动合并；请联系管理员保留原账号资料')
                else:
                    uid = account[0] if account else legacy['id'] if legacy else secrets.token_hex(16)
                    if not account and not legacy:
                        c.execute("INSERT INTO users VALUES(?,?,?,'active',?,?,?,?)", (uid,sha256(secrets.token_bytes(48)).hexdigest(),'墨知用户',self.settings.new_account_run_limit,encode(list(TOOLS)),timestamp,timestamp))
                    user = c.execute('SELECT * FROM users WHERE id=?', (uid,)).fetchone()
                    if user['status'] != 'active':
                        error = Problem(403, '此用户已停用')
                    else:
                        if not account:
                            c.execute('INSERT INTO phone_accounts VALUES(?,?)', (phone,uid))
                            bound = legacy is not None
                        c.execute('DELETE FROM auth_sessions WHERE expires<?', (timestamp,))
                        c.execute('INSERT INTO auth_sessions VALUES(?,?,?)', (sha256(secret.encode()).hexdigest(),uid,timestamp+30*86400000))
                        self.store.audit(c, 'account.login', uid, 'bound_legacy' if bound else '')
        # Incorrect-attempt counters and single-use consumption must commit before raising.
        if error:
            raise error
        return {'token': secret, 'expires_at': timestamp+30*86400000, 'user': self.profile(uid), 'bound_legacy': bound}

    def profile(self, uid):
        value = self.store.user(uid)
        with self.store.db() as c:
            row = c.execute('SELECT phone FROM phone_accounts WHERE user_id=?', (uid,)).fetchone()
        return {**value, 'phone_masked': masked(row[0]) if row else '', 'phone_verified': bool(row)}

    def logout(self, token):
        with self.store.db() as c:
            c.execute('DELETE FROM auth_sessions WHERE token_hash=?', (sha256(token.encode()).hexdigest(),))
        return {'ok': True}

    def require_phone(self, uid):
        if not self.profile(uid)['phone_verified']:
            raise Problem(403, '请先验证手机号并绑定账号')

    def chunk(self, uid, ident, value):
        self.require_phone(uid)
        if not re.fullmatch('[a-f0-9]{32}', ident):
            raise Problem(422, '备份编号无效')
        kinds = {'conversation','message','memory','decision','skill','share','share_comment','share_like','reminder','health_record'}
        cleaned = []
        for item in value.items:
            if set(item) != {'id','kind','parent','created','data'} or not isinstance(item['kind'],str) or item['kind'] not in kinds or not isinstance(item['id'], str) or not 1<=len(item['id'])<=100 or not isinstance(item['parent'], str) or len(item['parent'])>100 or type(item['created']) is not int or item['created']<0 or not isinstance(item['data'],dict) or len(encode(item['data']))>100000:
                raise Problem(422, '导入记录格式无效')
            item = json.loads(encode(item))
            # Archives are data, never a queue for replaying old side effects or uploading local paths.
            for key in ('remote_payload','pending_attachments'):
                item['data'].pop(key,None)
            if item['kind']=='message' and item['data'].get('status') in ('pending','interrupted'):
                item['data']['status']='cancelled'
            if item['kind']=='reminder':
                item['data'].update(state='paused',next_fire=0)
            cleaned.append(item)
        body = encode(cleaned)
        if len(body.encode()) > 240000:
            raise Problem(413, '导入批次过大')
        with self.store.db() as c:
            c.execute('BEGIN IMMEDIATE')
            c.execute('DELETE FROM account_archives WHERE complete=0 AND created<?', (now()-86400000,))
            archive = c.execute('SELECT * FROM account_archives WHERE id=?', (ident,)).fetchone()
            if archive is None:
                if value.seq!=0:
                    raise Problem(409, '请从首批开始导入')
                if c.execute('SELECT count(*) FROM account_archives WHERE user_id=? AND complete=0', (uid,)).fetchone()[0]>=5:
                    raise Problem(429, '未完成的导入过多，请稍后再试')
                c.execute('INSERT INTO account_archives(id,user_id,source,created) VALUES(?,?,?,?)', (ident,uid,value.source,now()))
            elif archive['user_id']!=uid:
                raise Problem(404, '备份不存在')
            elif archive['source']!=value.source:
                raise Problem(409, '导入来源不一致')
            old = c.execute('SELECT body FROM account_archive_chunks WHERE archive_id=? AND seq=?', (ident,value.seq)).fetchone()
            if old:
                if old[0]!=body:
                    raise Problem(409, '批次已存在且内容不同，请新建导入')
            else:
                if archive and archive['complete']:
                    raise Problem(409, '备份已完成')
                counts = c.execute('SELECT count(*),coalesce(sum(length(CAST(body AS BLOB))),0),coalesce(sum(count),0) FROM account_archive_chunks WHERE archive_id=?', (ident,)).fetchone()
                if value.seq!=counts[0]:
                    raise Problem(409, '请按顺序上传批次')
                if counts[1]+len(body.encode())>24*1024*1024 or counts[2]+len(cleaned)>50000:
                    raise Problem(413, '单份备份上限为 24 MB、50000 条记录')
                c.execute('INSERT INTO account_archive_chunks VALUES(?,?,?,?)', (ident,value.seq,body,len(cleaned)))
        return {'ok':True,'accepted':len(cleaned)}

    def complete(self, uid, ident, value):
        with self.store.db() as c:
            c.execute('BEGIN IMMEDIATE')
            a = c.execute('SELECT * FROM account_archives WHERE id=? AND user_id=?', (ident,uid)).fetchone()
            if not a:
                raise Problem(404,'备份不存在')
            rows = c.execute('SELECT body,count FROM account_archive_chunks WHERE archive_id=? ORDER BY seq', (ident,)).fetchall()
            if len(rows)!=value.chunks or sum(r['count'] for r in rows)!=value.total:
                raise Problem(409,'批次不完整，未完成导入')
            ids = [i['id'] for row in rows for i in json.loads(row['body'])]
            if len(ids)!=len(set(ids)):
                raise Problem(422,'备份包含重复记录编号')
            c.execute('UPDATE account_archives SET complete=1,total=? WHERE id=?', (value.total,ident))
            # Retain five complete snapshots per account; never expose a partial snapshot.
            c.execute('DELETE FROM account_archives WHERE user_id=? AND complete=1 AND id NOT IN (SELECT id FROM account_archives WHERE user_id=? AND complete=1 ORDER BY created DESC,id DESC LIMIT 5)', (uid,uid))
        return {'ok':True,'total':value.total}

    def archives(self, uid):
        with self.store.db() as c:
            return {'items':[dict(r) for r in c.execute('SELECT id,source,created,total FROM account_archives WHERE user_id=? AND complete=1 ORDER BY created DESC,id DESC', (uid,))]}

    def read_chunk(self, uid, ident, seq):
        with self.store.db() as c:
            a = c.execute('SELECT id FROM account_archives WHERE id=? AND user_id=? AND complete=1', (ident,uid)).fetchone()
            if not a:
                raise Problem(404,'备份不存在')
            row = c.execute('SELECT body FROM account_archive_chunks WHERE archive_id=? AND seq=?', (ident,seq)).fetchone()
        return {'items':json.loads(row[0]) if row else [],'next':seq+1 if row else None}
