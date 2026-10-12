import io
import json
import os
import threading
import unittest
from unittest.mock import Mock, patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import worker
import persona


class ProtocolTests(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {"MODEL_ALLOW_HTTP": "true", "MODEL_JSON_MODE": "false", "MODEL_STREAMING": "true"}, clear=True)
        self.env.start(); self.addCleanup(self.env.stop)
        self.observed = []; self.result = {"answer": "synthetic", "claims": []}; self.reason = "stop"; self.status = 200
        test = self
        class Gateway(BaseHTTPRequestHandler):
            def log_message(self, *unused): pass
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                test.observed.append(body)
                if test.status != 200: self.send_error(test.status); return
                raw = json.dumps({"choices": [{"finish_reason": test.reason, "message": {"content": json.dumps(test.result)}}]}).encode()
                self.send_response(200);self.send_header('Content-Type', 'application/json');self.send_header('Content-Length', str(len(raw)));self.end_headers();self.wfile.write(raw)
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Gateway)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close); self.addCleanup(self.server.shutdown)

    def model(self, **kwargs):
        return worker.Model(mode='gateway', url='http://127.0.0.1:' + str(self.server.server_port) + '/v1', model='fixture', key='fixture', **kwargs)

    def test_default_call_always_sends_an_explicit_limit(self):
        self.model().call('synthetic', {})
        self.assertEqual(self.observed[0].get('max_tokens'), 4096)

    def test_each_non_chat_stage_has_configurable_explicit_budget(self):
        os.environ.update({'MODEL_DISTILL_OUTPUT_TOKENS':'1024', 'MODEL_KNOWLEDGE_ANSWER_OUTPUT_TOKENS':'768',
                           'MODEL_PERSONA_ANALYSIS_OUTPUT_TOKENS':'1536', 'MODEL_PERSONA_BUILD_OUTPUT_TOKENS':'3072'})
        model = self.model(); chunk = {'material_id':'m','chunk_id':'m:0','text':'synthetic evidence'}
        model.distill_chunk(chunk, 'synthetic topic');model.answer({'question':'synthetic','claims':[]})
        self.result = {'analysis':{},'evidence':[], 'persona_markdown':'synthetic persona'}
        persona.generate(model, {'definition':{'family':'relationship','name':'synthetic'},'sources':[]})
        self.assertEqual([request.get('max_tokens') for request in self.observed], [1024,768,1536,3072])

    def test_completion_parameter_and_omitted_temperature_are_preserved(self):
        os.environ.update({'MODEL_OUTPUT_TOKEN_PARAMETER':'max_completion_tokens','MODEL_TEMPERATURE':'omit'})
        self.model().call('synthetic', {}, max_output_tokens=2048)
        self.assertEqual(self.observed[0].get('max_completion_tokens'), 2048)
        self.assertNotIn('max_tokens',self.observed[0]);self.assertNotIn('temperature',self.observed[0])

    def test_vision_has_independent_parameter_and_explicit_chat_limit(self):
        os.environ.update({'VISION_OUTPUT_TOKEN_PARAMETER':'max_completion_tokens','VISION_TEMPERATURE':'omit'})
        self.model(policy_prefix='VISION').call('synthetic',{}, image='data:image/png;base64,fixture', max_output_tokens=1024)
        self.assertEqual(self.observed[0].get('max_completion_tokens'),1024)
        self.assertNotIn('temperature',self.observed[0]); self.assertIsInstance(self.observed[0]['messages'][1]['content'],list)

    def test_invalid_policy_never_falls_back_or_sends_request(self):
        for name,value in [('MODEL_DEFAULT_OUTPUT_TOKENS','0'),('MODEL_DISTILL_OUTPUT_TOKENS','nan'),
                           ('MODEL_PERSONA_BUILD_OUTPUT_TOKENS','32769'),('MODEL_OUTPUT_TOKEN_PARAMETER','none'),
                           ('MODEL_TEMPERATURE','nan'),('MODEL_JSON_MODE','maybe'),('MODEL_STREAMING','sometimes')]:
            with self.subTest(name=name), patch.dict(os.environ,{name:value}):
                with self.assertRaises(ValueError) as error: self.model()
                self.assertEqual(worker.failure_code(error.exception),'MODEL_CONFIGURATION_ERROR')
        self.assertEqual(self.observed,[])

    def test_invalid_explicit_limit_does_not_issue_request(self):
        for limit in (0,-1,True,1.5,32769,'1024'):
            with self.subTest(limit=limit), self.assertRaises(ValueError): self.model().call('synthetic',{},max_output_tokens=limit)
        self.assertEqual(self.observed,[])

    def test_valid_json_with_length_reason_is_not_accepted(self):
        self.reason='length'
        with self.assertRaises(ValueError) as error: self.model().call('synthetic',{})
        self.assertEqual(worker.failure_code(error.exception),'MODEL_OUTPUT_LIMIT')

    def test_missing_or_non_text_completion_reason_is_rejected(self):
        for reason in (None,'content_filter','tool_calls','function_call','unexpected'):
            self.reason=reason
            with self.subTest(reason=reason), self.assertRaises(ValueError): self.model().call('synthetic',{})

    def stream(self, reason='stop', done=True, error=False):
        content=json.dumps({'answer':'synthetic'})
        frames=[{'choices':[{'delta':{'content':content}}]}]
        if error:frames.append({'error':{'message':'synthetic gateway error'}})
        if reason is not None:frames.append({'choices':[{'delta':{},'finish_reason':reason}]})
        data=[('data: '+json.dumps(frame)+'\n\n').encode() for frame in frames]
        if done:data.append(b'data: [DONE]\n\n')
        response=Mock();response.headers={'Content-Type':'text/event-stream'};response.__enter__=Mock(return_value=response);response.__exit__=Mock(return_value=False);response.__iter__=Mock(return_value=iter(data))
        opener=Mock();opener.open.return_value=response
        return opener

    def test_stream_requires_normal_finish_and_done_marker(self):
        with patch('urllib.request.build_opener',return_value=self.stream()):
            self.assertEqual(self.model().call('synthetic',{},on_chunk=lambda unused:None)['answer'],'synthetic')
        for reason,done in ((None,True),('stop',False),(None,False)):
            with self.subTest(reason=reason,done=done), patch('urllib.request.build_opener',return_value=self.stream(reason,done)):
                with self.assertRaises(ValueError):self.model().call('synthetic',{},on_chunk=lambda unused:None)

    def test_stream_length_is_reported_even_when_json_is_complete(self):
        with patch('urllib.request.build_opener',return_value=self.stream('length')):
            with self.assertRaises(ValueError) as error:self.model().call('synthetic',{},on_chunk=lambda unused:None)
        self.assertEqual(worker.failure_code(error.exception),'MODEL_OUTPUT_LIMIT')

    def test_embedded_stream_error_is_not_silently_skipped(self):
        with patch('urllib.request.build_opener',return_value=self.stream(error=True)):
            with self.assertRaises(ValueError):self.model().call('synthetic',{},on_chunk=lambda unused:None)

    def test_http_rejection_does_not_retry_without_the_limit(self):
        self.status=400
        with self.assertRaises(worker.ModelConfigurationError): self.model().call('synthetic',{})
        self.assertEqual(len(self.observed),1);self.assertEqual(self.observed[0].get('max_tokens'),4096)

    def test_checkpoint_fingerprint_changes_with_effective_budget_and_parameter(self):
        first=self.model().distillation_fingerprint()
        with patch.dict(os.environ,{'MODEL_DISTILL_OUTPUT_TOKENS':'1024'}):self.assertNotEqual(first,self.model().distillation_fingerprint())
        with patch.dict(os.environ,{'MODEL_OUTPUT_TOKEN_PARAMETER':'max_completion_tokens'}):self.assertNotEqual(first,self.model().distillation_fingerprint())
        with patch.dict(os.environ,{'MODEL_TEMPERATURE':'omit'}):self.assertNotEqual(first,self.model().distillation_fingerprint())

    def test_text_budget_is_not_added_to_image_or_audio_protocols(self):
        os.environ.update({'IMAGE_BASE_URL':'https://fixture.invalid/v1','IMAGE_API_KEY':'fixture','IMAGE_MODEL':'fixture',
                           'SPEECH_BASE_URL':'https://fixture.invalid/v1','SPEECH_API_KEY':'fixture','TTS_MODEL':'fixture','ASR_MODEL':'fixture'})
        png=b'\x89PNG\r\n\x1a\nfixture';import base64
        captured=[]
        def open_request(request,**unused):
            captured.append(request)
            if '/images/' in request.full_url:raw=json.dumps({'data':[{'b64_json':base64.b64encode(png).decode()}]}).encode()
            elif request.full_url.endswith('/transcriptions'):raw=b'{"text":"synthetic transcript"}'
            else:raw=b'RIFF'+b'\x00'*4+b'WAVE'+b'fixture'
            response=io.BytesIO(raw);response.headers={'Content-Type':'audio/wav'};return response
        opener=Mock();opener.open.side_effect=open_request
        with patch('urllib.request.build_opener',return_value=opener):
            persona.image(self.model(),{'prompt':'synthetic'})
            persona.speech(self.model(),{'operation':'TTS','text':'synthetic'})
            persona.speech(self.model(),{'operation':'ASR','audio':'data:audio/wav;base64,'+base64.b64encode(b'RIFF'+b'\x00'*4+b'WAVE').decode()})
        for request in captured:
            self.assertNotIn(b'max_tokens',request.data);self.assertNotIn(b'max_completion_tokens',request.data)


if __name__=='__main__':unittest.main()
