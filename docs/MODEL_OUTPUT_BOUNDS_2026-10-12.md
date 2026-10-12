# 文字模型输出上限与截断协议（2026-10-12）

已实现，无新依赖、迁移、部署或付费调用。worker/model_protocol.py集中校验输出预算及参数，所有Chat Completions调用都有显式上限；图片生成、TTS、ASR保留各自请求协议，不添加文字token参数。

| 配置 | 默认 | 用途 |
|---|---:|---|
| MODEL_DEFAULT_OUTPUT_TOKENS | 4096 | 未指定阶段/显式预算的文字调用 |
| MODEL_PERSONA_ANALYSIS_OUTPUT_TOKENS | 4096 | 人格材料分析 |
| MODEL_PERSONA_BUILD_OUTPUT_TOKENS | 8192 | 完整人格Markdown构建 |
| MODEL_DISTILL_OUTPUT_TOKENS | 4096 | 每个知识材料分块 |
| MODEL_KNOWLEDGE_ANSWER_OUTPUT_TOKENS | 2048 | 工作知识问答 |
| PLATFORM_REPLY_OUTPUT_TOKENS | 2048 | 聊天/视觉聊天，由已有后端上下文预算显式传入，原范围256～8192保持 |

新Worker阶段预算范围256～32768；非整数、零、负数、超范围报MODEL_CONFIGURATION_ERROR，不能关闭输出上限。MODEL_OUTPUT_TOKEN_PARAMETER只接受max_tokens/max_completion_tokens，默认max_tokens，单次请求只发送选定字段；MODEL_TEMPERATURE默认0，可设0～2的有限数或omit（完全省略）。MODEL_JSON_MODE/MODEL_STREAMING严格接受true/false，错误值不再静默关闭功能。Vision通过VISION_OUTPUT_TOKEN_PARAMETER、VISION_TEMPERATURE、VISION_JSON_MODE、VISION_STREAMING覆盖文字策略，未配置时继承；VISION_DEFAULT_OUTPUT_TOKENS为未传显式预算的视觉调用预留。配置示例和Compose映射已同步，未编辑用户.env或运行配置。

兼容依据：[OpenAI官方参数定义](https://github.com/openai/openai-python/blob/main/src/openai/types/chat/completion_create_params.py)说明max_completion_tokens包含可见及推理token，旧max_tokens不适用于o系列；[DeepSeek官方Chat Completions文档](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion)支持max_tokens并说明length可能表示输出或上下文截断。此平台上限是自身策略，不宣称等于供应商全部型号上限。未知网关参数不会自动按型号猜测；HTTP400等配置拒绝终止任务，不去掉限制偷偷重发。实际模型还需按其公开协议和明确授权材料验收。

非流式必须明确finish_reason=stop；length抛MODEL_OUTPUT_LIMIT，即使正文恰好是合法JSON也拒绝。流式必须有正常stop及[DONE]，缺终态/提前EOF/嵌入错误/工具调用或过滤终态都不能作为完整文字JSON。输出限额是终态失败，不自动重复供应商调用；前端提示缩小输入或调整额度后重新生成。模型截断不会写当前块checkpoint、创建知识候选/人格版本或最终聊天回复；此前完整且已存的分块保留。

蒸馏checkpoint指纹升级work-distill-checkpoint-v2，加入实际分块预算、参数名、temperature、JSON模式；旧指纹或不同设置返回EXECUTION_CHANGED，保留断点但不混用不同生成设置。升级前保存的未完成旧任务可能因此不能继续，应按界面说明重新创建任务；没有重写数据或自动迁移。

14项新Worker协议测试在旧实现出现30个失败子断言和1个错误，修复后通过。测试覆盖实际合成HTTP参数、四种非聊天阶段、独立视觉参数、无temperature模式、非法配置、合法JSON但length、正常/截断/提前结束SSE、嵌入错误、HTTP配置拒绝只请求一次、指纹变化及图片音频请求不含文字参数。两套实际API/Worker HTTP E2E还验证截断知识/人格仅1次尝试且版本列表为空，原已发布知识不受影响。全部Java120、Worker45、7组前端、7个JS语法、86个Java架构守卫、两套HTTP E2E和diff检查通过；日志/tmp/distilly-output-bounds/full-verify.log和research/model-output-bounds-2026-10-12.json。

边界：没有实测真实付费模型、供应商实际usage或人民币费用。令牌参数语义因模型不同，尤其推理token可能计入同一额度。网关缺finish_reason或流式不发DONE会被明确拒绝，需要合法协议适配，不能当成完成。真实人格表达、同义/跨语言召回和音视频质量尚未由合成网关证明。下一步按用户要求做实际页面完整旅程与风格一致性走查，不无限增加假设性后端待办；可靠持久额度仍是尚未执行的表结构提案。
