#!/usr/bin/env python3
"""独立SQLite/端口与禁ADB的真实Servlet重启、证据ZIP和维护归档对照。"""
import argparse, hashlib, io, json, os, pathlib, shutil, socket, sqlite3, subprocess, tempfile, time, urllib.error, urllib.request, zipfile
parser=argparse.ArgumentParser();parser.add_argument('--jar',required=True);parser.add_argument('--java',default=os.environ.get('JAVA_BIN') or (str(pathlib.Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')));parser.add_argument('--port',type=int,default=18819);parser.add_argument('--summary',required=True);parser.add_argument('--keep',action='store_true');args=parser.parse_args()
jar=pathlib.Path(args.jar).resolve();assert jar.is_file() and pathlib.Path(args.java).is_file()
assert 1024<=args.port<=65535 and args.port not in (18790,18890)
with socket.socket() as probe:
    assert probe.connect_ex(('127.0.0.1',args.port))!=0,'isolated port already occupied'
root=pathlib.Path(tempfile.mkdtemp(prefix='sudpanel-perf-context-'));db=root/'fixture.db';process=None;log=None;checks=[]
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}));url=f'http://127.0.0.1:{args.port}'
def check(name,condition):
    checks.append({'name':name,'passed':bool(condition)})
    if not condition: raise AssertionError(name)
