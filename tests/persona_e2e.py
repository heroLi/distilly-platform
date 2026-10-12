#!/usr/bin/env python3
"""Real API + Worker + HTTP gateway fixture. Uses only synthetic person/material data."""
from collections import Counter
import base64
import io
import wave
import json
import os
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT=Path(__file__).resolve().parents[1]
TOKEN='persona-e2e-worker-token-at-least-32-characters'
WAV_IO=io.BytesIO()
with wave.open(WAV_IO,'wb') as wav:
    wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(8000);wav.writeframes(b'\x00\x00'*800)
WAV=WAV_IO.getvalue()
PNG='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jXioAAAAASUVORK5CYII='

class Gateway(BaseHTTPRequestHandler):
    stages=set()
    memories=[]
    contexts=[]
    chat_calls=Counter()
    worker_kill_closed=threading.Event()
    worker_exit_closed=threading.Event()
    cancelled_stream_closed=threading.Event()
    cancel_frames=0
    cancel_total_frames=0
    def log_message(self,*_):pass
    def do_POST(self):
        if self.headers.get('Authorization')!='Bearer fixture-key':self.send_error(401);return
        raw_input=self.rfile.read(int(self.headers['Content-Length']))
        if self.path.endswith('/audio/speech'):
            assert b'max_tokens' not in raw_input and b'max_completion_tokens' not in raw_input
            Gateway.stages.add('tts');self.send_response(200);self.send_header('Content-Type','audio/wav');self.send_header('Content-Length',str(len(WAV)));self.end_headers();self.wfile.write(WAV);return
        if self.path.endswith('/audio/transcriptions'):
            assert b'max_tokens' not in raw_input and b'max_completion_tokens' not in raw_input
            Gateway.stages.add('asr');assert b'name="file"' in raw_input
            raw=json.dumps({'text':'今天想去散步。'}).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(raw);return
        request=json.loads(raw_input)
        if self.path.endswith('/images/generations'):
            assert 'max_tokens' not in request and 'max_completion_tokens' not in request
            output={'data':[{'b64_json':PNG}]}
        else:
            system=request['messages'][0]['content'];user=request['messages'][1]['content']
            if isinstance(user,list):
                Gateway.stages.add('vision');assert user[1]['image_url']['url'].startswith('data:image/png;');user=user[0]['text']
            p=json.loads(user)
            assert 'max_completion_tokens' not in request
            expected = p['context_budget']['output_tokens'] if 'history' in p else 4096 if ('chunk_id' in p or 'sources' in p) else 2048 if 'claims' in p else 8192
            assert request.get('max_tokens') == expected
            if 'history' in p:
                Gateway.stages.add('chat');Gateway.memories.append(p['memories'])
                Gateway.contexts.append(p)
                budget=p['context_budget']
                assert request.get('max_tokens') == budget['output_tokens']
                assert len(json.dumps(p,ensure_ascii=False).encode())+256 <= budget['input_limit']
                assert len(system.encode()) <= budget['system_reserve']
                assert '目前没有足够的已确认记录' in system
                assert '稳定表达约束' in system
                assert 'analysis' not in p['persona']

                Gateway.chat_calls[p['message']]+=1
                message=p['message'];memories=[{'body':'用户喜欢安静散步','quote':'喜欢安静散步'}] if '喜欢安静散步' in message and p['memory_enabled'] else []
                knowledge=p.get('knowledge',[]);citations=[knowledge[0]['id']] if knowledge else []
                if knowledge:Gateway.stages.add('knowledge')
                output={'citations':citations,'answer':knowledge[0]['text'] if knowledge else '我在听，我们可以慢慢聊。','emotion':{'label':'warm','intensity':0.6,'reason':'用户分享日常偏好'},'memory_candidates':memories}
                output['memory_citations']=[p['memories'][0]['id']] if p['memories'] else []
                output['experience_citations']=[]
                if message in ('合成取消慢流','合成退出慢流','合成强杀慢流','合成强杀后取消'): output['answer']='合成慢流回应。'*300
                if '巴黎演唱会' in message:
                    assert p['memories']==[] and p['experiences']==[]
                    output['answer']='目前没有足够的已确认记录，你能补充当时的背景吗？'

            elif 'chunk_id' in p:
                Gateway.stages.add('distill');output={'claims':[{'section':'消息排障','text':p['text'],'evidence':[{'material_id':p['material_id'],'chunk_id':p['chunk_id'],'quote':p['text'][:60]}]}]}
            elif 'sources' in p:
                Gateway.stages.add('analyze');assert ('Persona Analyzer' in system or 'Persona 分析 Prompt' in system)
                evidence=[{'material_id':s['material_id'],'quote':s['text'][:60]} for s in p['sources']]
                output={'analysis':{'expression':'温和、简洁','inferences':[],'insufficient':[]},'evidence':evidence}
            else:
                Gateway.stages.add('build');assert 'Persona' in system and 'analysis' in p
                output={'persona_markdown':'# 小溪\n\n原创朋友。表达温和，尊重交流节奏。'}
            if request.get('stream'):
                Gateway.stages.add('stream');self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
                content=json.dumps(output,ensure_ascii=False)
                cancellation_fixture=p.get('message') in ('合成取消慢流','合成退出慢流','合成强杀慢流','合成强杀后取消')
                if cancellation_fixture: Gateway.cancel_total_frames=(len(content)+15)//16
                try:
                    for i in range(0,len(content),16):
                        frame={'choices':[{'delta':{'content':content[i:i+16]}}]}
                        self.wfile.write(('data: '+json.dumps(frame)+'\n\n').encode());self.wfile.flush()
                        if cancellation_fixture: Gateway.cancel_frames+=1
                        time.sleep(.04)
                    self.wfile.write(('data: '+json.dumps({'choices':[{'delta':{},'finish_reason':'stop'}]})+'\n\n').encode())
                    self.wfile.write(b'data: [DONE]\n\n');return
                except (BrokenPipeError,ConnectionResetError):
                    if not cancellation_fixture: raise
                    if p.get('message') in ('合成强杀慢流','合成强杀后取消'):
                        Gateway.worker_kill_closed.set()
                    elif p.get('message')=='合成退出慢流':
                        Gateway.stages.add('graceful-release');Gateway.worker_exit_closed.set()
                    else:
                        Gateway.stages.add('cancelled-stream');Gateway.cancelled_stream_closed.set()
                    return
            finish = 'length' if p.get('definition',{}).get('name') == '合成截断人物' and 'analysis' in p else 'stop'
            output={'choices':[{'finish_reason':finish,'message':{'content':json.dumps(output,ensure_ascii=False)}}]}
        raw=json.dumps(output).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)


