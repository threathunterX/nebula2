# 发布流程

本文规定 Nebula 2.0 的版本号规则、发布前检查清单、打 tag 与发布的具体操作,
以及 `CHANGELOG.md` 的维护方式。

> **当前状态**:已发布 `v0.1.0`。本文描述每次发布的固定动作。
>
> 首次执行时发现两处与实际不符,已修订:CHANGELOG 应在 PR 中写而不是发布时补写
> (v0.1.0 的条目是补写的);`make demo` 的输出需与快速开始逐字对齐,而快速开始
> 在此期间已改版。

---

## 1. 版本号规则

采用[语义化版本 2.0.0](https://semver.org/lang/zh-CN/):`MAJOR.MINOR.PATCH`。

### 1.1 什么是本项目的「公共 API」

语义化版本的兼容性承诺是针对「公共 API」的。对本项目而言,公共 API 包含:

| 契约 | 位置 | 说明 |
|---|---|---|
| 领域模型 schema | `packages/domain-schema/*.schema.json` | 事件、变量、策略、告警的结构 |
| 枚举值 | `packages/domain-schema/enums.json` | `scene`、`decision`、`check_type` 等 |
| 算子语义 | `docs/reference/operators.md` | **规范性文档**,算子的输入输出与边界行为 |
| `/checkRisk` 对外契约 | 继承自 1.x | 业务系统同步查询接口 |
| 内置资产的语义 | `seeds/` | 变量命名规范、策略判定含义 |
| CEL 扩展函数 | `packages/cel-functions/` | 函数签名与语义 |

**不属于**公共 API,可以随时改动而不触发版本号变更的:参考引擎的内部实现结构、
`tools/` 下脚本的命令行参数、自动生成文档的排版、CI 配置、Makefile 目标的实现细节。

参考引擎(`packages/reference-engine/`)本身不是产品的一部分,不做生产使用承诺;
但它**实现的语义**属于公共 API —— 它跑出的结果变了,就意味着算子语义变了。

### 1.2 0.x 阶段的含义

**项目当前处于 0.x 阶段,这个阶段的兼容性承诺与 1.0 之后不同。**

按语义化版本第 4 条,主版本号为 0 时,任何版本都可能包含不兼容变更。本项目对
0.x 阶段做如下具体约定:

| 版本位 | 0.x 阶段的含义 |
|---|---|
| `0.MINOR.0` | 可以包含**破坏性变更**(schema 结构调整、算子语义修订、枚举值变动)。破坏性变更必须在 CHANGELOG 中单列 `Breaking` 小节,并说明迁移动作。 |
| `0.x.PATCH` | 只含缺陷修复、文档更新、不改变已定义语义的补充。升级 PATCH 不应要求使用方做任何调整。 |

也就是说:**在 0.x 阶段,升 MINOR 就要预期可能需要改配置或改数据。**
这不是流程疏漏,而是明确的阶段性选择 —— 领域模型还在随实现推进而修订,
过早冻结 schema 会逼出更糟的兼容性妥协。

**离开 0.x 的条件**(全部满足才发 1.0.0):

- 生产引擎(Flink)实现完成,并通过 golden 回归测试对全部内置策略验证语义一致
- 采集器、控制面 API、管理界面达到可用状态
- `deploy/compose` 的 Lite 模式可一条命令启动
- 算子语义规格已被真实实现完整验证,不再有「欠定义」条目
- 有至少一个非贡献者的第三方完成过端到端部署

在此之前,`docs/reference/operators.md` 与 `packages/domain-schema/` 都可能变。

### 1.3 预发布版本

需要在正式版之前放出可评估的构建时,使用 `-alpha.N` / `-beta.N` / `-rc.N` 后缀,
例如 `v0.2.0-rc.1`。预发布版本在 GitHub Release 中必须勾选 **pre-release**,
不写入 CHANGELOG 的正式版本段落(合并进最终版本的段落即可)。

### 1.4 Tag 命名

Tag 一律带 `v` 前缀:`v0.1.0`、`v0.2.0-rc.1`。CHANGELOG 中的版本标题**不带** `v`,
例如 `## [0.1.0] - 2026-08-15` —— 这是 Keep a Changelog 的惯例。

---

## 2. CHANGELOG 维护方式

`CHANGELOG.md` 遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

### 2.1 谁写、什么时候写

**在 PR 中写,不在发布时补写。** 每个 PR 如果引入了使用方或评估者会察觉到的变化,
就应当在 `[Unreleased]` 段落追加一条。Pull Request 模板里有对应的检查项。

发布时补写整个版本的 CHANGELOG 是行不通的 —— 事后没人记得清每处改动的动机,
写出来的条目会退化成 commit 标题的堆砌。

**不需要写 CHANGELOG 的改动**:纯内部重构、typo 修正、CI 配置微调、
自动生成文档的重新生成。判断标准很简单:**读者会不会因为不知道这件事而受影响。**

### 2.2 分类

只用下面这些标题,顺序固定:

| 分类 | 用于 |
|---|---|
| `Added` | 新增的能力、资产、文档、工具 |
| `Changed` | 已有行为的变化。**算子语义、schema 结构、默认值的变动一律进这里** |
| `Deprecated` | 即将移除但当前仍可用的能力,须写明预计移除的版本 |
| `Removed` | 已移除的能力 |
| `Fixed` | 缺陷修复 |
| `Security` | 安全相关的修复。**即使不构成漏洞也要单列**,便于使用方评估升级紧迫性 |

本项目额外约定两个小节:

- `Breaking` —— 破坏性变更单独成节,置于全部标准分类之前,每条必须写明**使用方要做什么**。
- `已知问题` —— 明知存在但本次未修的问题。当前主要是从 1.x 继承的数据缺陷,
  这些涉及风控业务判断,保留原样并公开标注,而不是悄悄改掉。

### 2.3 条目怎么写

写**变化本身与它的后果**,不写 commit 标题。有具体数字就写具体数字。

```
✗ 修复了策略索引的问题
✓ 策略索引改为派生生成:index.json 此前手工维护,策略转 2.0 后仍带着 1.x 的
  status=online,与实际文件脱节。现由 gen_seeds_index.py 生成并纳入 CI 门禁。
  「需要配置才能生效」的策略数量从 7 条修正为 10 条。
```

涉及语义变更时,必须写清楚**与旧行为的差异**,并在迁移指南中留下对照条目。

### 2.4 发布时的 CHANGELOG 操作

1. 把 `## [Unreleased]` 改为 `## [Unreleased]` + 新的 `## [X.Y.Z] - YYYY-MM-DD` 两段,
   已完成的条目移入版本段落,`[Unreleased]` 保留为空壳。
2. 通读一遍版本段落,合并重复条目、删除已被后续改动推翻的条目。
   开发过程中「加了又改」的中间状态不该出现在发布记录里。
3. 更新文件底部的链接引用:

   ```markdown
   [Unreleased]: https://github.com/threathunterX/nebula2/compare/vX.Y.Z...HEAD
   [X.Y.Z]: https://github.com/threathunterX/nebula2/compare/vA.B.C...vX.Y.Z
   ```

   首个版本没有可比较的前一个 tag,用 `.../releases/tag/vX.Y.Z`。

---

## 3. 发布前检查清单

**逐项执行,不要凭印象勾选。** 每一项后面都写了具体命令或核对对象。

### 3.1 自动化校验

- [ ] `make validate` 通过 —— 这一条等同 CI 的全部检查项:
      schema 自身合法、seeds 符合 schema、生成文档与资产一致、文档内部链接可达、
      参考引擎规格符合性测试
- [ ] `make test` 通过
- [ ] `make lint` 通过(目前只覆盖 `apps/collector` 的 `gofmt` 与 `go vet`,
      Python 与 JavaScript 尚未纳入)
- [ ] `make privacy-check` 通过
- [ ] `make secrets-scan` 通过(需本地已安装 gitleaks)
- [ ] `make demo` 能跑完,输出与 `docs/guide/quickstart.md` 中的示例一致
- [ ] main 分支上最近一次 CI 全绿

### 3.2 数据合规(本项目的重点)

星云处理的是业务风控数据,而内置资产派生自 1.x 的出厂配置 —— 这类数据天然带着来源
系统的痕迹。**发布是不可撤销的**:数据一旦进入公开仓库,即便随后删除、重写历史,
已被克隆和 fork 的副本仍然存在。这一节不能走过场。

- [ ] `seeds/` 中不含真实客户信息、真实终端用户标识、真实生产流量样例
- [ ] `**/sanitize_rules.json`(含真实标识,仅本地使用)确实不在版本控制中:
      `git ls-files | grep sanitize_rules` 应无输出
- [ ] 新增的示例数据使用约定占位值(`example.com` 系、RFC 5737 IP 段、
      `13800138000`、`user@example.com`)
- [ ] 仓库中不存在任何可用凭据,包括测试用的
- [ ] `git log` 的提交信息与提交者邮箱中不含不应公开的个人信息

### 3.3 文档真实性

**这是本项目的基调:不把尚未实现的能力写成已具备的能力。**

- [ ] README 的「项目状态」表与仓库实际状态逐行核对一致
- [ ] README 的「核心特点」表中,✅ 标记的每一项都能由读者亲自验证
- [ ] `docs/README.md` 的 🚧 标记与文档实际完成度一致 ——
      **既不能把占位文档标成完成,也不能把已完成的文档还标着 🚧**
- [ ] `SECURITY.md`、`docs/security/privacy.md` 中描述的运行时能力,
      凡未实现的均已标注为设计意图
- [ ] `CONTRIBUTING.md` 中要求贡献者执行的命令都真实存在且真的会检查东西
      (注意 `make lint` 目前是占位实现)
- [ ] `tests/golden/README.md` 中描述的 CI 门禁与 `.github/workflows/` 实际配置一致
- [ ] 快速开始中的每条命令都在干净环境中实际执行过,输出与文档一致
- [ ] `docs/development/release-readiness.md` 已重新审查并更新

### 3.4 数字一致性

README 与文档中反复出现的资产数量必须与实际一致。逐项核对:

```bash
ls seeds/events/*.json     | grep -v index.json | wc -l   # 应为 17
ls seeds/variables/*.json  | grep -v index.json | wc -l   # 应为 253
ls seeds/strategies/*.json | grep -v index.json | wc -l   # 应为 170
python3 -c "import json;print(json.load(open('seeds/tags.json'))['count'])"  # 应为 15
```

- [ ] 上述数字与 README、`docs/README.md`、`seeds/INVENTORY.md` 中的表述一致
- [ ] 测试数量、策略分类拆分等派生数字也已核对

### 3.5 许可与归属

- [ ] `LICENSE` 完整且未被改动
- [ ] 如引入了第三方代码,`NOTICE` 已记录其出处与许可证
- [ ] 新增依赖的许可证与 Apache-2.0 兼容
- [ ] 从 1.x 继承的资产的授权关系已在文档中说明

### 3.6 CHANGELOG 与版本号

- [ ] `[Unreleased]` 中的条目已按 §2.4 整理进版本段落
- [ ] 破坏性变更已单列 `Breaking` 并写明迁移动作
- [ ] 版本号的选择符合 §1.2 的 0.x 约定
- [ ] 底部链接引用已更新

---

## 4. 打 tag 与发布

> 下列命令需要对仓库的写权限。执行前请确认本地 main 与远端一致且工作区干净。

### 4.1 准备

```bash
git checkout main
git pull --ff-only origin main
git status --porcelain          # 必须无输出:工作区干净
make validate && make test      # 最后再跑一遍
```

CHANGELOG 的整理通过**正常的 PR 流程**合入 main,不要直接推 main。
发布用的 PR 惯例标题:`chore(release): 0.1.0`。

### 4.2 打 tag

一律使用**带注释的 tag**(`-a`),不用轻量 tag —— 带注释的 tag 携带打标者、
时间与说明,是可审计的发布记录。

```bash
git tag -a v0.1.0 -m "Nebula 2.0 v0.1.0"
git push origin v0.1.0
```

**tag 只打在已经合入 main 的提交上。** 不要给未合并的分支打发布 tag。

如需给 tag 签名(推荐,尤其对安全类项目):

```bash
git tag -s v0.1.0 -m "Nebula 2.0 v0.1.0"
```

### 4.3 发布 Release

在 GitHub 上基于该 tag 创建 Release:

- **标题**:`v0.1.0`
- **正文**:直接复制 CHANGELOG 中该版本的段落。开头补一段两三句话的概述,
  说明这个版本**能做什么、不能做什么** —— 在 0.x 阶段这一段比变更列表更重要。
- 预发布版本勾选 **pre-release**
- 如有安全修复,在正文顶部醒目标注,并致谢报告者(除非对方要求匿名)

### 4.4 发布后

- [ ] 在 `CHANGELOG.md` 中确认 `[Unreleased]` 已重置为空
- [ ] 如版本号在 README 或文档中被引用,同步更新
- [ ] 如本次发布修复了已上报的安全问题,按 `SECURITY.md` 的承诺回复报告者

---

## 5. tag 打错了怎么办

**已经推送到远端的 tag 不要删除或移动。** 已经有人拉取过的 tag 被改动,
会造成本地与远端不一致且难以察觉。

正确做法:发一个新的补丁版本。

```bash
# 错误示范,不要这样做
git tag -d v0.1.0 && git push --delete origin v0.1.0

# 正确做法:修复后发 v0.1.1,并在 CHANGELOG 中说明 v0.1.0 的问题
```

唯一的例外:tag 推送后**几分钟内**发现严重错误(例如误含了不该公开的内容),
且确认尚无人拉取。这种情况下删除 tag 与 Release 后,**必须**在下一个版本的
CHANGELOG 中记录曾经发生过撤回。如果误发内容涉及凭据或个人信息,
删除 tag 是不够的 —— 按 `SECURITY.md` 的流程处理,并假定内容已经泄露。

---

## 相关文档

- [发布就绪度审查](release-readiness.md) —— 当前仓库距离可发布状态还差什么
- [贡献指南](../../CONTRIBUTING.md)
- [更新日志](../../CHANGELOG.md)
- [安全策略](../../SECURITY.md)
