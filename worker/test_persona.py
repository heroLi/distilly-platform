import json
import unittest
from unittest.mock import Mock, patch
import persona
from worker import Model


class PersonaTests(unittest.TestCase):
    def payload(self):
        return {'definition': {'name':'小溪','family':'relationship','origin':'original'}, 'sources':[]}
    def test_two_stage_prompt_flow(self):
        model=Mock(mode='gateway')
        model.call.side_effect=[{'analysis':{'expression':'温和'},'evidence':[]},{'persona_markdown':'# 人物\n温和表达'}]
        out=persona.generate(model,self.payload())
        self.assertEqual(out['analysis']['expression'],'温和')
        self.assertEqual(model.call.call_count,2)
        self.assertIn('Relationship Persona Analyzer',model.call.call_args_list[0].args[0])
        self.assertIn('Relationship Persona Builder',model.call.call_args_list[1].args[0])
        self.assertEqual(model.call.call_args_list[1].args[1]['analysis'],{'expression':'温和'})
    def test_all_families_and_mock(self):
        for family in ('relationship','celebrity','colleague'):
            p=self.payload();p['definition']['family']=family
            result=persona.generate(Model(mode='mock'),p)
            self.assertIn('模拟人格',result['persona_markdown'])
        with self.assertRaises(ValueError):persona.prompts('../../outside')
    def test_unverified_material_blocks_builder(self):
        model=Mock(mode='gateway');model.call.return_value={'analysis':{},'evidence':[{'material_id':'x','quote':'编造引用'}]}
        with self.assertRaises(ValueError):persona.generate(model,self.payload())
        self.assertEqual(model.call.call_count,1)
    def test_mock_remembers_only_explicit_input(self):
        p={'message':'请记住，我喜欢安静散步。','memory_enabled':True,'memories':[]}
        out=persona.chat(Model(mode='mock'),p)
        self.assertEqual(len(out['memory_candidates']),1)
        p['memory_enabled']=False
        self.assertEqual(persona.chat(Model(mode='mock'),p)['memory_candidates'],[])
    def test_inferred_memory_rejected(self):
        model=Mock(mode='gateway');model.call.return_value={'answer':'你好','memory_candidates':[{'body':'虚构','quote':'不存在的经历'}]}
        with self.assertRaises(ValueError):persona.chat(model,{'message':'今天不错','memories':[]})
    def test_unconfigured_image_has_explicit_error(self):
        with patch.dict('os.environ',{'IMAGE_BASE_URL':'','IMAGE_MODEL':'','IMAGE_API_KEY':''}):
            with self.assertRaises(persona.MediaNotConfigured):persona.image(Mock(),{'prompt':'portrait'})
    def test_shared_knowledge_keeps_persona_and_validates_citations(self):
        p={'message':'消息延迟如何排查？','memory_enabled':False,'memories':[],'knowledge':[{'id':'source-1','text':'先检查队列积压。'}]}
        out=persona.chat(Model(mode='mock'),p);self.assertIn('队列积压',out['answer']);self.assertEqual(out['citations'],['source-1'])
        model=Mock(mode='gateway');model.call.return_value={'answer':'先检查队列','memory_candidates':[],'citations':['forged']}
        with self.assertRaises(ValueError):persona.chat(model,p)
        model.call.return_value={'answer':'先检查队列','memory_candidates':[],'citations':['source-1']};self.assertEqual(persona.chat(model,p)['citations'],['source-1'])
        self.assertIn('不把它当作人物亲身经历',model.call.call_args.args[0])
    def test_vendored_hashes(self):
        from pathlib import Path
        import hashlib
        root=Path(__file__).resolve().parents[1]/'vendor/distilly'
        manifest=json.loads((root/'UPSTREAM.json').read_text())
        for name,digest in manifest['vendored_files'].items():
            self.assertEqual(hashlib.sha256((root/name).read_bytes()).hexdigest(),digest)

