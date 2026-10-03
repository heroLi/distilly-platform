# 独立平台与上游升级边界

平台路径：/Users/lixiao167/Desktop/other/distilly-platform，独立Git仓库，无远程地址，尚未提交。
上游路径：/Users/lixiao167/Desktop/other/distilly，保留原dot-skill分支及已有origin地址，不在其中开发平台。

平台根目录的server/为Spring Boot项目，worker/为Python Worker，data/为本地H2数据库，.env为本地凭证；data/.env/target均不纳入Git。
平台不加载上游Python模块。唯一运行时复用内容是vendor/distilly/prompts/work_analyzer.md，附MIT许可及UPSTREAM.json记录固定提交和哈希。
Docker构建上下文仅独立平台自身。Maven、PythonWorker、网页、CI与数据库均独立于上游检出目录。

## 升级流程

1. 在上游仓库执行正常fetch/review/升级，不覆盖平台目录。
2. 对比vendor/distilly/UPSTREAM.json所记提交与新上游；如需更新工作分析提示词，先审查差异，再更新vendor文件及提交/哈希记录。
3. 在平台运行Maven测试、Worker测试、Node问答回归、HTTP端到端测试；需要时以授权测试材料做真实模型评测。
4. 原本地工具的数据完整性修复保存在upstream-patches/local-integrity.patch。仅当继续使用原工具时，在上游独立修复分支用git apply --check检查，再应用、测试，或提交上游PR；平台运行不依赖此补丁。
5. upstream-patches/original-worktree.patch保留迁移前已跟踪文件改动，便于审计或恢复，不应直接混入新上游版本。

迁移时正常停止平台，复制H2文件并核对字节一致，保留owner身份与密钥。验证独立启动后，移除旧仓库中的平台文件并恢复此次修改的上游文件。
企业部署时仍需PostgreSQL/Docker及SSO环境验收。
