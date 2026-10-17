import json
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient

from mozhi_service.accounts import Accounts, VerifyInput, ChunkInput, CompleteInput, phone_number
from mozhi_service.api import create_app
from mozhi_service.settings import Settings, TOOLS
from mozhi_service.store import Store, Problem, now


class SMS:
    def __init__(self):
        self.messages = []
    def send(self, phone, code):
        self.messages.append((phone, code))


@pytest.fixture
def env(tmp_path):
    settings = Settings(db_path=str(tmp_path/'db.sqlite'), auth_secret='test-only-secret-xxxxxxxxxxxxxxxxxxxx')
    store = Store(settings.db_path)
    app = create_app(settings,store)
    sms = SMS()
    app.state.accounts.sms = sms
    return store, app.state.accounts, sms, TestClient(app)


def issued(env, phone='13800138000'):
    _, accounts, sms, _ = env
    challenge = accounts.send_code(phone,'test-ip')
    return VerifyInput(phone=phone,code=sms.messages[-1][1],challenge_id=challenge['challenge_id'])


def login(env, phone='13800138000'):
    return env[1].verify(issued(env,phone))


def row(ident='c1', kind='conversation', **data):
    return dict(id=ident,kind=kind,parent='',created=100,data=data or dict(title='旧对话'))


def test_phone_normalization():
    assert phone_number('+86 138 0013 8000')==phone_number('13800138000')
    for v in ('12345678901','+1 13800138000','13800138000 OR 1=1','1380013800'):
        with pytest.raises(Problem): phone_number(v)


def test_registration_profile_opaque_session_and_logout(env):
    store,a,sms,client=env
    value=issued(env)
    response=client.post('/v1/auth/verify',json=value.model_dump())
    assert response.status_code==200
    result=response.json();token=result['token'];uid=result['user']['id']
    assert result['user']['phone_masked']=='138****8000' and result['user']['phone_verified']
    assert '13800138000' not in json.dumps(result)
    assert store.user_by_token(token)['id']==uid
    h={'Authorization':'Bearer '+token}
    assert client.get('/v1/me',headers=h).json()['phone_verified']
    assert client.post('/v1/auth/logout',headers=h).status_code==200
    assert client.get('/v1/me',headers=h).status_code==401
    with store.db() as c:
        challenge=c.execute('SELECT * FROM sms_challenges').fetchone()
        assert sms.messages[0][1] not in challenge['code_hash']
        assert not c.execute('SELECT 1 FROM users WHERE token_hash=?',(token,)).fetchone()


def test_existing_token_binding_preserves_identity_and_data(env):
    store,a,_,_=env
    old=store.create_user(dict(nickname='原用户',daily_run_limit=9,enabled_tools=list(TOOLS)))
    uid=old['user']['id']
    with store.db() as c:
        c.execute('INSERT INTO health_records VALUES(?,?,?,?,?)',('old-health',uid,'{}',100,100))
    v=issued(env);v.bind_legacy=True
    result=a.verify(v,old['token'])
    assert result['bound_legacy'] and result['user']['id']==uid
    assert store.user_by_token(old['token'])['id']==uid
    with store.db() as c:
        assert c.execute('SELECT user_id FROM health_records').fetchone()[0]==uid
        assert c.execute('SELECT count(*) FROM users').fetchone()[0]==1


def test_phone_login_reuses_account_from_another_device(env):
    first=login(env)
    with env[0].db() as c: c.execute('UPDATE sms_challenges SET created=created-61000')
    second=login(env)
    assert first['user']['id']==second['user']['id'] and first['token']!=second['token']
    env[1].logout(first['token'])
    assert env[0].user_by_token(second['token'])


def test_single_use_even_under_concurrent_verification(env):
    value=issued(env)
    def verify():
        try:return env[1].verify(value)['user']['id']
        except Problem as p:return p.status
    with ThreadPoolExecutor(max_workers=2) as pool:
        results=list(pool.map(lambda _:verify(),range(2)))
    assert sum(isinstance(x,str) for x in results)==1


def test_wrong_attempts_commit_and_lock_after_five(env):
    correct=issued(env);wrong=correct.model_copy(update={'code':'000000' if correct.code!='000000' else '999999'})
    for _ in range(5):
        with pytest.raises(Problem):env[1].verify(wrong)
    with pytest.raises(Problem):env[1].verify(correct)
    with env[0].db() as c:assert c.execute('SELECT attempts FROM sms_challenges').fetchone()[0]==5


def test_expired_and_unsent_codes_rejected(env):
    value=issued(env)
    with env[0].db() as c:c.execute('UPDATE sms_challenges SET expires=?',(now()-1,))
    with pytest.raises(Problem):env[1].verify(value)
    with env[0].db() as c:c.execute('UPDATE sms_challenges SET expires=?,delivered=0',(now()+300000,))
    with pytest.raises(Problem):env[1].verify(value)