def call(path,body=None):
    request=urllib.request.Request(url+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
    try:
        with opener.open(request,timeout=15) as response: data=response.read();return response.status,data
    except urllib.error.HTTPError as error:return error.code,error.read()
def read(path):
    status,body=call(path);assert status==200,(path,status,body[:100]);return json.loads(body)
def stop():
    global process,log
    if process and process.poll() is None:
        process.terminate()
        try:process.wait(timeout=15)
        except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
    if log:log.close()
    process=None;log=None
def start():
    global process,log
    log=open(root/'server.log','ab')
    process=subprocess.Popen([args.java,'-jar',str(jar),f'--server.port={args.port}','--server.address=127.0.0.1',f'--sudpanel.db-path={db}','--sudpanel.adb-path=disabled',f'--sudpanel.resource-id-file={root}/no-real-config.properties'],stdout=log,stderr=subprocess.STDOUT,cwd=root,env={**os.environ,'SUDPANEL_DATA_DIR':str(root)})
    for _ in range(150):
        if process.poll() is not None:raise RuntimeError('own server exited; see '+str(root/'server.log'))
        try:
            if call('/api/meta')[0]==200 and call('/api/perf/runs/active')[0]==200:return
        except (OSError,urllib.error.URLError):pass
        time.sleep(.1)
    raise TimeoutError('own server ready deadline')
def sql(statement,*values):
    with sqlite3.connect(db) as connection:return connection.execute(statement,values).fetchall()
try:
    start();check('禁ADB实例设备为空',read('/api/devices')==[])
    sql("INSERT INTO perf_game(id,name,short_name) VALUES(501,'fixture','fixture')")
    sql("INSERT INTO perf_run(id,device,mode,status,game_queue,loop_count,sample_seconds,start_time) VALUES(401,'SERIAL-SENTINEL','NORMAL','STOPPED','501',1,5,'2020-01-01T00:00:00Z')")
    sql("INSERT INTO perf_loop(id,run_id,game_id,loop_no,status,pss_avg_kb,cpu_avg,game_round_id) VALUES(5011,401,501,1,'OK',1024,2,'ROUND-SENTINEL')")
    for kind,reason,duration in [('WAIT_STARTED','ENV_UNREACHABLE',None),('WAIT_FINISHED','ENV_REACHABLE',1234)]:sql("INSERT INTO perf_run_event(run_id,type,stage,game_ordinal,loop_no,created_at,reason,duration_ms) VALUES(401,?,'ENV_WAIT',1,1,'2020-01-01T00:00:00Z',?,?)",kind,reason,duration)
    shots=root/'screenshots';shots.mkdir();(shots/'run401-game501-loop1.png').write_bytes(b'SECRET-SERIAL ROUND-SENTINEL PRIVATE-KEY-SENTINEL')
    timeline=read('/api/perf/runs/401/timeline');check('真实HTTP读取有序时间线及耗时',[x['type'] for x in timeline['items']]==['WAIT_STARTED','WAIT_FINISHED'] and timeline['items'][1]['durationMs']==1234)
    check('旧运行条件不补造',read('/api/perf/runs/401/conditions')['state']=='NOT_RECORDED')
    trend=read('/api/perf/runs/401/trend?gameId=501');check('默认排除未知可比条件',trend['items']==[] and trend['excluded']==1)
    trend=read('/api/perf/runs/401/trend?gameId=501&comparableOnly=false');check('放开筛选仍明确未知',len(trend['items'])==1 and not trend['items'][0]['comparable'])
    status,data=call('/api/perf/runs/401/evidence.zip');check('默认ZIP真实下载成功',status==200)
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        contents={name:archive.read(name) for name in archive.namelist()};manifest=json.loads(contents['manifest.json'])
        check('ZIP结构与CRC完整',archive.testzip() is None and {'manifest.json','timeline.json','loops.json','request-summaries.json','logs/redacted.txt'}<=set(contents))
        check('默认ZIP无原始文件和身份哨兵',not any(name.startswith('private/') for name in contents) and all(marker not in value for value in contents.values() for marker in [b'SERIAL-SENTINEL',b'ROUND-SENTINEL',b'PRIVATE-KEY-SENTINEL']))
        check('manifest哈希逐字节相符',all(hashlib.sha256(contents[name]).hexdigest()==meta['sha256'] and len(contents[name])==meta['bytes'] for name,meta in manifest['files'].items()))
        check('截图排除和缺日志均明确',any(x['state']=='RECORDED' and not x['included'] for x in manifest['attachments']) and any(x['state']=='MISSING' for x in manifest['attachments']))
    check('缺确认拒绝私密附件',call('/api/perf/runs/401/evidence.zip?includePrivateFiles=true')[0]==400)
    status,data=call('/api/perf/runs/401/evidence.zip?includePrivateFiles=true&confirmation=PRIVATE_LOCAL_ONLY')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:check('明确私密本机包才含原始截图',status==200 and b'PRIVATE-KEY-SENTINEL' in archive.read('private/loop-1.png') and json.loads(archive.read('manifest.json'))['private'])
    check('无设备布局不能绑定坐标',call('/api/perf/coordinate-profile/fixed',{'deviceSerial':'fixture','confirmed':True})[0]==400)
    stop();start();check('同库重启保留时间线',read('/api/perf/runs/401/timeline')['total']==2)
    check('重启不复活手动停止任务',sql('SELECT status FROM perf_run WHERE id=401')[0][0]=='STOPPED' and read('/api/perf/runs/active')==[])
    overview=read('/api/maintenance/overview');names={entry['name'] for entry in overview['tables']};check('容量概览包含新增三表',{'perf_coordinate_profile','perf_run_event','perf_run_conditions'}<=names)
    status,body=call('/api/maintenance/cleanup/preview',{'domain':'PERF','before':'2025-01-01T00:00:00Z','archive':True});preview=json.loads(body);check('维护预览包含恢复事件',status==200 and preview['counts']['perf_run_event']==2)
    sql("INSERT INTO perf_run_event(run_id,type,stage,created_at,reason) VALUES(401,'STOP_REQUESTED','USER','2020-01-01T00:00:00Z','USER_REQUEST')")
    check('预览后新事件阻止旧清理',call('/api/maintenance/cleanup/commit',{'token':preview['token']})[0]==400 and sql('SELECT COUNT(*) FROM perf_run')[0][0]==1)
    status,body=call('/api/maintenance/cleanup/preview',{'domain':'PERF','before':'2025-01-01T00:00:00Z','archive':True});preview=json.loads(body)
    status,body=call('/api/maintenance/cleanup/commit',{'token':preview['token']});clean=json.loads(body);check('新预览事务清理成功',status==200 and clean['deleted']==1 and sql('SELECT COUNT(*) FROM perf_run_event')[0][0]==0)
    status,data=call('/api/maintenance/archives/'+clean['archiveId']+'/download');check('私密归档保留完整时间线',status==200 and b'perf_run_event' in data and b'ENV_UNREACHABLE' in data)
    check('全部检查不发业务或操作设备',read('/api/devices')==[])
except Exception as error:
    checks.append({'name':'完整真实HTTP验收','passed':False,'error':str(error)});raise
finally:
    stop();summary={'total':len(checks),'passed':sum(c['passed'] for c in checks),'failed':sum(not c['passed'] for c in checks),'checks':checks,'trueDeviceChecks':0,'isolatedRoot':str(root),'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest()};path=pathlib.Path(args.summary).resolve();path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n');print(json.dumps(summary,ensure_ascii=False))
    if not args.keep:shutil.rmtree(root)