class GroundedContextTests(unittest.TestCase):
    def payload(self):
        return {'message': '青岛海边拍照', 'memory_enabled': True, 'history': [],
                'persona': {'persona_markdown': '短句，先倾听再追问', 'definition': {'name': '小溪', 'expression': '短句'}},
                'memories': [{'id': 'memory-1', 'body': '确认过的青岛拍照', 'origin': 'confirmed_interaction'}],
                'experiences': [{'id': 'experience-1', 'text': '原创摄影设定', 'origin': 'fictional_setting'}], 'knowledge': []}

    def result(self):
        return {'answer': '合成回应', 'memory_candidates': [], 'citations': [],
                'memory_citations': ['memory-1'], 'experience_citations': ['experience-1']}

    def test_grounded_sources_and_style_contract(self):
        model = Mock(mode='gateway'); model.call.return_value = self.result()
        result = persona.chat(model, self.payload())
        self.assertEqual(result['memory_citations'], ['memory-1'])
        system, payload = model.call.call_args.args
        self.assertIn('稳定表达约束', system)
        self.assertIn('目前没有足够的已确认记录', system)
        self.assertIn('不自动证明是当前人物的亲历', system)
        self.assertEqual(payload['persona']['definition']['expression'], '短句')
        for key in ('memory_citations', 'experience_citations'):
            output = self.result(); output[key] = ['forged']; model.call.return_value = output
            with self.assertRaises(ValueError): persona.chat(model, self.payload())

    def test_worker_rechecks_budget_and_caps_output(self):
        p = self.payload()
        p['context_budget'] = {'total_units': 32768, 'system_reserve': 8192, 'output_tokens': 2048,
                               'image_reserve': 0, 'input_limit': 22528}
        model = Mock(mode='gateway'); model.call.return_value = self.result()
        persona.chat(model, p)
        self.assertEqual(model.call.call_args.kwargs['max_output_tokens'], 2048)
        p['history'] = [{'role': 'user', 'body': '长消息' * 10000}]
        with self.assertRaises(ValueError): persona.chat(model, p)
        self.assertEqual(model.call.call_count, 1)

    def test_unknown_experience_rejects_claimed_citation(self):
        p = self.payload(); p['message'] = '我们在巴黎哪天见面？'; p['memories'] = []; p['experiences'] = []
        model = Mock(mode='gateway'); model.call.return_value = self.result()
        with self.assertRaises(ValueError): persona.chat(model, p)
        model.call.return_value = {'answer': '目前没有足够的已确认记录，你能补充背景吗？', 'memory_candidates': [],
                                  'citations': [], 'memory_citations': [], 'experience_citations': []}
        self.assertIn('没有足够', persona.chat(model, p)['answer'])

class PersonaCancellationTests(unittest.TestCase):
    def test_stopped_worker_does_not_start_new_provider_call_or_report_failure(self):
        import threading
        model = Mock(mode='gateway')
        model.call.return_value = {'answer': '不应调用', 'memory_candidates': [], 'citations': []}
        runner = Mock(model=model); runner.stop = threading.Event(); runner.stop.set()
        persona.execute(runner, {'id': 'cancelled-fixture', 'kind': 'CHAT', 'lease_token': 'fixture', 'lease_seconds': 300,
                                 'payload': {'message': '合成请求', 'memories': []}})
        model.call.assert_not_called()
        runner.request.assert_called_once_with('/internal/persona-jobs/cancelled-fixture/release', {'lease_token': 'fixture'})

class PersonaCompletionAcknowledgementTests(unittest.TestCase):
    def test_committed_chat_ack_is_replayed_even_when_heartbeat_sees_terminal_lease(self):
        import os, threading, urllib.error
        from worker import Worker
        model=Mock(mode='gateway');model.call.return_value={'answer':'合成已保存回复','memory_candidates':[],'citations':[]}
        with patch.dict(os.environ,{'PLATFORM_WORKER_TOKEN':'persona-ack-fixture-token-32-characters'}):runner=Worker(model)
        committed=threading.Event();heartbeat_seen=threading.Event();completions=[];failures=[]
        def request(path,payload,**unused):
            if path.endswith('/heartbeat') and committed.is_set():
                heartbeat_seen.set();raise urllib.error.HTTPError('http://fixture',409,'synthetic already complete',{},None)
            if path.endswith('/complete'):
                completions.append(json.loads(json.dumps(payload)))
                if len(completions)==1:
                    committed.set();assert heartbeat_seen.wait(3)
                    raise ConnectionResetError('synthetic commit ack lost')
                return {'message_id':'stored-once'}
            if path.endswith('/fail'):failures.append(payload)
        with patch.object(runner,'request',side_effect=request):
            persona.execute(runner,{'id':'persona-ack','kind':'CHAT','lease_token':'fixture','lease_seconds':1,'payload':{'message':'合成输入','memories':[]}})
        self.assertEqual(len(completions),2);self.assertEqual(completions[0],completions[1]);self.assertEqual(failures,[])
        model.call.assert_called_once()

if __name__=='__main__':unittest.main()
