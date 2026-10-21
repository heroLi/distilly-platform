#!/usr/bin/env python3
"""Actual isolated file-H2/API/Worker: blocked real text egress and one TXT mock flow."""
import argparse,hashlib,json,os,shutil,socket,subprocess,sys,tempfile,time
from pathlib import Path
import urllib.error,urllib.request

ROOT=Path(__file__).resolve().parents[1]
TOKEN='text-egress-synthetic-worker-token-32-characters'


def run(output,browser=False):
 output=output.resolve();output.mkdir(parents=True,exist_ok=True)
 safe={k:v for k,v in os.environ.items() if k in ('HOME','LANG','LC_ALL','TMPDIR','PATH','JAVA_HOME','JAVA_BIN','MAVEN_OPTS','MAVEN_ARGS')}
 result={'status':'RUNNING','real_provider_calls':0,'checks':[],'screenshots':{},'page_errors':[],'external_browser_requests':[]};processes=[];logs=[]
 with tempfile.TemporaryDirectory(prefix='distilly-text-egress-') as tmp:
  folder=Path(tmp);jar=folder/'runtime.jar';shutil.copy2(ROOT/'server/target/distilly-platform-0.1.0.jar',jar);result['jar_sha256']=hashlib.sha256(jar.read_bytes()).hexdigest()
  with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
  base=f'http://127.0.0.1:{port}'
  env=dict(safe,PLATFORM_DEMO='true',PLATFORM_WORKER_TOKEN=TOKEN,PLATFORM_PORT=str(port),PLATFORM_BIND='127.0.0.1',PLATFORM_DB_URL='jdbc:h2:file:'+str(folder/'db')+';DATABASE_TO_LOWER=TRUE',PLATFORM_DB_USER='sa',PLATFORM_DB_PASSWORD='',PLATFORM_API_URL=base,MODEL_MODE='gateway',MODEL_BASE_URL='https://provider.invalid/v1',MODEL_NAME='synthetic-unavailable-budget',MODEL_API_KEY='synthetic-test-value-not-a-real-key',MODEL_SYNTHETIC_TEXT_FIXTURE='false')
  def launch(name,command,values):
   log=(output/(name+'.log')).open('w');logs.append(log);p=subprocess.Popen(command,cwd=ROOT,env=values,stdout=log,stderr=subprocess.STDOUT);processes.append(p);return p
  def stop(p):
   if p.poll() is None:
    p.terminate()
    try:p.wait(timeout=12)
    except subprocess.TimeoutExpired:p.kill();p.wait(timeout=5)
  def api(path,body=None,actor='owner',method=None,expected=200):
   req=urllib.request.Request(base+path,None if body is None else json.dumps(body,ensure_ascii=False).encode(),{'Authorization':'Bearer demo-'+actor,'Content-Type':'application/json'},method=method or ('POST' if body is not None else 'GET'))
   try:
    with urllib.request.urlopen(req,timeout=8) as response:status,data=response.status,response.read()
   except urllib.error.HTTPError as error:
    with error:status,data=error.code,error.read()
   assert status==expected,(path,status,expected)
   return json.loads(data) if data else None
  def wait(predicate,seconds=30):
   end=time.monotonic()+seconds
   while time.monotonic()<end:
    value=predicate()
    if value:return value
    time.sleep(.1)
   raise AssertionError('bounded synthetic condition timed out')
  def healthy():
   assert server.poll() is None,'isolated API exited'
   try:return api('/health')=={'status':'UP'}
   except OSError:return False
  def job_state(job,status):
   row=api('/api/jobs/'+job['id']);return row if row['status']==status else None
  def upload(project):
   boundary='text-egress-one-txt';data=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.txt"\r\nContent-Type: text/plain\r\n\r\n合成接口记录：重试必须核对幂等键，不能重复扣款。\r\n--{boundary}--\r\n').encode()
   req=urllib.request.Request(base+f'/api/projects/{project}/materials',data,{'Authorization':'Bearer demo-owner','Content-Type':'multipart/form-data; boundary='+boundary})
   with urllib.request.urlopen(req,timeout=8) as response:return json.load(response)['id']
  try:
   server=launch('api',[safe.get('JAVA_BIN','java'),'-jar',str(jar)],env);wait(healthy)
   project=api('/api/projects',{'title':'合成文字费用出口验证'})['id'];api(f'/api/projects/{project}/members',{'user_id':'viewer','role':'VIEWER'},method='PUT');material=upload(project)
   blocked_profile=api(f'/api/projects/{project}/profiles',{'title':'费用未核验的合成任务'})['id'];blocked=api(f'/api/profiles/{blocked_profile}/distill',{'material_ids':[material]})
   remote_worker=launch('blocked-worker',[sys.executable,str(ROOT/'worker/worker.py')],env)
   denied=wait(lambda:job_state(blocked,'FAILED'));assert denied['error_code']=='MODEL_BUDGET_NOT_VERIFIED' and denied['attempt']==1
   time.sleep(1.2);assert api('/api/jobs/'+blocked['id'])['attempt']==1
   api('/api/jobs/'+blocked['id']+'/retry',method='POST');denied=wait(lambda:job_state(blocked,'FAILED'));assert denied['attempt']==1;assert denied['error_code']=='MODEL_BUDGET_NOT_VERIFIED';assert api(f'/api/profiles/{blocked_profile}/versions')==[]
   result['checks'].append('real-provider target refused before model request, exact error persisted, no automatic retry or candidate version; explicit retry remains blocked')
   stop(remote_worker)
   result['explicit_blocked_executions']=(output/'blocked-worker.log').read_text().count('job failed id='+blocked['id']+' code=MODEL_BUDGET_NOT_VERIFIED')
   assert result['explicit_blocked_executions']==2
   mock_env=dict(env,MODEL_MODE='mock');mock=launch('mock-worker',[sys.executable,str(ROOT/'worker/worker.py')],mock_env)
   profile=api(f'/api/projects/{project}/profiles',{'title':'合成接口记录'})['id'];job=api(f'/api/profiles/{profile}/distill',{'material_ids':[material]});ready=wait(lambda:job_state(job,'SUCCEEDED'));version=ready['result']['version_id'];assert api(f'/api/profiles/{profile}/versions',actor='viewer')==[]
   api(f'/api/versions/{version}/review',{'approve':True,'note':'合成资料明确审核'});question=api(f'/api/profiles/{profile}/questions',{'question':'重试应核对什么？'},'viewer');answer=wait(lambda:job_state(question,'SUCCEEDED'));assert answer['result']['citations'] and '幂等键' in answer['result']['answer'] and '模拟模型' in answer['result']['answer'];api('/api/materials/'+material,actor='viewer',expected=403)
   result['checks'].append('one synthetic UTF-8 TXT import, mock extraction, explicit approval and viewer answer with citation succeed; raw material remains forbidden to viewer')
   if browser:
    from playwright.sync_api import sync_playwright
    with sync_playwright() as pw:
     chromium=pw.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox','--disable-dev-shm-usage']);page=chromium.new_page(viewport={'width':1440,'height':1080})
     def restrict(route):
      if route.request.url.startswith(base+'/') or route.request.url.startswith(('data:','blob:')):route.continue_()
      else:result['external_browser_requests'].append('blocked external request');route.abort()
     page.route('**/*',restrict);page.on('pageerror',lambda error:result['page_errors'].append(str(error)));page.goto(base+'/knowledge.html',wait_until='networkidle');page.locator('#token').fill('demo-owner');page.locator('#login-form button').click();page.locator('#workspace').wait_for(state='visible');page.locator('#projects').select_option(project);page.locator('[data-page=tasks]').click();page.get_by_text(blocked['id'][:8],exact=False).first.wait_for()
     # The real task-detail action renders the server failure code as actionable text.
     page.locator('#task-list tr').filter(has_text=blocked['id'][:8]).get_by_role('button',name='查看任务详情',exact=True).click()
     page.get_by_text('真实文字调用已关闭',exact=False).first.wait_for();shot=output/'blocked-task-browser.png';page.screenshot(path=str(shot),full_page=True);result['screenshots']['blocked_task']={'file':str(shot),'sha256':hashlib.sha256(shot.read_bytes()).hexdigest()}
     assert not result['page_errors'] and not result['external_browser_requests'];chromium.close()
    result['checks'].append('native browser task detail shows cost-block reason; no external browser request or page exception')
   result['status']='PASS'
  finally:
   for process in reversed(processes):stop(process)
   for log in logs:log.close()
   result['cleanup_all_processes_stopped']=all(process.poll() is not None for process in processes)
   (output/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
 print('PASS: text-egress',len(result['checks']),'groups; real provider calls 0')


if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,default=ROOT/'server/target/text-egress-e2e');parser.add_argument('--browser',action='store_true');args=parser.parse_args();run(args.output,args.browser)
