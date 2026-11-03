# 长期记忆离线评测：冻结和运行完整性工具

基线 `449c78353577033bad2af07ab933123182cbaa64`，独立分支 `resume/text-cost-gate-20261021`。旧 M0 freeze 能校验列出的文件，但没有完整 A/B 提交、配置/数据/评分器分组摘要，亦没有拒绝缺失一侧或漏题运行的通用回执。本批补该合同，不改生产检索、候选规则、旧 fixtures、聊天历史或删除语义。

`scripts/memory_eval_integrity.py` 仅使用 Python 标准库。它读取明确指定的本地文件和本地 Git 对象，**不执行代码、评分器、模型、网络或数据库操作**；Git 对象缺失就拒绝，不自动 fetch。没有新增问题、答案、gold 或封存样本。

## 三个操作

1. `freeze`：输入 spec，独占创建新 manifest，返回 manifest 原始字节的 SHA-256。要求 A/B 各有完整 40 位提交 SHA（必须是本地 commit 对象）、非空普通源码文件列表，以及配置、数据、评分器的非空文件列表。manifest 包含 Git 提交源码摘要、三组文件摘要、案例清单数量/摘要及校验器自身摘要。
2. `verify`：必须由调用者提供事先保存的 `--expected-manifest-sha256`。先验证外部 pin，再重新读取本地 Git 源码、绑定文件、案例清单和校验器版本；不接受仅自行重签的 manifest。不能在运行后临时读取新 manifest 的 hash 作为事先冻结证明。
3. `audit`：先 verify，再检查 A/B 运行元数据及结果摘要；结束前再次核对输入与结果。成功或可读取输入后的校验失败均可写独占回执；失败退出 2，成功退出 0。已有 manifest/回执/输入绝不覆盖。

所有路径必须是仓库内明确指定的相对普通文件，拒绝绝对路径、隐藏路径、`..`、符号链接及路径规范化歧义；不枚举目录或读取环境变量。JSON 上限为每文件 16 MiB，拒绝重复键和非有限数。错误只报告固定代码，不输出异常原文、资料、指标值或机器路径；回执保存 UTC 检查时间、原始输入/结果摘要、完成数量与状态。

## spec 合同与可复现示例

以下示例仅引用**已经暴露的开发集**。A/B 可以来自同一完整提交中的不同实现；完整提交绑定整个源码版本，列出的源码摘要进一步标明本次选择。应完整列出影响运行的配置与评分器文件，不把 API key 或真实私密配置放入 spec。

```json
{
  "schema_version": 1,
  "scope": "OFFLINE_SHADOW",
  "dataset_kind": "development",
  "implementations": {
    "A": {
      "commit": "449c78353577033bad2af07ab933123182cbaa64",
      "sources": ["server/src/main/java/internal/distilly/modules/memory/service/SqlMemoryProvider.java"]
    },
    "B": {
      "commit": "449c78353577033bad2af07ab933123182cbaa64",
      "sources": ["server/src/test/java/internal/distilly/MemorySpanShadowProvider.java"]
    }
  },
  "configuration": ["tests/fixtures/memory_m0_shadow_v1.contract.json", "tests/fixtures/memory_m0_shadow_v1.freeze.json"],
  "dataset": ["tests/fixtures/memory_m0_dev_v1.json", "tests/fixtures/memory_m0_dev_v1.lock.json"],
  "scorer": ["server/src/test/java/internal/distilly/MemoryDiagnosticMetrics.java", "server/src/test/java/internal/distilly/MemoryM0ShadowComparisonTest.java"],
  "case_inventory": "tests/fixtures/memory_m0_dev_v1.json"
}
```

`case_inventory` 必须列在 dataset 中。清单沿用现有 JSON 的 `split`、`cases[].id` 结构：split 须等于 dataset_kind（development 或 holdout），ID 唯一、非空、仅为不含资料的 ASCII 标识。工具不生成清单或读取尚未准备的封存集。

先将 spec 放入明确的合成实验目录，输出父目录须已存在。示例命令中 PIN 代表事先单独保存的完整 manifest SHA-256：

```sh
python3 scripts/memory_eval_integrity.py freeze --spec data/experiment/spec.json --out data/experiment/manifest.json
python3 scripts/memory_eval_integrity.py verify --manifest data/experiment/manifest.json --expected-manifest-sha256 PIN
python3 scripts/memory_eval_integrity.py audit --manifest data/experiment/manifest.json --expected-manifest-sha256 PIN --run data/experiment/run.json --out data/experiment/receipt.json
```

