# F3跨空间晚到列表修复（2026-10-12）

代码修复及最终完整合成回归通过，修复后F3真实浏览器复验待执行。本批不操作浏览器或旧审查标签、不改迁移配置/数据库/后端/业务接口、不部署/push/安装模型/付费调用；保留既有改动。

## 已确认根因与范围

已读取并视觉检查[独立复验](ui-accessibility-recheck-2026-10-12/REPORT.md)图07/08。合成owner同时有A/B权限；在knowledge.html#experiences中，A刷新请求`GET /api/projects/672d03eb-93cb-4ca4-a735-5c1a90168291/profiles`延迟8秒，切B后先为空，A返回后却覆盖B列表和操作按钮。旧代码loadProfiles直接渲染，不核对空间或请求代次；单次清空列表不能拦截后写入。新自动回归在旧实现确实得到“A stale”而非“B fresh”，失败日志/tmp/distilly-workspace-race/before.log。

这是已证实的UI归属错误；本轮没有验证后端越权，也不把同一owner可访问两空间称为权限泄漏。成员、审计、已删除专题/空间列表有同类代码缺口；人物/记忆已有跨人物/空间epoch校验，但同一人物并发读取可能倒序。相邻范围通过自动请求故障测试核对，尚没有全部浏览器复现证据。

## 修复机制与精确文件

| 文件 | 变化 |
|---|---|
| static/ui-read-scope.js（新增） | 小型只读守卫：捕获账号/空间/角色等上下文、单调epoch、每渲染槽最新读取代次；过期成功与失败返回空结果，当前有效错误继续抛出；可附加弹窗存活条件 |
| static/app.js | 专题/选项、成员、审计、已删除专题、空间列表与分页读取加守卫；切换同步清旧实体列表/专题选项，初始化链每个await后核对身份；导航同步更新，防旧初始化结束后关闭新弹窗；详情/预检/材料查看/轮询核对当前与原生dialog.open；退出先失效、清身份和关闭弹窗，pagehide失效 |
| static/persona.js | 空间、人物、材料、交流、记忆、任务、知识、人格绑定与成员读取保留原epoch并增加最新读取校验；会话读取带nonce；新人物读取成功后先清旧实体列表再展示，普通未发送草稿不清空；无空间成员区域清旧账号记录；pagehide失效 |
| static/personality-library.js | 人格列表使用同一空间/账号epoch；预填/选材/详情/依据读取核对弹窗及上下文，过期失败不报到新空间；保留用户编辑和授权重确认 |
| static/published-knowledge.js | 保留原修订代次，增加账号/空间epoch；旧成功/错误/最终busy状态不得改新上下文 |
| static/experience-tasks.js | 专题任务与详情读取核对空间epoch、专题、详情存活，关闭/切换后的成功或错误不写回 |
| static/index.html、knowledge.html | 先载入共同守卫并更新变动脚本缓存版本 |

文件完整路径为server/src/main/resources/static/。测试新增tests/workspace_race_ui_test.cjs，六组既有测试导入相同真实守卫以保持隔离环境；精确列表与复验清单见[结构化证据](research/workspace-response-guards-2026-10-12.json)。自己的差异/tmp/distilly-workspace-race/own-changes.diff，修改前逐文件备份在同目录before/，不以Git原始版本覆盖已有脏改动。

这不是靠禁用按钮隐藏问题。A-B-A仍有不同epoch；同一上下文以最新读取为准；退出/关闭时先逻辑失效。没有物理取消服务端读请求，没有自动重试，没有撤销已经提交的业务写入。普通composer、问题和新专题名称等输入不因旧回调而清空；明确空间/人物切换仍按原行为清理实体相关行与选项，防跨实体显示。当前业务/权限/网络错误仍沿用原分类和反馈。

## 自动回归与测试运行条件

实际执行源码处理器的deferred回归：A慢B快及专题选项/B草稿、A-B-A、各主要列表旧成功/旧失败、同空间成员与同人物记忆倒序、退出/pagehide迟到两种结果、详情关闭（包括open=false先于close事件）、旧初始化链停止、当前错误保留、工作室退出后的空间读取、无空间成员清理、新人物旧私人行清理而composer保留。原预填、网络、权限、知识审核、任务与键盘/对比度回归仍通过。

最终完整入口通过：Java120、Worker45、12组前端、10个JS语法、86个架构源、两套合成HTTP及diff检查。日志/tmp/distilly-workspace-race/full-verify.log；完整执行命令：

```sh
JAVA_TOOL_OPTIONS=-XX:TieredStopAtLevel=1 JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home JAVA_BIN=/opt/homebrew/opt/openjdk/bin/java python3 scripts/verify.py --e2e
```

需保留限制：最终源码默认JVM完整入口在知识E2E重启的固定30秒等待超时；单独保持原条件重跑也在更后重启超时。日志显示一次启动到process29.974s、另一次重启24.467s；未看到端口冲突或Java启动异常，具体原因未确认。最终只给本次测试进程临时JVM参数TieredStopAtLevel=1，产品、测试功能断言、30秒阈值与OS/生产设置均未改；不是生产启动性能验收。失败日志final-default-full-verify.log与knowledge-retry.log保留。较早的一次完整通过针对同批的中间源码，不能代替最终源码结果。

## 独立浏览器复验方案及剩余边界

本批无修复后截图，不接管旧479965391；原报告Debugger not attached，退出/关闭未确认。新验收只用自己的合成localhost标签和原/tmp隔离H2/网关，检查固定端口后经原生审批启动。若共享Mac默认Java25仍启动慢，可只给隔离测试进程同样临时参数并记录，不改系统设置。

按原A profiles8秒延迟复现：截图B刚为空及A返回后，空间标题/选择器、列表与任务专题选项均必须仍为B。再做A-B-A与连续切换，旧成功/断线失败均不得写回或显示到新空间；当前新空间错误应准确可见。成员/审计/归档/人格/分页材料逐项延迟验证；关闭知识/人格详情与预填、退出及切新合成用户；同人物记忆两次读取及跨人物行隔离，普通草稿保留。只读导航不点击错位旧业务按钮，不执行永久删除。记录实际请求路径/状态和已检查的前后截图；最后复原故障开关、退出合成账号、恢复viewport、关闭自有标签、停止准确自建PID并核对端口。

永久删除仍待原生确认；这不是所有写完成回调的完整竞态审计，也未证明真实模型/媒体、PostgreSQL、生产或屏幕阅读器合规。F1/F2已有独立真实浏览器通过证据，此批不重写这些历史结论。
