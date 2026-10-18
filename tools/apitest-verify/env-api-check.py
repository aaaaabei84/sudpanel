"""环境持久化 HTTP 回归：独立库内注入写失败，验证真实服务事务回滚。"""
import json
import os
import sqlite3
import urllib.error
import urllib.request
import urllib.parse

BASE = os.environ.get('SP_API_BASE', 'http://127.0.0.1:18797/api/apitest')
parsed = urllib.parse.urlsplit(BASE)
if parsed.scheme != 'http' or parsed.hostname != '127.0.0.1' or parsed.port in (18790, 18890):
    raise RuntimeError('Use an isolated loopback endpoint')
DB = os.environ['SP_DB']
http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
count = failures = 0

def call(path, method='GET', body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={'Content-Type': 'application/json'})
    try:
        response = http.open(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        text = response.read().decode()
        return response.status, json.loads(text) if text else None

def check(name, ok):
    global count, failures
    count += 1
    failures += not ok
    print(('PASS ' if ok else 'FAIL ') + name)

def sql(statement, args=()):
    with sqlite3.connect(DB, timeout=10) as con:
        con.execute(statement, args)

_, env = call('/envs', 'POST', {'name': 'environment-transaction-regression'})
eid = env['id']
vars_before = {'base': [{'name': 'url', 'value': 'https://example.invalid'}],
               'header': [{'name': 'header', 'value': 'old', 'secret': True}], 'body': []}
vars_expected = {'base': [{'name': 'url', 'value': 'https://example.invalid', 'secret': False}],
                 'header': [{'name': 'header', 'value': 'old', 'secret': True}], 'body': []}
sign_before = {'enabled': False, 'appId': 'old', 'appSecret': 'dummy-regression-key',
               'nonce': 'old', 'mgId': ''}
copied = None
try:
    status, result = call(f'/envs/{eid}/settings', 'PUT', {'sign': sign_before, 'vars': vars_before})
    check('整套环境保存成功', status == 200 and result.get('vars') == vars_expected)
    check('保存响应不泄漏密钥', status == 200 and 'appSecret' not in result['sign'])
    sql("UPDATE api_var SET scope=UPPER(scope) WHERE env_id=?", (eid,))
    status, copied = call(f'/envs/{eid}/duplicate', 'POST')
    check('复制支持大写历史作用域', status == 200 and call(f'/vars?env={copied["id"]}')[1] == vars_expected)
    sql("CREATE TRIGGER reject_env_var BEFORE INSERT ON api_var "
        "WHEN NEW.name='reject' BEGIN SELECT RAISE(ABORT,'injected test failure'); END")
    new_sign = dict(sign_before, appId='new', appSecret='')
    new_vars = {'base': [{'name': 'reject', 'value': 'new'}], 'header': [], 'body': []}
    status, _ = call(f'/envs/{eid}/settings', 'PUT', {'sign': new_sign, 'vars': new_vars})
    check('写入失败明确返回错误', status >= 400)
    check('整套失败后变量全部回滚', call(f'/vars?env={eid}')[1] == vars_expected)
    check('整套失败后签名也回滚', call(f'/sign?env={eid}')[1]['appId'] == 'old')
    check('整套失败后密钥保留', call(f'/sign/secret?env={eid}')[1]['appSecret'] == 'dummy-regression-key')
    status, _ = call('/vars', 'PUT', dict(new_vars, env=eid))
    check('旧变量接口同样回滚', status >= 400 and call(f'/vars?env={eid}')[1] == vars_expected)
    sql('DROP TRIGGER reject_env_var')
    sql("CREATE TRIGGER reject_env_sign BEFORE INSERT ON api_sign "
        "WHEN NEW.app_id='reject' BEGIN SELECT RAISE(ABORT,'injected test failure'); END")
    status, _ = call('/sign', 'PUT', dict(new_sign, env=eid, appId='reject'))
    check('旧签名接口失败保留原配置', status >= 400 and call(f'/sign?env={eid}')[1]['appId'] == 'old')
    sql('DROP TRIGGER reject_env_sign')
    status, _ = call(f'/envs/{eid}/settings', 'PUT', {'vars': vars_before})
    check('缺少半套设置拒绝写入', status == 400)
finally:
    sql('DROP TRIGGER IF EXISTS reject_env_var')
    sql('DROP TRIGGER IF EXISTS reject_env_sign')
    if copied and copied.get('id'):
        call(f'/envs/{copied["id"]}', 'DELETE')
    call(f'/envs/{eid}', 'DELETE')
print(f'环境 HTTP 回归：{count-failures}/{count}，失败 {failures}')
raise SystemExit(1 if failures else 0)