def main():
    real='--real-model' in __import__('sys').argv
    port=int(os.getenv('PERSONA_E2E_PORT','18082'));gateway=ThreadingHTTPServer(('127.0.0.1',int(os.getenv('PERSONA_E2E_GATEWAY_PORT','18083'))),Gateway);threading.Thread(target=gateway.serve_forever,daemon=True).start()
    base=f'http://127.0.0.1:{port}'
    with tempfile.TemporaryDirectory(prefix='persona-e2e-') as tmp:
        runtime_jar=Path(tmp)/'distilly-platform.jar'
        shutil.copy2(ROOT/'server/target/distilly-platform-0.1.0.jar',runtime_jar)
        env={**os.environ,'PLATFORM_DEMO':'true','PLATFORM_WORKER_TOKEN':TOKEN,'PLATFORM_PORT':str(port),'PLATFORM_BIND':'127.0.0.1','PLATFORM_DB_URL':f'jdbc:h2:file:{tmp}/db;DATABASE_TO_LOWER=TRUE','PLATFORM_DB_USER':'sa','PLATFORM_DB_PASSWORD':'','PLATFORM_API_URL':base,'MODEL_MODE':'gateway','MODEL_BASE_URL':f'http://127.0.0.1:{gateway.server_port}/v1','MODEL_ALLOW_HTTP':'true','MODEL_NAME':'fixture','MODEL_API_KEY':'fixture-key','IMAGE_BASE_URL':f'http://127.0.0.1:{gateway.server_port}/v1','IMAGE_API_KEY':'fixture-key','IMAGE_MODEL':'fixture-image'}
        env.update(SPEECH_BASE_URL=f'http://127.0.0.1:{gateway.server_port}/v1',SPEECH_API_KEY='fixture-key',TTS_MODEL='fixture-tts',ASR_MODEL='fixture-asr',VISION_BASE_URL=f'http://127.0.0.1:{gateway.server_port}/v1',VISION_API_KEY='fixture-key',VISION_MODEL='fixture-vision')
        if not real:env['PLATFORM_LEASE_SECONDS']='4'
        if real:
            for k in ('MODEL_BASE_URL','MODEL_NAME','MODEL_API_KEY'):
                if not os.getenv(k):raise RuntimeError('Missing '+k)
                env[k]=os.environ[k]
            env['MODEL_JSON_MODE']='true'
        java=os.getenv('JAVA_BIN','java');processes=[]
        if not real:env.update({'MODEL_OUTPUT_TOKEN_PARAMETER': 'max_tokens', 'MODEL_DEFAULT_OUTPUT_TOKENS': '4096', 'MODEL_DISTILL_OUTPUT_TOKENS': '4096', 'MODEL_KNOWLEDGE_ANSWER_OUTPUT_TOKENS': '2048', 'MODEL_PERSONA_ANALYSIS_OUTPUT_TOKENS': '4096', 'MODEL_PERSONA_BUILD_OUTPUT_TOKENS': '8192', 'VISION_OUTPUT_TOKEN_PARAMETER': 'max_tokens', 'MODEL_TEMPERATURE': '0', 'VISION_TEMPERATURE': '0'})
        def api(path,body=None,token='demo-owner',method=None,raw=False):
            headers={'Authorization':'Bearer '+token};data=None
            if body is not None:headers['Content-Type']='application/json';data=json.dumps(body).encode()
            with urllib.request.urlopen(urllib.request.Request(base+path,data,headers,method=method),timeout=15) as res:content=res.read()
            return content.decode() if raw else json.loads(content) if content else None
        def wait_job(j,token='demo-owner'):
            deadline=time.monotonic()+(240 if real else 60)
            while time.monotonic()<deadline:
                job=api('/api/persona-jobs/'+j['id'],token=token)
                if job['status']=='SUCCEEDED':return job['result']
                if job['status']=='FAILED':raise AssertionError('Job failed: '+str(job['error_code']))
                time.sleep(.25)
            raise AssertionError('Job timeout')
        def forbidden(path,token):
            try:api(path,token=token)
            except urllib.error.HTTPError as e:assert e.code==403
            else:raise AssertionError('private resource exposed')
        log=open(Path(tmp)/'runtime.log','w')
        def start():
            processes.append(subprocess.Popen([java,'-jar',str(runtime_jar)],cwd=ROOT,env=env,stdout=log,stderr=log))
            deadline=time.monotonic()+30
            while time.monotonic()<deadline:
                try:api('/health');break
                except OSError:time.sleep(.2)
            else:raise AssertionError('API startup failed')
            processes.append(subprocess.Popen([__import__('sys').executable,str(ROOT/'worker/worker.py')],cwd=ROOT,env=env,stdout=log,stderr=log))
        def stop():
            for p in reversed(processes):
                p.terminate()
                try:p.wait(timeout=12)
                except subprocess.TimeoutExpired:p.kill();p.wait()
            processes.clear()
        try:
            start();project=api('/api/projects',{'title':'虚构人物验收空间'})['id'];api(f'/api/projects/{project}/members',{'user_id':'viewer','role':'VIEWER'},method='PUT')
            ids=[]
            for family in ('relationship','celebrity','colleague'):
                p=api(f'/api/projects/{project}/personas',{'name':'小溪','family':family,'origin':'original','background':'虚构测试人物，没有真实个人资料。','personality':'温和，尊重交流节奏。','expression':'自然简洁。','relationship':'朋友','boundaries':'不冒充真人','consent':False})
                version=wait_job(api('/api/personas/'+p['id']+'/generate',{'material_ids':[]}))['version_id'];ids.append(p['id'])
                api(f"/api/personas/{p['id']}/versions/{version}/review",{'approve':True})
            persona=ids[0]
            if not real:
                boundary='fixture-knowledge-upload';text='消息延迟先检查队列积压，再核对消费速率。'
                body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="knowledge.md"\r\nContent-Type: text/plain\r\n\r\n{text}\r\n--{boundary}--\r\n').encode()
                req=urllib.request.Request(base+f'/api/projects/{project}/materials',body,{'Authorization':'Bearer demo-owner','Content-Type':'multipart/form-data; boundary='+boundary})
                with urllib.request.urlopen(req) as response:material=json.load(response)['id']
                req=urllib.request.Request(base+f'/api/projects/{project}/materials',body.replace(b'knowledge.md',b'personality.md'),{'Authorization':'Bearer demo-owner','Content-Type':'multipart/form-data; boundary='+boundary})
                with urllib.request.urlopen(req) as response:second_material=json.load(response)['id']
                library=api(f'/api/projects/{project}/personalities',{'name':'倾听型人格','family':'relationship','origin':'authorized','consent':True,'personality':'自然、善于倾听'})['id']
                personality_version=wait_job(api('/api/personas/'+library+'/generate',{'material_ids':[material,second_material]}))['version_id']
                api(f'/api/personas/{library}/versions/{personality_version}/review',{'approve':True})
                api('/api/personas/'+persona+'/personality',{'personality_id':library,'version_id':personality_version},method='PUT')
                assert library not in [p['id'] for p in api(f'/api/projects/{project}/personas')]
                assert len(api('/api/personas/'+library)['versions'][0]['content']['source_material_ids'])==2
                topic=api(f'/api/projects/{project}/profiles',{'title':'消息排障知识'})['id'];work_job=api(f'/api/profiles/{topic}/distill',{'material_ids':[material]})
                deadline=time.monotonic()+60
                while time.monotonic()<deadline:
                    work=api('/api/jobs/'+work_job['id'])
                    if work['status']=='SUCCEEDED':break
                    if work['status']=='FAILED':raise AssertionError('Knowledge generation failed')
                    time.sleep(.25)
                assert work['status']=='SUCCEEDED';api('/api/versions/'+work['result']['version_id']+'/review',{'approve':True})
                api('/api/personas/'+persona+'/knowledge',{'profile_ids':[topic]},method='PUT')
                kchat=api('/api/personas/'+persona+'/conversations',{},token='demo-viewer')['id']
                grounded=wait_job(api('/api/conversations/'+kchat+'/messages',{'message':'消息延迟怎么排查？'},token='demo-viewer'),'demo-viewer')
                assert len(grounded['citations'])==1 and grounded['citations'][0]['profile_id']==topic and '队列积压' in grounded['answer']
                unbound=api('/api/personas/'+ids[1]+'/conversations',{},token='demo-viewer')['id'];answer=wait_job(api('/api/conversations/'+unbound+'/messages',{'message':'消息延迟怎么排查？'},token='demo-viewer'),'demo-viewer');assert answer['citations']==[]
            c=api(f'/api/personas/{persona}/conversations',{},token='demo-viewer')['id']
            result=wait_job(api(f'/api/conversations/{c}/messages',{'message':'请记住，我喜欢安静散步。'},token='demo-viewer'),'demo-viewer')
            assert result['answer'];memories=api(f'/api/personas/{persona}/memories',token='demo-viewer')
            if memories:
                assert memories[0]['status']=='PENDING';api(f"/api/personas/{persona}/memories/{memories[0]['id']}",{'status':'CONFIRMED'},token='demo-viewer',method='PUT')
                wait_job(api(f'/api/conversations/{c}/messages',{'message':'我们去哪里散步呢？'},token='demo-viewer'),'demo-viewer')
                if not real:
                    assert Gateway.memories[-1]
                    snapshot=Gateway.contexts[-1]
                    original_version=snapshot['persona_version_id']
                    original_identity=snapshot['persona']
                    assert snapshot['memories'][0]['origin']=='confirmed_interaction'
                    # Fixed identity must survive a new published version and a long conversation.
                    next_version=api(f'/api/personas/{persona}/versions/{original_version}/revise',
                                     {'persona_markdown':'# 小溪\n合成新版人格：更详细表达。','correction':'合成版本比较'})['version_id']
                    api(f'/api/personas/{persona}/versions/{next_version}/review',{'approve':True})
                    for _ in range(3):
                        wait_job(api(f'/api/conversations/{c}/messages',{'message':'合成长对话填充：'+'字'*3900},token='demo-viewer'),'demo-viewer')
                    relevant=wait_job(api(f'/api/conversations/{c}/messages',{'message':'安静散步的偏好是什么？'},token='demo-viewer'),'demo-viewer')
                    snapshot=Gateway.contexts[-1]
                    assert snapshot['persona_version_id']==original_version
                    assert snapshot['persona']==original_identity
                    assert snapshot['context_budget']['omitted_history']>0
                    assert relevant['memory_citations']==[memories[0]['id']]
                    assert relevant['context_budget']['estimated_input_units']<=relevant['context_budget']['input_limit']
                    unknown=wait_job(api(f'/api/conversations/{c}/messages',{'message':'我们在巴黎演唱会见面是哪一天？'},token='demo-viewer'),'demo-viewer')
                    assert '没有足够的已确认记录' in unknown['answer']
                    assert unknown['memory_citations']==[] and unknown['experience_citations']==[]
                    isolated=api(f'/api/personas/{persona}/conversations',{})['id']
                    wait_job(api(f'/api/conversations/{isolated}/messages',{'message':'安静散步的偏好是什么？'}))
                    assert Gateway.contexts[-1]['memories']==[]
                    other=api(f'/api/personas/{ids[1]}/conversations',{},token='demo-viewer')['id']
                    wait_job(api(f'/api/conversations/{other}/messages',{'message':'安静散步的偏好是什么？'},token='demo-viewer'),'demo-viewer')
                    assert Gateway.contexts[-1]['memories']==[]
                    Gateway.stages.add('grounded-context')
                    cancelled=api(f'/api/personas/{persona}/conversations',{},token='demo-viewer')['id']
                    slow=api(f'/api/conversations/{cancelled}/messages',{'message':'合成取消慢流'},token='demo-viewer')
                    deadline=time.monotonic()+15
                    while time.monotonic()<deadline:
                        state=api('/api/persona-jobs/'+slow['id'],token='demo-viewer')
                        if state.get('partial_reply'):break
                        time.sleep(.1)
                    else:raise AssertionError('slow stream never produced a partial reply')
                    api('/api/persona-jobs/'+slow['id']+'/cancel',{},token='demo-viewer')
                    assert Gateway.cancelled_stream_closed.wait(5), 'Worker kept reading a cancelled provider stream'
                    assert Gateway.cancel_frames<Gateway.cancel_total_frames
                    assert api('/api/persona-jobs/'+slow['id'],token='demo-viewer')['status']=='CANCELLED'
                    assert len(api('/api/conversations/'+cancelled,token='demo-viewer')['messages'])==1
                    # The same serial Worker can proceed to another chat after aborting the old stream.
                    resumed=wait_job(api(f'/api/conversations/{cancelled}/messages',{'message':'合成取消后继续聊天'},token='demo-viewer'),'demo-viewer')
                    assert resumed['answer']
                    exiting=api(f'/api/personas/{persona}/conversations',{},token='demo-viewer')['id']
                    held=api(f'/api/conversations/{exiting}/messages',{'message':'合成退出慢流'},token='demo-viewer')
                    deadline=time.monotonic()+15
                    while time.monotonic()<deadline:
                        state=api('/api/persona-jobs/'+held['id'],token='demo-viewer')
                        if state.get('partial_reply'):break
                        time.sleep(.1)
                    else:raise AssertionError('shutdown fixture never started streaming')
                    active_worker=processes[-1];active_worker.terminate();active_worker.wait(timeout=12)
                    state=api('/api/persona-jobs/'+held['id'],token='demo-viewer')
                    assert state['status']=='QUEUED' and state['attempt']==0 and state['partial_reply']==''
                    assert Gateway.worker_exit_closed.wait(5)
                    assert len(api('/api/conversations/'+exiting,token='demo-viewer')['messages'])==1
                    processes.append(subprocess.Popen([__import__('sys').executable,str(ROOT/'worker/worker.py')],cwd=ROOT,env=env,stdout=log,stderr=log))
                    recovered=wait_job(held,'demo-viewer')
                    assert recovered['answer'] and api('/api/persona-jobs/'+held['id'],token='demo-viewer')['attempt']==1
                    assert len(api('/api/conversations/'+exiting,token='demo-viewer')['messages'])==2
                    # Inject completion transport faults only after all ordinary workers have stopped.
                    processes[-1].terminate();processes[-1].wait(timeout=12)
                    for fault in ('after-commit','before-commit'):
                        ack_session=api(f'/api/personas/{persona}/conversations',{},token='demo-viewer')['id']
                        marker='合成完成确认故障-'+fault
                        ack_job=api('/api/conversations/'+ack_session+'/messages',{'message':marker},token='demo-viewer')
                        counter=Path(tmp)/('completion-'+fault+'.json')
                        child=subprocess.Popen([__import__('sys').executable,str(ROOT/'tests/fault_worker.py'),'--job-id',ack_job['id'],
                                                '--fault',fault,'--counter-file',str(counter)],cwd=ROOT,env=env,stdout=log,stderr=log)
                        processes.append(child)
                        ack_result=wait_job(ack_job,'demo-viewer')
                        deadline=time.monotonic()+15
                        while time.monotonic()<deadline:
                            try:stats=json.loads(counter.read_text())
                            except (OSError,ValueError):stats={}
                            if stats.get('completion_acks')==1:break
                            if child.poll() is not None:raise AssertionError('fault Worker exited unexpectedly')
                            time.sleep(.1)
                        else:raise AssertionError('completion replay was not acknowledged')
                        assert stats['completion_requests']==2 and stats['injected_faults']==1
                        assert len(set(stats['payload_digests']))==1 and len(set(stats['result_digests']))==1
                        assert Gateway.chat_calls[marker]==1, 'completion failure regenerated the model response'
                        session=api('/api/conversations/'+ack_session,token='demo-viewer')
                        assert len(session['messages'])==2 and session['messages'][-1]['id']==ack_result['message_id']
                        assert session['emotion']['previous']=='calm'
                        child.terminate();child.wait(timeout=12)
                    processes.append(subprocess.Popen([__import__('sys').executable,str(ROOT/'worker/worker.py')],cwd=ROOT,env=env,stdout=log,stderr=log))
                    Gateway.stages.add('completion-replay')
                    # Kill only the isolated fixture process. Let real lease time pass, without SQL mutation.
                    killed_session=api(f'/api/personas/{persona}/conversations',{},token='demo-viewer')['id']
                    killed_job=api('/api/conversations/'+killed_session+'/messages',{'message':'合成强杀慢流'},token='demo-viewer')
                    deadline=time.monotonic()+15
                    while time.monotonic()<deadline:
                        state=api('/api/persona-jobs/'+killed_job['id'],token='demo-viewer')
                        if state.get('partial_reply'):break
                        time.sleep(.1)
                    else:raise AssertionError('SIGKILL fixture never began streaming')
                    processes[-1].kill();processes[-1].wait(timeout=5)
                    state=api('/api/persona-jobs/'+killed_job['id'],token='demo-viewer')
                    assert state['status']=='RUNNING' and state['attempt']==1
                    assert Gateway.worker_kill_closed.wait(5)
                    assert len(api('/api/conversations/'+killed_session,token='demo-viewer')['messages'])==1
                    processes.append(subprocess.Popen([__import__('sys').executable,str(ROOT/'worker/worker.py')],cwd=ROOT,env=env,stdout=log,stderr=log))
                    recovered=wait_job(killed_job,'demo-viewer')
                    assert recovered['answer'] and api('/api/persona-jobs/'+killed_job['id'],token='demo-viewer')['attempt']==2
                    assert Gateway.chat_calls['合成强杀慢流']==2
                    assert len(api('/api/conversations/'+killed_session,token='demo-viewer')['messages'])==2
                    cancelled_session=api(f'/api/personas/{persona}/conversations',{},token='demo-viewer')['id']
                    abandoned=api('/api/conversations/'+cancelled_session+'/messages',{'message':'合成强杀后取消'},token='demo-viewer')
                    deadline=time.monotonic()+15
                    while time.monotonic()<deadline:
                        state=api('/api/persona-jobs/'+abandoned['id'],token='demo-viewer')
                        if state.get('partial_reply'):break
                        time.sleep(.1)
                    else:raise AssertionError('cancelled SIGKILL fixture never began streaming')
                    processes[-1].kill();processes[-1].wait(timeout=5)
                    api('/api/persona-jobs/'+abandoned['id']+'/cancel',{},token='demo-viewer')
                    processes.append(subprocess.Popen([__import__('sys').executable,str(ROOT/'worker/worker.py')],cwd=ROOT,env=env,stdout=log,stderr=log))
                    until=time.monotonic()+5
                    while time.monotonic()<until:
                        assert api('/api/persona-jobs/'+abandoned['id'],token='demo-viewer')['status']=='CANCELLED'
                        assert Gateway.chat_calls['合成强杀后取消']==1
                        time.sleep(.2)
                    assert len(api('/api/conversations/'+cancelled_session,token='demo-viewer')['messages'])==1
                    continuing=wait_job(api('/api/conversations/'+cancelled_session+'/messages',{'message':'合成强杀取消后新输入'},token='demo-viewer'),'demo-viewer')
                    assert continuing['answer'] and api('/api/persona-jobs/'+abandoned['id'],token='demo-viewer')['status']=='CANCELLED'
                    assert Gateway.chat_calls['合成强杀后取消']==1
                    Gateway.stages.add('sigkill-recovery')




            elif not real:raise AssertionError('missing memory')
            forbidden('/api/conversations/'+c,'demo-owner');assert api(f'/api/personas/{persona}/memories')==[]
            image=wait_job(api(f'/api/personas/{persona}/image',{'prompt':'synthetic portrait'}));assert image['avatar_saved'];assert api('/api/personas/'+persona)['avatar'].startswith('data:image/png;')
            speech=wait_job(api(f'/api/personas/{persona}/speech',{'operation':'TTS','text':'你好，小溪。'},token='demo-viewer'),'demo-viewer');assert speech['audio'].startswith('data:audio/wav;')
            transcript=wait_job(api(f'/api/personas/{persona}/speech',{'operation':'ASR','audio':'data:audio/wav;base64,'+base64.b64encode(WAV).decode()},token='demo-viewer'),'demo-viewer');assert transcript['text']=='今天想去散步。'
            if not real:wait_job(api(f'/api/conversations/{c}/messages',{'message':'这张图片里有什么？','image':'data:image/png;base64,'+PNG},token='demo-viewer'),'demo-viewer')
            skill=api(f'/api/personas/{persona}/export',token='demo-viewer',raw=True);assert '# ' in skill and 'fixture-key' not in skill
            if not real:
                truncated=api(f'/api/projects/{project}/personas',{'name':'合成截断人物','family':'relationship','origin':'original','background':'仅用于截断验收','personality':'温和','expression':'短句','boundaries':'不补造经历','consent':False})['id']
                truncated_job=api('/api/personas/'+truncated+'/generate',{'material_ids':[]})
                deadline=time.monotonic()+20
                while time.monotonic()<deadline:
                    failed=api('/api/persona-jobs/'+truncated_job['id'])
                    if failed['status']=='FAILED':break
                    assert failed['status']!='SUCCEEDED','truncated persona was accepted'
                    time.sleep(.2)
                else:raise AssertionError('truncated persona did not fail')
                assert failed['error_code']=='MODEL_OUTPUT_LIMIT' and failed['attempt']==1
                assert api('/api/personas/'+truncated)['versions']==[]
                api('/api/personas/'+truncated,method='DELETE')
            stop();start();assert len(api(f'/api/projects/{project}/personas'))==3;assert api('/api/conversations/'+c,token='demo-viewer')['messages'];assert api('/api/personas/'+persona)['avatar']
            api('/api/conversations/'+c,token='demo-viewer',method='DELETE');assert api(f'/api/personas/{persona}/memories',token='demo-viewer')==[]
            if not real:
                assert api('/api/personas/'+persona)['versions'][0]['content']['binding']['personality_id']==library
                assert any(k['selected'] for k in api('/api/personas/'+persona+'/knowledge'))
                assert Gateway.stages=={'analyze','build','chat','stream','tts','asr','vision','distill','knowledge','grounded-context','cancelled-stream','graceful-release','completion-replay','sigkill-recovery'}
            print('PASS: explicit text-stage budgets, truncated persona never becomes a version, SIGKILL lease-expiry recovery and cancelled task never revives, committed/lost-ack and before-commit completion replay without model regeneration, graceful SIGTERM lease release and restart without duplicate reply, cancelled provider stream closes early and next chat proceeds, grounded context budget, pinned style, relevant recall, unknown experience, user/persona isolation, configured workspace knowledge and citations, three families, two-stage persona generation, publication, private chat/memory, SSE deltas, image/vision/speech gateways, Skill export, restart, deletion'+(' (real text model + fixture image/vision/speech)' if real else ' (HTTP gateway fixtures)'))
        except Exception:
            # Do not print model content, credentials or material from runtime logs.
            print('Persona E2E failed; temporary runtime logs are cleaned up after process shutdown.')
            raise
        finally:stop();log.close();gateway.shutdown();gateway.server_close()

if __name__=='__main__':main()
