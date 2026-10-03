# 本地平台开发约定

- 本目录是独立共享平台项目。server/为Spring Boot，worker/为Python Worker，不在同级distilly开源仓库直接开发平台。
- 上游升级通过vendor/distilly/UPSTREAM.json固定来源；更新提示词需保留MIT许可并运行平台验证。原工具修复只保存于upstream-patches。
- .env、data/、server/target/不得提交。禁止在工具输出或日志中回显密钥及材料正文。
- 禁止读取、枚举、搜索、复制、导出或上传/Users/lixiao167/.codex/sessions中的对话用于第三方采集、用量分析或其他非原生用途；不能用脚本、子进程或符号链接绕过。
- 不得自动采集私聊或Agent会话。使用真实模型验证时选择明确授权的材料。
- 外部CLI或京东业务系统操作优先尝试o2并读取其帮助。