def test_rate_limit_and_failed_delivery_never_create_login(env):
    value=issued(env)
    with pytest.raises(Problem) as e:env[1].send_code(value.phone,'different-ip')
    assert e.value.status==429
    def fail(phone,code):raise Problem(503,'no delivery')
    env[2].send=fail
    with pytest.raises(Problem):env[1].send_code('13900139000','other-ip')
    with env[0].db() as c:assert c.execute('SELECT delivered FROM sms_challenges WHERE phone=?',('+8613900139000',)).fetchone()[0]==0


def test_missing_sms_configuration_has_no_test_bypass(tmp_path):
    settings=Settings(db_path=str(tmp_path/'db'),auth_secret='a'*32)
    store=Store(settings.db_path);client=TestClient(create_app(settings,store))
    assert client.post('/v1/auth/code',json={'phone':'13800138000'}).status_code==503
    assert client.post('/v1/auth/verify',json={'phone':'13800138000','challenge_id':'a'*32,'code':'123456'}).status_code==400


def test_binding_collision_does_not_merge_or_reassign_users(env):
    first=login(env)
    old=env[0].create_user(dict(nickname='另一个服务用户',daily_run_limit=5,enabled_tools=[]))
    with env[0].db() as c:c.execute('UPDATE sms_challenges SET created=created-61000')
    value=issued(env);value.bind_legacy=True
    with pytest.raises(Problem) as error:env[1].verify(value,old['token'])
    assert error.value.status==409
    assert env[1].profile(first['user']['id'])['phone_verified']
    assert not env[1].profile(old['user']['id'])['phone_verified']


def test_admin_rotation_and_suspend_revoke_access(env):
    result=login(env);store=env[0];uid=result['user']['id']
    store.edit_user(uid,{'status':'suspended'})
    with pytest.raises(Problem):store.user_by_token(result['token'])
    store.edit_user(uid,{'status':'active'})
    store.rotate_token(uid)
    with pytest.raises(Problem):store.user_by_token(result['token'])


def test_archive_is_private_idempotent_and_not_visible_until_complete(env):
    store,a,_,client=env
    u=login(env)['user']['id'];other=login(env,'13900139000')['user']['id'];ident='a'*32
    value=ChunkInput(source='installation-00001',seq=0,items=[row()])
    a.chunk(u,ident,value);a.chunk(u,ident,value)
    assert a.archives(u)['items']==[]
    with pytest.raises(Problem):a.read_chunk(u,ident,0)
    with pytest.raises(Problem):a.complete(u,ident,CompleteInput(chunks=2,total=2))
    a.complete(u,ident,CompleteInput(chunks=1,total=1))
    a.complete(u,ident,CompleteInput(chunks=1,total=1))
    assert len(a.archives(u)['items'])==1 and a.read_chunk(u,ident,0)['items']==[row()]
    assert a.read_chunk(u,ident,1)['next'] is None and not a.archives(other)['items']
    for action in [lambda:a.read_chunk(other,ident,0),lambda:a.chunk(other,ident,value),lambda:a.complete(other,ident,CompleteInput(chunks=1,total=1))]:
        with pytest.raises(Problem) as error:action()
        assert error.value.status==404
    with pytest.raises(Problem):a.chunk(u,ident,value.model_copy(update={'items':[row(title='overwrite')]}))
    assert client.get('/v1/account/archives').status_code==401


def test_archive_validates_bytes_order_and_sanitizes_side_effects(env):
    a=env[1];uid=login(env)['user']['id'];ident='b'*32
    value=ChunkInput(source='installation-00001',seq=0,items=[row(),{**row('m1','message',role='user',text='测试',status='pending',remote_payload='DO NOT RUN',pending_attachments=['/local/file']),'parent':'c1'},row('r1','reminder',state='active',next_fire=99999)])
    with pytest.raises(Problem):a.chunk(uid,ident,value.model_copy(update={'seq':1}))
    a.chunk(uid,ident,value);a.complete(uid,ident,CompleteInput(chunks=1,total=3))
    data=a.read_chunk(uid,ident,0)['items']
    assert 'remote_payload' not in data[1]['data'] and 'pending_attachments' not in data[1]['data']
    assert data[1]['data']['status']=='cancelled' and data[2]['data']['state']=='paused'
    with pytest.raises(Problem):a.chunk(uid,'c'*32,ChunkInput(source='installation-00001',seq=0,items=[row(text='大'*99990)]))
    with pytest.raises(Problem):a.chunk(uid,'d'*32,ChunkInput(source='installation-00001',seq=0,items=[{**row(),'kind':[]}]))


def test_duplicate_ids_never_become_complete_backup(env):
    a=env[1];uid=login(env)['user']['id'];ident='a'*32
    a.chunk(uid,ident,ChunkInput(source='installation-00001',seq=0,items=[row(),row()]))
    with pytest.raises(Problem):a.complete(uid,ident,CompleteInput(chunks=1,total=2))
    assert not a.archives(uid)['items']