## 运行与结果导出合同（schema_version 1）

run.json 必须包含且仅包含：schema_version、manifest_sha256、run_id、status、sides。run_id 为不含个人信息的标识，status 必须 COMPLETED；sides 恰有 A/B。

每侧必须包含：

- commit：与 manifest 对应完整 SHA 相同。
- implementation_digest：manifest 中该侧 `{commit,sources}` 的规范 JSON SHA-256。
- configuration_digest、dataset_digest、scorer_digest：manifest 对应文件路径→SHA 映射的规范 JSON SHA-256。
- status=COMPLETED、整数 exit_code=0；运行失败或仍在执行不得签成完成。
- result：`{path,sha256}`，指向对应结果文件及其原始字节 SHA-256。

结果文件必须包含且仅包含 schema_version、side、manifest_sha256、case_results、metrics。side 和 manifest pin 必须匹配；case_results 每项为 `{id,status}`，status 全部 COMPLETED，ID 与冻结清单完全相同，不能漏、重复、增加或跳过。metrics 为非空的指标名→有限数字/null 映射，null 仅表示评分器无法定义的分母，不转成零；工具不采纳任何质量阈值。

规范 JSON 统一为 Python `json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True,allow_nan=False)` 的 UTF-8 字节。工具公开 `canonical`/`digest` 函数供合成结果导出器复用。路径角色摘要与原始文件 SHA 是不同值，不能混用。输出回执不复制逐题资料或指标值，以摘要绑定完整结果。

当前 Java 开发集报告仍保留原格式。未来独立执行器须显式导出上述运行元数据和完成清单，不能将历史报告自动冒充一次新运行。本批没有添加结果导出执行器、没有重新评分或开封。

## 能证明与不能证明

成功仅表示**给定外部 pin 的文件、提交声明和完成清单内部一致**。输出明确为 `INTEGRITY_VERIFIED`、`quality_status=NOT_ASSESSED`、`blinding_status=NOT_ESTABLISHED_BY_INTEGRITY_CHECK`。

它不是签名、公证或防恶意伪造系统，不能证明输入原先没有被看过、编写者独立、评分正确、导出器如实执行，也不能单凭声明证明运行二进制来自所指提交。执行负责人仍需保留独立编写/开封记录、实际命令、构建来源和退出证据；源码从 Git 对象核对，不等于允许在脏工作树执行后声称对应提交。配置遗漏也需人工审查，工具无法推断未声明的依赖。外部 pin 应另行保存，不能与可任意修改的 manifest 一起重签后称原始冻结。

本批没有构造封存集、没有盲测、没有模型调用或中文质量提升。旧开发集 10/16 的 shadow 结果不变，原 12/21、语义 0/4 和旧 247 项未恢复均保留。原长期记忆删除/历史 payload 行为不变。

## 本轮证据与验收

- 17 项自动回归仅在临时 Git 仓库使用源码占位文本、案例 ID/状态和计数元数据；没有记忆问题、答案或新 gold。覆盖成功 CLI、完整提交、旧不完整合同、外部 pin、配置/数据/评分器漂移、校验器版本、非零退出、漏题/重复/额外/未完成案例、A/B 对调、结果摘要、非有限值、路径安全及不覆盖。
- 已接入 `scripts/verify.py`，每次标准入口都运行这些回归。新增 Python 语法检查通过；本轮默认统一入口 `python3 scripts/verify.py --e2e` 全过，退出 0，耗时 635.821 秒：Java 379、Worker 91、JS 语法 19、Node 26 组、E2E 18 套及新增工具 17 项。未加 JVM 参数、30 秒门槛未变；本地浏览器使用支持的既有 Chromium 显式路径。精确提交 CI 另外核验，不引用旧运行代替。
- 在原开发集上仅执行 freeze/verify；人为构造的未完成**元数据**记录被 `run_incomplete` 拒绝。最终示范 manifest SHA-256：`01525a1b42d66469496e43010fe29e4a04e398aa4b0d1a32202927f8350adb0b`。示范不是评测执行。
- 原始缺口记录、分次工具测试、示范 manifest/拒绝回执、完整标准入口结果保存在忽略目录 `data/resume-20261103/memory-eval-integrity/`。之前的阶段性 manifest 和日志保留，不覆盖。

下一步仍是指定独立封存负责人，先核验冻结版本，再编写和锁定新来源家族，按本合同导出一次真实执行元数据；无需为本工具新增模型、网络、资源或凭据。独立分工尚未完成，不能以工具通过替代这一步。
