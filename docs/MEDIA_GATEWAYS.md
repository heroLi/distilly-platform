# 文本、图像、视觉与声音网关契约

下文记录当前已实现的兼容网关。[VoiceStudio 接入设计](HINDSIGHT_VOICESTUDIO_PLAN.md)补充人物声音版本、4096 字符分段、私有媒体资产与统一回复打断；这些新增能力尚未实现。

当前适配器位于 worker/worker.py 与 worker/persona.py。配置地址只来自 Worker 环境；用户材料与模型输出不能设置地址或执行工具。HTTPS 默认必需，只有本地 HTTP 测试显式设置 MODEL_ALLOW_HTTP=true；不跟随重定向，避免转发凭证。

## 文本与视觉

POST `{MODEL_BASE_URL}/chat/completions`，Bearer MODEL_API_KEY，model=MODEL_NAME。
messages 包含平台 system 约束及 user JSON。人格使用上游 analyzer → builder 两阶段；对话输入包含已发布/预览人格、最近24条消息、最多30条已确认记忆与上一轮情感。
MODEL_JSON_MODE=true 时请求 response_format={"type":"json_object"}；返回 choices[0].message.content 必须为 JSON 对象字符串。
对话默认 stream=true，接受 data: JSON 的 SSE、choices[0].delta.content 和 [DONE]；普通 JSON 响应也接受。前端每两秒读取已受租约保护的 partial_reply，完整输出校验通过才写入正式消息。
不接受 stream 参数的网关设置 MODEL_STREAMING=false。SSE 最大2 MiB，内容60k字符；完整 JSON 响应最大1 MiB。网关需兼容 Persona、answer/emotion/memory_candidates 输出契约。

图片交流改用 VISION_BASE_URL / VISION_MODEL / VISION_API_KEY 的同类接口。user content 为 text JSON + image_url（已校验的 PNG/JPEG/WebP data URL）；图片不发送给不支持视觉的文本模型。当前图片最大1 MiB，历史上下文保留文字及回答，后续轮次不会重新发送全部历史图片。

## 形象生成

POST `{IMAGE_BASE_URL}/images/generations`，Bearer IMAGE_API_KEY。

```json
{"model":"配置的 IMAGE_MODEL","prompt":"用户形象描述","n":1,"size":"1024x1024","response_format":"b64_json"}
```

响应 data[0].b64_json 为 PNG/JPEG/WebP；适配器按文件头识别类型，生成产物限制约1 MiB。只有远程 URL、其他专用协议或更大产物的供应商需要另加适配器，不自动下载外部 URL。

## 语音合成

POST `{SPEECH_BASE_URL}/audio/speech`，Bearer SPEECH_API_KEY。

```json
{"model":"TTS_MODEL","input":"用户文字或人物回应","voice":"TTS_VOICE，默认 alloy","response_format":"mp3","speed":1.0}
```

返回音频二进制，Content-Type 必须为 audio/mpeg、audio/wav、audio/ogg、audio/mp4 或 audio/webm，最大1 MiB。关切/低落状态将 speed 调为0.9，其余为1.0。voice 是供应商音色标识，需设为该供应商支持的值；不提供声音克隆。
浏览器取回私有任务结果后显示播放器，用户点击播放。朗读与合成播放共用轻量形象动画。

## 音频转写

POST `{SPEECH_BASE_URL}/audio/transcriptions`，Bearer SPEECH_API_KEY。
使用 multipart/form-data，字段 model=ASR_MODEL、file=用户音频；文件名为平台生成的 audio 扩展名，输入最大1 MiB。
返回 JSON {"text":"识别文字"}，最多12000字符。识别结果只填到可核对的界面，用户选择不超过4000字内容后主动发送。

## 浏览器语音与运行边界

浏览器朗读使用 SpeechSynthesis，可选音色、语速、情感相关 pitch，支持停止。麦克风输入使用 SpeechRecognition / webkitSpeechRecognition，功能取决于浏览器和授权；识别结果先进入输入框，不自动发送。不宣称浏览器识别一定在本地完成。
参考：[MDN Web Speech API](https://developer.mozilla.org/en-US/docs/Web/API/Web_Speech_API/Using_the_Web_Speech_API)。

媒体网关未配置返回 MEDIA_NOT_CONFIGURED；协议、输出或模型故障明确失败，不退化成虚构媒体。失败任务最多三次尝试，可手动重试/停止。TTS/ASR 任务仅提交者可读，项目负责人不因此取得用户音频和转写内容。
完成后清空任务输入；失败时保留受权访问的输入用于重试。当前无独立对象存储、媒体清理后台或存储配额，需根据正式运行规模扩展。
