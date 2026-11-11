#!/usr/bin/env python3
"""Native synthetic source readability; actual mock, no excerpt/semantic claims.

Only test data enters the loopback API. A clean child imports Worker/browser code.
Default runs retain a fresh subdirectory; --output uses the exact requested path.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import time

ROOT=Path(__file__).resolve().parents[1]


def select_output(requested):
    if requested is not None:
        return requested.resolve()
    base=ROOT/'server/target/long-memory-source-e2e'
    base.mkdir(parents=True,exist_ok=True)
    return Path(tempfile.mkdtemp(prefix='run-',dir=base))


def run(output):
    OUT=output
    OUT.mkdir(parents=True,exist_ok=True)
    marker='仅在2027年3月预约成功且获许可时参加；从未获许可时绝不参加。'
    message=('请记住🙂：合成青砾展的早期安排。'+'合成背景填充。'*48+marker
             +'<img src=x onerror="window.__longQuoteXss=true">')
    SPEC={'message':message,'tail_marker':marker,
          'viewports':[{'width':1360,'height':950},{'width':390,'height':844}]}
    sys.path.insert(0,str(ROOT/'tests'));sys.path.insert(0,str(ROOT/'worker'))
    from browser_support import launch_chromium
    from playwright.sync_api import sync_playwright, expect
    from worker import Model,Worker
    import persona
    loader=importlib.util.spec_from_file_location('scenario_runtime',ROOT/'tests/text_scenarios_e2e.py')
    scenarios=importlib.util.module_from_spec(loader);loader.loader.exec_module(scenarios)
    result={'status':'RUNNING','real_provider_calls':0,'browser':'native Chromium','cases':[],
            'page_errors':[],'external_requests':[],'screenshots':{}}
    def require(value,code):
        if not value:raise scenarios.ContractFailure(code)
    def wait(fn):
        until=time.monotonic()+15
        while time.monotonic()<until:
            if fn():return
            time.sleep(.05)
        raise scenarios.ContractFailure('bounded_ui_condition_timeout')

    with tempfile.TemporaryDirectory(prefix='long-quote-ui-synthetic-') as tmp:
        folder=Path(tmp);shutil.copy2(ROOT/'server/target/distilly-platform-0.1.0.jar',folder/'runtime.jar')
        with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
        base='http://127.0.0.1:'+str(port)
        os.environ.update(PLATFORM_API_URL=base,PLATFORM_WORKER_TOKEN=scenarios.TOKEN,MODEL_MODE='mock')
        model=Model(mode='mock')
        def forbid_gateway(*args,**kwargs):raise scenarios.ContractFailure('unexpected_gateway_call')
        model.call=forbid_gateway;runtime=scenarios.Runtime(base,Worker(model))
        env=dict(os.environ,PLATFORM_DEMO='true',PLATFORM_WORKER_TOKEN=scenarios.TOKEN,MODEL_MODE='mock',
            PLATFORM_PORT=str(port),PLATFORM_BIND='127.0.0.1',PLATFORM_DB_URL='jdbc:h2:file:'+str(folder/'db')+';DATABASE_TO_LOWER=TRUE',PLATFORM_DB_USER='sa',PLATFORM_DB_PASSWORD='')
        log=(OUT/'server.log').open('x');server=subprocess.Popen([env['JAVA_BIN'],'-jar',str(folder/'runtime.jar')],env=env,stdout=log,stderr=subprocess.STDOUT)
        try:
            start=time.monotonic()
            while time.monotonic()-start<30:
                require(server.poll() is None,'server_exited')
                try:
                    if runtime.api('/health')=={'status':'UP'}:break
                except OSError:pass
                time.sleep(.1)
            else:raise scenarios.ContractFailure('default_startup_timeout')
            result['startup_seconds']=round(time.monotonic()-start,3)
            project=runtime.api('/api/projects',{'title':'合成长引文确认边界'})['id']
            runtime.api(f'/api/projects/{project}/members',{'user_id':'viewer','role':'VIEWER'},method='PUT')
            person,version=runtime.create_persona(project,{'name':'合成长正文伙伴','family':'relationship','origin':'original','boundaries':'仅合成资料'})
            conversation=runtime.api(f'/api/personas/{person}/conversations',{},actor='viewer')['id']
            message=SPEC['message'];marker=SPEC['tail_marker']
            job=runtime.api(f'/api/conversations/{conversation}/messages',{'message':message},actor='viewer')
            claimed=runtime.execute(job);runtime.result(job,actor='viewer')
            actual=persona.chat(model,claimed['payload'])['memory_candidates'][0]
            require(actual['body']==message and marker not in actual['quote'],'quote_control_must_omit_tail_only')
            result['quote_utf16_units']=len(actual['quote'].encode('utf-16-le'))//2
            rows=runtime.api(f'/api/personas/{person}/memories',actor='viewer');require(len(rows)==1 and rows[0]['body']==message and rows[0]['status']=='PENDING','full_pending_body')
            require('quote' not in rows[0],'no_excerpt_quote_field_in_confirmation_record')
            memory=rows[0]['id'];source=f'/api/personas/{person}/memories/{memory}/source'
            runtime.api(source,expected=404)
            with sync_playwright() as pw:
                browser=launch_chromium(pw);pages=[]
                def page_for(viewport):
                    page=browser.new_page(viewport=viewport);pages.append(page)
                    page.on('pageerror',lambda e:result['page_errors'].append(type(e).__name__))
                    page.route('**/*',lambda route:route.continue_() if route.request.url.startswith(base+'/') or route.request.url.startswith('data:') else (result['external_requests'].append('blocked'),route.abort()))
                    page.goto(base);page.locator('#token').fill('demo-viewer');page.locator('#login-form button').click()
                    page.locator('#projects').select_option(project);page.locator(f'[data-person="{person}"]').click();page.locator('[data-view="chat"]').click()
                    expect(page.locator('[data-memory-body]')).to_have_value(message)
                    return page
                def capture(page,name,locator=None):
                    (locator or page).screenshot(path=str(OUT/(name+'.png')),**({} if locator else {'full_page':True}))
                    result['screenshots'][name]=name+'.png'
                def source_dialog(page):
                    page.locator('[data-memory="source"]').click();expect(page.locator('#memory-source-body')).to_have_text(message)
                    dialog=page.locator('#memory-source-dialog');expect(dialog).to_be_visible()
                    require(page.locator('#memory-source-body').evaluate('(e)=>e.scrollWidth<=e.clientWidth+1'),'source_horizontal_overflow_hides_qualifiers')
                    require(dialog.evaluate('(e)=>e.scrollWidth<=e.clientWidth+1'),'dialog_horizontal_overflow')
                    expect(dialog).to_contain_text('这是当时用户发送的来源，不代表纠错后的记忆正文或真实事实')
                    require(dialog.locator('img,script').count()==0,'source_html_must_remain_text')
                    require(page.evaluate('()=>window.__longQuoteXss!==true'),'source_must_not_execute')
                    return dialog
                desktop=page_for(SPEC['viewports'][0]);editor=desktop.locator('[data-memory-body]')
                dimensions=editor.evaluate('(e)=>({scroll:e.scrollHeight,client:e.clientHeight})')
                require(dimensions['scroll']>dimensions['client'],'long_body_uses_native_scroll')
                editor.focus();editor.press('Control+End')
                capture(desktop,'desktop-candidate-tail',editor.locator('..'))
                capture(desktop,'desktop-original-source',source_dialog(desktop));desktop.locator('#memory-source-close').click()
                result['cases'].append('desktop full pending body retained beyond quote; native textarea scroll; complete inert original source and distinction notice')
                mobile=page_for(SPEC['viewports'][1]);mobile.locator('[data-memory-body]').focus();mobile.locator('[data-memory-body]').press('Control+End')
                capture(mobile,'mobile-candidate-tail',mobile.locator('[data-memory-body]').locator('..'))
                capture(mobile,'mobile-original-source',source_dialog(mobile));mobile.locator('#memory-source-close').click()
                mobile.locator('[data-memory="confirm"]').click()
                wait(lambda:runtime.api(f'/api/personas/{person}/memories',actor='viewer')[0]['status']=='CONFIRMED')
                require(runtime.api(f'/api/personas/{person}/memories',actor='viewer')[0]['body']==message,'native_confirmation_must_not_shorten')
                result['cases'].append('mobile complete candidate/source and native confirmation preserve full text')
                corrected='用户明确改为合成午后安排。'
                runtime.api(f'/api/personas/{person}/memories/{memory}',{'status':'CONFIRMED','body':corrected},actor='viewer',method='PUT')
                mobile.locator('#memory-refresh').click();expect(mobile.locator('[data-memory-body]')).to_have_value(corrected)
                source_dialog(mobile);mobile.locator('#memory-source-close').click()
                result['cases'].append('explicit correction changes current memory; original source and distinction notice remain intact; other member source404')
                source_dialog(mobile)
                runtime.api(f'/api/personas/{person}',method='DELETE')
                wait(lambda:not mobile.locator('#memory-source-dialog').is_visible() or not mobile.locator('#memory-source-body').text_content())
                require(not mobile.locator('#memory-source-body').text_content(),'revoked_source_must_clear')
                runtime.api(source,actor='viewer',expected=404)
                result['cases'].append('persona archive honored by existing poll/read cleanup; private source cleared and new source404')
                require(not result['page_errors'] and not result['external_requests'],'browser_error_or_external_request')
                for page in pages:page.close()
                browser.close()
            result['status']='PASS'
        except Exception as error:
            result['status']='FAIL';result['failure']=str(error) if isinstance(error,scenarios.ContractFailure) else type(error).__name__
        finally:
            server.terminate()
            try:server.wait(timeout=12)
            except subprocess.TimeoutExpired:server.kill();server.wait(timeout=5)
            log.close();result['server_stopped']=server.poll() is not None
            (OUT/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print('PASS: long-memory-source 4 native desktop/mobile groups; full body/source, safe wrapping, confirmation/correction, archive cleanup; model calls0' if result['status']=='PASS' else 'FAIL: long-memory-source '+result.get('failure','unknown'))
    return 0 if result['status']=='PASS' else 1


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,help='Exact evidence directory; default retains a fresh run subdirectory')
    parser.add_argument('--isolated-child',action='store_true',help=argparse.SUPPRESS)
    args=parser.parse_args()
    if args.isolated_child:
        if args.output is None:parser.error('--isolated-child requires --output')
        return run(args.output.resolve())
    output=select_output(args.output)
    print('EVIDENCE: long-memory-source '+str(output),flush=True)
    safe={k:os.environ[k] for k in ('HOME','LANG','LC_ALL','TMPDIR','PATH','JAVA_HOME','JAVA_BIN','DISTILLY_TEST_CHROMIUM') if k in os.environ}
    return subprocess.run([sys.executable,str(Path(__file__).resolve()),'--isolated-child','--output',str(output)],cwd=ROOT,env=safe).returncode


if __name__=='__main__':
    raise SystemExit(main())
