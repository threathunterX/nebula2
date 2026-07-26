# 发布就绪度审查

> ## v0.1.0 发布前的复审(2026-07-26)
>
> 首次审查(见下文)之后,项目又落地了认证授权、告警查询、策略编辑、元数据下发与
> 容器化编排。发布前按[发布流程](release-process.md) §3 的清单重新逐项核对,发现并
> 处理了以下几项:
>
> | 问题 | 处理 |
> |---|---|
> | **凭据扫描一直没跑起来** —— `.gitleaks.toml` 用了 RE2 不支持的否定先行断言,gitleaks 直接 panic,而 action 崩溃后仍去上传 sarif,失败看起来像环境问题 | ✅ 已修,并加了工作区扫描与 `.env` 未被跟踪的硬断言 |
> | `make secrets-scan` 用 `\|\| echo` 吞掉失败,发现泄露也返回成功 | ✅ 已修 |
> | 9 个提交带着开发机的本机主机名邮箱(形如 `<用户名>@<主机名>.local`) | ✅ 已重写(当时 0 fork / 0 star),`git config` 已修正 |
> | CHANGELOG 没有覆盖此前 9 个提交的工作 | ✅ 已补。**流程本身要求「在 PR 中写,不在发布时补写」,这次是补写的** |
> | 快速开始仍写着「生产引擎仍在开发中」 | ✅ 已改版为两条路径(参考引擎 / compose 全栈) |
> | 5 篇占位文档(接入、策略、CEL、API、配置、部署)各 15–19 行 | ✅ 已按实际实现编写,命令逐条实跑验证 |
> | 仓库 Topics 与描述为空,归档仓库描述未指向 2.0 | ✅ 已设置 |
> | 无依赖更新机制 | ✅ 已加 dependabot,并对刻意钉住的版本逐条写明忽略理由 |
>
> **仍未处理的遗留**:1.x 仓库的历史提交清理需要向 GitHub Support 提交请求(归档并
> 重写历史后,旧提交仍可通过精确 SHA 从 fork 网络取回)。相关的运维事项在内部跟踪,
> 不在本文档展开 —— 公开文档不适合记录尚在处理中的运维状态。
>
> 首次审查报告原文保留在下方。


> ## 本次审查后的修复状态
>
> 报告完成后已逐条修复,修复情况如下。**修复本身也经过实测验证**,不是照单打勾。
>
> | 编号 | 问题 | 状态 |
> |---|---|---|
> | P0-0 | CI 因 `mask.go` 未通过 gofmt 而变红 | ✅ 已修。同时把 `make lint` 改成可靠写法(原写法本身有缺陷) |
> | P0-1 | 四份文档用现在时描述不存在的机制 | ✅ 已修。golden CI 门禁、生成类型一致性检查、SBOM/Trivy、存储层与主体权利接口,全部加 🚧 并写明当前实际生效的是什么 |
> | P0-2 | 许可证归属链条不完整 | ✅ 已修。新增 `NOTICE`,写明 1.x 为 Apache-2.0(与本项目兼容)、不含 1.x 实现代码、MurmurHash3 与 HyperLogLog 的算法出处 |
> | P0-3 | `jsonschema` 依赖未文档化 | ✅ 已修。新增 `tools/requirements.txt`,CI 改为按其安装,CONTRIBUTING 补前置依赖表 |
> | P0-4 | 快速开始仍标 🚧 | ✅ 已修 |
> | P0-5 | `deploy/`、`docs/assets/` 等空目录 clone 后不存在 | ✅ 已修。4 个目录加带用途说明的 `.gitkeep` |
> | P1-1 | `make validate` 自称等同 CI,却不含隐私检查与凭据扫描 | ✅ 已修。纳入 `privacy-check` 与 `lint` |
> | P1-4 | `docs/assets/` 被排除在隐私扫描外 | ✅ 已修。**用探针文件实测确认**:修复前含真实手机号与公网 IP 的文件完全不被发现,修复后立即拦截 |
> | P1-5 | 「五大场景」与实有六个枚举值矛盾 | ✅ 已修 |
> | P1-6 | 声明「不是 WAF」但内置 19 条 WAF 类策略 | ✅ 已修。改为说明 WAF 类规则是补充而非重点,并点明星云不能替代专业 WAF |
> | P1-10 | `.gitignore` 路径写错导致二进制未被忽略 | ✅ 已修,`git check-ignore` 验证通过 |
> | P1 其他 | privacy job 缺 setup-python、脚本路径写错 | ✅ 已修 |
>
> **尚未处理**:`seeds/` 下两份文档是英文(与全中文的其余文档不一致);身份证与银行卡的形态匹配未做校验位;邮箱白名单是子串匹配。这三项记录在案,不阻塞发布。

**审查对象**:`threathunterX/nebula2`,提交 `4442758`
**审查日期**:2026-07-26
**审查范围**:公开发布(Apache-2.0,GitHub 公开仓库)的准备情况
**结论**:**可以发布,但需先处理 P0 项 —— 其中 P0-0 会让 CI 直接变红。**

> 审查期间仓库有两次提交落地(`cadca32` 参考引擎的变量计算图、`4442758` Go 采集器),
> 本文已按 `4442758` 复核。项目推进很快,**下次发布前应重跑一遍本文的检查项**,
> 不要直接照搬结论。

---

## 0. 怎么读这份报告

这份报告的目的是**列出不足**,不是给项目背书。项目自己在 README 里写了「不做超前宣传」,
这份审查按同样的标准检查它自己是否做到了。

发现的问题按影响分三级:

| 级别 | 含义 |
|---|---|
| **P0** | 发布前必须处理。会误导使用者、涉及法律归属,或让贡献者按文档操作时直接受挫 |
| **P1** | 应在首个 tag 前处理。不处理不会出错,但会持续消耗维护者与贡献者的时间 |
| **P2** | 记录在案,可排期。属于成熟度差距而非缺陷 |

已核实通过的项目在 §8 单列 —— 列出来是为了说明审查覆盖到了,不是为了充数。

---

## 1. 总体判断

**这个仓库处于「诚实的早期项目」状态,发布是合理的。**

支撑这个判断的事实:

- `make validate` 与 `make test` 在干净检出上通过(参考引擎 52 个测试 + 采集器全部包);
  唯一失败的是 `make lint` 的格式检查,见 P0-0
- README 的「项目状态」表逐项标注了 ✅ / 🚧,明确写了「不能做什么:接入真实流量、生产部署」
- 快速开始里的命令是真的能跑的,不是占位符
- 资产数量(17 / 253 / 170)经实际清点核对无误,且 `tools/validate_seeds.py` 把
  这三个数字硬编码为断言,数量对不上 CI 会红
- 从 1.x 继承的数据缺陷被公开记录并加了回归测试,而不是悄悄修掉或藏起来

**但项目的诚实标准应用得不均匀。** README 和 `docs/README.md` 做到了逐项标注,
`SECURITY.md`、`docs/security/privacy.md`、`tests/golden/README.md`、`CONTRIBUTING.md`
四份文档仍在用现在时描述尚不存在的机制。这是本次审查最主要的一类发现:
**不是虚假宣传,而是同一个项目内部两套标准。** 外部读者不会知道哪份文档适用哪套标准。

---

## 2. P0 —— 发布前必须处理

### P0-0 当前 HEAD 上 CI 是红的:一个 Go 文件未格式化

`4442758` 新增的采集器 CI job(`ci.yml` 的 `collector`)有一个「格式检查」步骤,
执行 `gofmt -l .` 并在有输出时失败。实测:

```
$ cd apps/collector && gofmt -l .
internal/mask/mask.go
```

`make lint` 同样失败(`make: *** [lint] Error 1`)。差异是
`internal/mask/mask.go:83-85` 三行 map 字面量的键值对齐问题,`gofmt -w` 一条命令即可修复,
不涉及任何逻辑。`go vet ./...` 与 `go test ./...` 都通过。

**这说明 `4442758` 是在没跑 `make lint` 的情况下提交的。** 问题本身是琐碎的,
但一个公开仓库的首屏就是红色的 CI 徽章,而且新贡献者的第一个 PR 会因为一个
与他无关的既存失败而变红。**发布前必须先让 main 变绿。**

顺带:CI 里新加的 `collector` job 是唯一在仓库根目录之外用
`defaults.run.working-directory` 的 job,而 `Makefile` 的 `lint` 目标用
`gofmt -l . | tee /dev/stderr | (! read)` 实现同样的判断 —— 两处逻辑重复且写法不同,
将来容易只改一处。

---

### P0-1 四份文档描述了不存在的 CI 门禁与运行时能力

这几处是同一类问题:文档把「设计意图」写成了「已生效的机制」。读者据此判断项目成熟度,
贡献者据此判断自己的 PR 会被怎么检查,两边都会落空。

**`tests/golden/README.md:99-100`** 的「在 CI 中的位置」表格写明:

> 每次 PR | `golden-verify`,全部用例必须通过
> 新增算子 | 必须同时新增覆盖该算子的用例,否则 schema 覆盖率检查失败

实际情况:`Makefile:83-85` 的 `golden-verify` 就是一句
`@echo "🚧 尚未实现"`;`.github/workflows/ci.yml` 的五个 job
(`secrets` / `schema` / `privacy` / `docs` / `reference-engine`)中没有任何一个调用它,
也不存在「schema 覆盖率检查」。golden 测试目前**连本地都跑不起来**,`tests/golden/`
目录下只有一份 README。

**`CONTRIBUTING.md:28`**:「不要手工修改生成的类型文件,**CI 会检出**。」
—— 仓库里根本没有生成的类型文件。`Makefile:58-64` 的 `gen-java` / `gen-ts`
都是 🚧 占位,`ci.yml` 也没有对应检查。

**`CONTRIBUTING.md:30`**:「新增一个算子需要同时提供:schema 声明、引擎实现、
单元测试、以及文档。**缺少任何一项 CI 都会失败**。」
—— 通读 `ci.yml` 五个 job 与 `tools/validate_seeds.py`,没有任何算子级别的
四项交叉校验。这条规则是好规则,但目前靠人工评审执行,不是 CI 执行。

**`SECURITY.md:42-44`** 的「供应链」一节:

> - 每次构建生成 SBOM
> - 依赖与镜像扫描(Trivy / Grype)接入 CI
> - 依赖版本锁定,定期自动升级

实际情况:`ci.yml` 里没有 SBOM 生成步骤、没有 Trivy / Grype、没有 dependabot 配置,
仓库里也没有任何依赖锁文件。

`4442758` 之后这条的性质变了 —— 采集器落地后仓库**确实开始构建东西**了
(`make build-collector` 产出一个 9 MB 的 Go 二进制),所以「每次构建生成 SBOM」
不再是无从谈起,而是**明确的未兑现承诺**。采集器目前零外部依赖
(`apps/collector/go.mod` 只有一行 `go 1.22`),现在正是接入 SBOM 生成的最低成本时机:
依赖图还是空的,建立机制不必处理任何历史包袱。

**`docs/security/privacy.md`** 通篇用现在时描述运行时能力,且**全文没有一个 🚧 标记**,
`docs/README.md:44` 引用它时也没标 🚧 —— 按本项目的文档惯例,这意味着「已完成」。
具体例子:

- `privacy.md:118`:「标注为 `pii` 的字段以 HMAC 形式存储。HMAC 密钥独立于数据库,
  支持轮换……即使数据库被拖库,攻击者也无法直接得到原始账号或设备 ID。」
- `privacy.md:120`:「保留期自动执行:由 ClickHouse 的 TTL 与 PostgreSQL 的分区裁剪
  自动完成,不依赖运维脚本。」
- `privacy.md:141-142`:给出了具体接口路径
  `GET/DELETE /api/v2/privacy/subject/{type}/{value}[/export]`。

这些全部依赖 collector / console-api / 存储层,而这三者都还没有一行代码。

`4442758` 让这篇文档的情况明显好转:新增的「采集端脱敏 vs 存储层保护」一节
准确描述了已实现的采集端行为,「内置资产的字段分级」一节给出的
`sensitive` 39 / `pii` 47 / `internal` 98 也与 `seeds/events/` 实际标注一致。
**但上面引的三处仍然全部指向未实现的存储层与控制面**,它们与已实现的部分混在同一篇文档里,
没有任何标记区分。

**这篇文档还有一部分本来就是真的**:schema 层的强制约束确实已生效 ——
`packages/domain-schema/variable-model.schema.json` 用 `if/then` 规定 `pii` /
`sensitive` 变量必须声明 `value_masking`,`tools/validate_seeds.py:91-110`
强制 profile 层可读类型变量显式声明 `sensitivity`,两者都在 CI 里真的会跑。
问题在于文档把「静态声明层已强制校验」与「运行时功能已实现」混在一起讲,
读者无法区分。

**建议处理**:统一采用 README 已经用的标注方式。给这四份文档的相关小节加
「🚧 设计,尚未实现」的显式标注,或在文档开头加一段状态说明。**不建议删掉这些内容**
—— 设计意图本身是有价值的,问题只是没标清楚它是意图。

---

### P0-2 许可证归属链条不完整

**没有 `NOTICE` 文件。** `find . -iname "NOTICE*"` 只命中
`packages/domain-schema/notice.schema.json`,那是「风险告警通知」这个业务对象的
JSON Schema,与法律意义上的 NOTICE 无关。

**1.x 的许可证在整个仓库里查不到。** 这是最实际的问题:

- `seeds/INVENTORY.md:3` 与 `seeds/tools/extract_seeds.py:4-5` 明确说明
  17 个事件模型、253 个变量、170 条策略模板提取自 1.x 的数据库初始化 SQL
- README、`SECURITY.md` 多处链接到 `github.com/threathunterX/nebula`
- 但通读 README、`SECURITY.md`、`CONTRIBUTING.md`、`docs/migration/from-1x.md`、
  `seeds/INVENTORY.md`、`seeds/PLACEHOLDERS.md`,**没有任何一处说明 1.x 采用什么许可证**,
  也没有说明这批资产以 Apache-2.0 重新授权的依据

`git log` 显示提交者统一是 `threathunterX <opensource@threathunter.cn>`,
2.0 与 1.x 大概率同属一个版权主体,重新授权的法律风险低。**但这是推断,不是仓库里写着的。**
一个明确宣称「继承了另一个项目的资产」的开源项目,必须把这层关系写清楚。

**`packages/reference-engine/src/hll.js:28-66` 的 MurmurHash3 实现出处未标注。**
文件头注释只说明了 HyperLogLog 的参数选择依据,没提哈希函数的来源。这段代码的魔数
(`0xcc9e2d51`、`0x1b873593`、`0xe6546b64`、`0x85ebca6b`、`0xc2b2ae35`)、
`nblocks` 主循环加 `switch` fallthrough 处理尾字节的结构、变量命名
(`c1`/`c2`/`h1`/`k1`/`tail`/`nblocks`),与多个已有的 JS 移植版本高度一致。
算法本身(Austin Appleby)是公有领域,**不构成法律问题**,但需要作者人工确认:
这是照着 C++ 规格独立实现的,还是从某个带许可证的 JS 库转译的?如果是后者,
该库的许可证声明应当出现在 NOTICE 里。

**建议处理**:新建 `NOTICE`,写明版权主体、与 1.x 的资产继承关系及其授权依据、
以及任何第三方代码的出处。在 `seeds/INVENTORY.md` 顶部补一段资产授权说明。

---

### P0-3 `make validate` 需要一个未文档化的第三方包

`4442758` 带来了 `apps/collector/go.mod`,这是仓库里**第一个也是唯一一个**依赖清单。
仍然不存在 `requirements.txt` 与 `package.json`。

**具体后果**:`tools/validate_seeds.py:11-14` 和 `Makefile:16-22` 的
`validate-schema` 都需要 `jsonschema`。这个依赖**只出现在 `.github/workflows/ci.yml:33`
的一行 `pip install jsonschema` 里**,没有版本号,`CONTRIBUTING.md` 完全没提。

也就是说,一个新贡献者按 `CONTRIBUTING.md:44-49` 的指引跑 `make validate`,
会直接撞到 `需要 jsonschema:pip install jsonschema`,然后得自己去翻 CI 配置
才知道该装什么。脚本的错误提示写得不错,但这不该是发现依赖的方式。

`packages/reference-engine` 确实零第三方依赖(核对过全部 `require()`,
只有 `fs` / `path` / `node:test` / `node:assert` 与相对路径),严格说不需要
`package.json` 才能跑。但作为 `packages/` 下自称 package 的模块,连名称、版本、
许可证、Node 版本要求这些基本元数据都没有 —— README 说需要 Node.js 18+,
这个约束没有任何机器可读的表达。

**建议处理**:加 `requirements-dev.txt`(至少写明 `jsonschema>=4.18`)并在
`CONTRIBUTING.md` 的「本地检查」一节说明;给 `packages/reference-engine` 加
最小 `package.json`(name / version / license / engines / scripts)。

---

### P0-4 `docs/README.md` 把已完成的快速开始仍标为 🚧

`docs/README.md:10`:

该行把 `guide/quickstart.md` 的表格项标上了 🚧,描述为「5 分钟跑通第一条告警」。

但 `docs/guide/quickstart.md` 在 `cadca32` 中已改写为 160 行的可实跑教程,
每条命令都经过验证。README 首页把它当作项目的主要入口来推,文档索引却告诉读者
这篇还没写。

这个方向的错误(把完成的标成未完成)比反过来危害小,但它说明 🚧 标记的维护是手工的、
容易漏 —— `cadca32` 改了 quickstart 却没同步 `docs/README.md`。

**建议处理**:去掉这个 🚧。更长远地,考虑让 `check_doc_links.py` 顺带检查
「标了 🚧 的文档是否短于 N 行」,把这类漂移变成 CI 能发现的问题。

---

### P0-5 README 的仓库结构图列出了两个 clone 后不存在的目录

`README.md:118` 的仓库结构图列出 `deploy/  # compose(Lite) / helm(Cluster)`。
实际上 `deploy/compose` 与 `deploy/helm` 都是**空目录**,`git ls-files deploy` 无输出
—— git 不追踪空目录,所以**使用者 clone 下来根本不会有 `deploy/` 目录**。
`docs/assets/` 同样是空目录且无追踪文件,而 `docs/README.md:58` 明确规定
「图片一律存放在仓库内 `docs/assets/`」。

`README.md:130` 上那个指向 `seeds/` 目录的链接,其目标目录下没有 `README.md`,
在 GitHub 上点开只有一列裸文件名(其余同类链接 `docs/`、`docs/adr/`、
`tests/golden/`、`packages/reference-engine/` 都有 README 会被渲染成说明页)。

**建议处理**:给 `deploy/compose`、`deploy/helm`、`docs/assets` 各放一个
带说明的 `.gitkeep` 或占位 README;补 `seeds/README.md`,或把链接改指向
`seeds/INVENTORY.md`。

---

## 3. P1 —— 首个 tag 前应处理

### P1-1 `make validate` 的自我描述不准确:它不含隐私检查

`Makefile:14` 的注释写着「跑全部校验(**等同 CI 的检查项**)」,但:

| CI job | 是否被 `make validate` 覆盖 |
|---|---|
| `schema` | ✅ `validate-schema` + `validate-seeds` |
| `docs` | ✅ `docs-check` + `links` |
| `reference-engine` | ✅ `test-reference` |
| `privacy` | ❌ **未覆盖**,`privacy-check` 是独立目标 |
| `secrets` | ❌ **未覆盖**,`secrets-scan` 是独立目标 |

对一个把数据合规当作核心纪律的项目来说,这个缺口尤其值得修 —— 贡献者跑了
「等同 CI」的命令,恰恰漏掉了隐私与凭据这两道最重要的门禁。

**建议处理**:要么把 `privacy-check` 加进 `validate` 依赖(`secrets-scan`
因为需要外部工具可以保持独立,但应在 help 文本里说明),要么改掉那句「等同 CI」。

### P1-2 `make lint` 只覆盖 Go,不覆盖 Python 与 JavaScript

`4442758` 把 `make lint` 从空壳(原先只打印「🚧 尚未实现」)改成了真实检查,
这是明确的改进。但它现在**只对 `apps/collector` 跑 `gofmt` 与 `go vet`**。

仓库里另有 7 个 Python 脚本(`tools/`)、1 个提取器(`seeds/tools/`)和
约 12 个 JavaScript 文件(`packages/reference-engine/`),**一个都不在 lint 覆盖范围内**,
CI 里也没有对应的 job。`CONTRIBUTING.md:45` 的注释仍是笼统的「代码风格」,
会让贡献者以为改 Python 或 JS 也被检查了。

建议:要么在 help 文本里写清楚覆盖范围,要么补上 `ruff` / `eslint`
(前者对现有 7 个脚本几乎零配置成本)。

### P1-3 CI 的 `privacy` job 缺 `setup-python`

`.github/workflows/ci.yml:51-57` 是五个 job 里唯一一个没有 `actions/setup-python@v5` 的。
`schema`(29-31 行)与 `docs`(64-66 行)都显式固定 `python-version: '3.12'`,
`privacy` 直接用 runner 镜像自带的系统 Python。镜像更新导致系统 Python 变化时,
这个 job 的行为可能在没有任何代码改动的情况下漂移 —— 而它恰好是隐私合规门禁。

### P1-4 隐私检查工具的已知盲区未被记录

`tools/check_no_pii.py` 是个有价值的工具,但它的实际覆盖范围比文档给人的印象窄。
把盲区写下来,才不至于让人误以为过了 CI 就等于没泄露。

- **`SKIP_DIRS` 包含 `"assets"`(第 14 行)** —— 也就是说 `docs/assets/`
  **整个目录被排除在隐私扫描之外**。而这恰恰是项目规定放图片的地方,
  也是最可能混入真实控制台截图(含真实 IP、真实用户 ID)的地方。
  跳过二进制图片本身合理(`SKIP_SUFFIX` 已按扩展名处理),但按目录名整体跳过
  会连该目录下的 `.md`、`.json`、`.svg` 一起放过。
- **身份证号(27-28 行)与银行卡号(30-31 行)只做正则形态匹配**,
  判定函数就是 `lambda m, line: True`,不做校验位 / Luhn 校验。银行卡正则
  `(62|4\d|5[1-5])\d{14,17}` 几乎能匹配任何以 4 或 5 开头的 15-18 位数字串
  (哈希值、拼接时间戳等),误报面很宽。而脚本给出的补救方式是往允许列表加例外
  —— 长期看会侵蚀检查的严格性。 **v0.1.0 复审时已修**:银行卡加 Luhn 校验、
  身份证加 GB 11643 校验位。真实号码必然通过校验,随机数字串基本不会 ——
  这是提高精确率而不降低召回率的改动。
- **完全不检查**:IPv6 地址、非中国大陆手机号、真实姓名。
- **邮箱白名单是子串匹配**:`ALLOWED_DOMAINS.search(m.group(0))`,
  所以把允许域名嵌进自己的域名里(前面加前缀、后面接别的后缀)就能通过。
  **v0.1.0 复审时已修**:改为取 `@` 后的域名做精确比对,子域放行、伪装不放行。
  (此处不再写出具体的绕过样例 —— 写出来会被这个检查自己抓到,
  这本身就说明修复生效了。)
- 公网 IP 正则的组播段排除只写了字面量 `224.`,没覆盖完整的 `224.0.0.0/4`
  (224-239)。次要瑕疵,不构成泄露风险。 **v0.1.0 复审时已修**,同时补上 240.0.0.0/4 保留段。

`tools/check_doc_links.py` 同样有未记录的盲区:不检查引用式链接 `[text][ref]`
(正则完全忽略,也不计入 checked 计数)、不检查锚点片段是否真的存在于目标文档、
不检查 HTML `<a href>`。另外它用 `Path.resolve().exists()` 判断存在性,
在大小写不敏感的文件系统(默认配置的 macOS)上,大小写拼错的链接**本地会通过而
CI 会失败** —— CI 不会漏检,但贡献者会遇到「本地好好的」的困惑。

### P1-5 「五大场景」与「六大场景」自相矛盾

- `README.md:50`:「覆盖访客/账号/支付/订单/营销**五大场景**」
- `docs/migration/from-1x.md:130`:「`scene`(**六大场景**)」
- `packages/domain-schema/enums.json` 的 `scene` 枚举实际有 6 个值:
  `VISITOR` / `ACCOUNT` / `TRANSACTION` / `ORDER` / `MARKETING` / `OTHER`

README 漏掉的是 `OTHER`(其他)。同一个枚举在三处有三种说法。

### P1-6 README 的定位声明与内置资产内容不符

`README.md:36`:「星云是一套业务风控系统 —— 注意不是 WAF,也不是主机安全。
它关心的不是『这个请求有没有 SQL 注入』」。

但 `seeds/strategies/` 里有 13 条策略(`visit_sql_injection_*`、`visit_xss_*`、
`visit_rfi_*`、`visit_directory_traversal_*`)的 `tags` 就是
`SQL注入` / `XSS` / `RFI` / `目录遍历`,是纯粹的 WAF 特征匹配规则。
生成文档 `docs/reference/strategies.md:528` 里有一句限定「作为 WAF 的补充而非替代」,
但这个限定词从未出现在 README 里 —— 读 README 得到的印象与实际资产内容是冲突的。

这不是必须删掉那些策略,而是 README 那句话该说得更准一点。

### P1-7 `seeds/` 下两份文档是英文,与全仓库中文文档体系不一致

`seeds/INVENTORY.md` 与 `seeds/PLACEHOLDERS.md` 的正文是英文(策略名保留中文),
而 README、`docs/` 全部是中文,且 README 把 INVENTORY 作为「数据来源声明」的
权威出处推给读者。这两份文档恰好是审计结论与占位符约定这两件最需要读懂的事。

### P1-8 `seeds/tools/extract_seeds.py` 的自述路径是错的

该文件 docstring 里的用法示例(`seeds/tools/extract_seeds.py:23`)写着
`python3 tools/extract_seeds.py --sql ... --out ...`,但文件实际在
`seeds/tools/extract_seeds.py`,照抄会报文件不存在。同一个错误路径还被写进了生成器
(`seeds/tools/extract_seeds.py:693`),因此出现在它生成的 `seeds/INVENTORY.md:3`
里 —— 而 README 正是把 INVENTORY 作为资产来源的权威出处推给读者的。

`check_doc_links.py` 抓不到这个,因为它只检查 Markdown 链接语法,
不检查散文里的路径字符串。

### P1-9 `seeds/INVENTORY.md` 里一条已完成的发布前动作仍写成待办

`seeds/INVENTORY.md:169` 写:「……that file is the only place the real identifiers
appear, and it should be removed or git-ignored before the seed set is published.」

实际上这件事**已经做完了**:`.gitignore:23` 有 `**/sanitize_rules.json`,
且 `git ls-files | grep sanitize_rules` 无输出,文件也不在工作区。
但文档仍把它写成未完成的发布前动作,任何做发布审查的人都得重新核实一遍。

### P1-10 `.gitignore` 忽略采集器二进制的规则写错了路径,9 MB 产物不会被忽略

`4442758` 在 `.gitignore` 末尾加了一行:

```
collector/nebula-collector
```

**这条规则永远匹配不到东西。** 含斜杠的 gitignore 模式是相对 `.gitignore` 所在目录锚定的,
所以它匹配的是仓库根下的 `collector/nebula-collector`,而实际产物在
`apps/collector/nebula-collector`(`Makefile` 的 `build-collector` 目标里
`cd apps/collector && go build -o nebula-collector ./cmd/nebula-collector`)。

实测确认:

```
$ make build-collector
已构建 apps/collector/nebula-collector          # 9.0 MB
$ git check-ignore -v apps/collector/nebula-collector
$ echo $?
1                                               # 未被任何规则忽略
$ git status --porcelain apps/collector
?? apps/collector/nebula-collector
```

任何人跑一次 `make build-collector` 再 `git add -A`,就会把一个 9 MB 的二进制提交进仓库
—— 而且是在一个刚刚才有构建产物的项目里,还没人形成检查习惯。

**修法**:改成 `apps/collector/nebula-collector`,或用不锚定的 `nebula-collector`。
(本次审查中构建的二进制已删除。)

### P1-11 `.gitignore` 缺 Python 相关规则

`.gitignore` 覆盖了 Java / Node 构建产物、密钥、IDE、系统文件,但**完全没有
Python 忽略规则**(`__pycache__/`、`*.pyc`、`.pytest_cache/`、`.coverage`、
`venv/`、`.venv/`)。

这不是「以后才用得上」:`tools/` 下七个 Python 脚本是现有代码,任何人跑一次
`python3 tools/validate_seeds.py` 就会在工作区生成 `__pycache__/`。
而且 `tools/check_no_pii.py:14` 自己的 `SKIP_DIRS` 里写了 `.venv`,
说明作者预期贡献者会建虚拟环境,但 `.gitignore` 没忽略它。

另外还缺编辑器交换文件(`*.swp`、`*.swo`、`*~`)与覆盖率产物(`coverage/`、
`.nyc_output/`)。Go 侧除了 P1-10 那条写错路径的规则外,还缺 `vendor/` 与
`*.test`(`go test -c` 的产物)。

---

## 4. P2 —— 记录在案,可排期

### P2-1 GitHub Actions 只固定到浮动的主版本标签

`actions/checkout@v4`、`actions/setup-python@v5`、`actions/setup-node@v4`、
`actions/setup-go@v5`、`gitleaks/gitleaks-action@v2` 全部只固定主版本,
没固定到 commit SHA。
对一个安全导向的项目,尤其是 `gitleaks-action` 这个**凭据扫描门禁本身**,
供应链固定的价值更高。

CI 也没有配置 `concurrency` 分组,同一 PR 连续 push 会产生重叠运行(成本问题)。

**权限方面没有问题**:`ci.yml:8-9` 的顶层 `permissions: contents: read` 全局生效,
没有任何 job 申请更多权限,符合最小权限原则。

### P2-2 缺少的常规仓库元数据

均已核实不存在:

- `NOTICE`(见 P0-2,有法律实质,已列为 P0)
- 源文件的 license header / `SPDX-License-Identifier`(全仓库 0 处匹配)
- `.github/dependabot.yml`
- `CODEOWNERS`
- `AUTHORS` / `MAINTAINERS`
- `GOVERNANCE.md` —— 项目定位为社区项目的话,决策权归属最好在还没有争议时写清楚
- `.editorconfig`
- README 没有 CI 状态徽章(现有两个徽章是静态的 License 与 Status 文案)
- 没有任何 git tag(`git tag` 返回空)—— 这是预期内的,首个 tag 的流程见
  [发布流程](release-process.md)

### P2-3 `.github/ISSUE_TEMPLATE/config.yml` 引用了尚未启用的 Discussions

本次新增的 issue 模板配置里有一条指向
`https://github.com/threathunterX/nebula2/discussions` 的联系链接。
仓库公开后**需要在设置里启用 Discussions**,否则这个链接会 404。
如果不打算启用,把这条删掉。

### P2-4 占位文档的实质内容

`docs/` 下标 🚧 的九篇是纯提纲(均以「🚧 本文档尚未编写」开头,列 5-8 条计划要点,
没有可执行内容):

| 文件 | 行数 |
|---|---|
| `docs/reference/api.md` | 19 |
| `docs/guide/integration.md` | 18 |
| `docs/reference/configuration.md` | 18 |
| `docs/guide/strategy.md` | 16 |
| `docs/guide/cel-reference.md` | 16 |
| `docs/operations/deployment.md` | 15 |
| `docs/security/threat-model.md` | 15 |
| `docs/operations/capacity.md` | 13 |
| `docs/operations/monitoring.md` | 13 |

**这不算问题** —— `docs/README.md` 顶部明确说了「标记为 🚧 的文档尚未完成」,
提纲页的存在是为了让链接不 404(`check_doc_links.py` 首次运行抓到过 19 个失效链接)。
列在这里是为了给出规模感:**文档体系里约有九分之一的入口目前是空的**,
评估者应当知道这个比例。

`apps/` 下另外三个组件(`engine` 11 行、`console-api` 9 行、`console-web` 7 行)
仍是职责说明加 🚧,零实现代码,与 README 项目状态表中的「🚧 未开始」一致。
`apps/collector/README.md` 在 `4442758` 后已扩充到 156 行的实用文档。

### P2-5 生效状态与阈值仍需使用方判断

170 条策略的 `status` 现在全部是 `test`(已核实),这是刻意的选择 ——
避免导入即全量生效造成告警洪水。但这意味着**开箱导入后一条策略都不会实际处置**,
使用方必须逐条评估阈值后手动转为 online。这个事实在 `seeds/INVENTORY.md`
里有记录,但 README 的「开箱即用的风控知识 ✅」这个措辞会让人预期更高。

另有 10 条策略需配置占位符后才能生效,其中未配置时会**恒真**(把正常用户全打中)
的问题已在 CHANGELOG 的「已知问题」中记录。

---

## 5. 调试代码与临时文件扫描

**结果:干净**(唯一的例外是 P0-0 的 gofmt 失败,那是格式而非残留代码)。
记录扫描方法以便下次复核。

- **TODO / FIXME / XXX / HACK**:对 `tools/*.py`、`seeds/tools/*.py`、
  `packages/reference-engine/**/*.js`、`apps/collector/**/*.go` 及全仓库
  `.py` / `.js` / `.go` / `.md` 做 `grep -rnE "TODO|FIXME|XXX|HACK"`,**零匹配**。
- **调试输出**:逐一核对了 `tools/*.py`(`validate_seeds.py:64,89,110,113-120`、
  `check_no_pii.py:92-101`、`gen_seeds_index.py:115-126`)与
  `packages/reference-engine/run.js:35-91`,全部是脚本本身的正常 CLI 输出,
  没有 `print("debug")` / `console.log(x)` 这类残留。
  采集器的 Go 代码里只有一处 `fmt.Println`
  (`apps/collector/cmd/nebula-collector/main.go:54`),是 `--version` 的正常输出。
- **注释掉的代码块**:用启发式正则扫描全部目标文件,零匹配。
- **硬编码本地路径**(`/Users/`、`/home/`、`C:\`):只命中
  `seeds/strategies/visit_directory_traversal_*.json:42` 及其生成文档
  `docs/reference/strategies.md:532-533`,那是目录遍历检测策略的**攻击特征正则**
  (`c:\\\\|cmd\.exe|/etc/passwd`),不是真实路径。
- **杂物文件**:`.DS_Store`、`*.bak`、`*.orig`、`*.swp`、`*.tmp`、`~$*`、
  `__pycache__`、`node_modules`、`.idea`、`.vscode`、`*.log` 全部零命中。
- **`sanitize_rules.json`**(唯一含真实标识的文件)确认不在工作区且不被追踪。

---

## 6. 建议的处理顺序

发布前(P0):

0. **先让 main 变绿**:`cd apps/collector && gofmt -w internal/mask/mask.go`
   (顺手把 `.gitignore` 里 P1-10 那条路径改对,它同属「提交前没跑本地检查」这一类)
1. 给 `SECURITY.md`、`docs/security/privacy.md`、`tests/golden/README.md`、
   `CONTRIBUTING.md` 的相关小节补状态标注
2. 新建 `NOTICE`,写清版权主体、1.x 资产的继承与授权关系、第三方代码出处;
   确认 `hll.js` 中 MurmurHash3 的来源
3. 加 `requirements-dev.txt` 与 `packages/reference-engine/package.json`,
   在 `CONTRIBUTING.md` 说明依赖
4. 去掉 `docs/README.md:10` 上快速开始的 🚧
5. 给 `deploy/*`、`docs/assets/` 补占位文件;补 `seeds/README.md`

首个 tag 前(P1):按 §3 逐条,其中 P1-1(`make validate` 不含隐私检查)
与 P1-4(隐私工具盲区)优先 —— 它们直接关系到本项目最看重的那条纪律。

---

## 7. 本次审查新增的文件

审查过程中一并补齐了成熟开源项目应有的协作与治理文件:

| 文件 | 说明 |
|---|---|
| `.github/ISSUE_TEMPLATE/bug_report.yml` | 缺陷报告,含强制的数据合规确认与当前可报告范围说明 |
| `.github/ISSUE_TEMPLATE/feature_request.yml` | 功能建议与 ADR 异议,要求申报方案代价 |
| `.github/ISSUE_TEMPLATE/strategy_contribution.yml` | 策略模板贡献(本项目特有),要求申报效果验证与已知误报场景 |
| `.github/ISSUE_TEMPLATE/config.yml` | 关闭空白 issue,把安全漏洞导向私下报告 |
| `.github/PULL_REQUEST_TEMPLATE.md` | 含 `make validate`、真实数据、领域模型同步三组检查清单 |
| `.github/workflows/pr-title.yml` | PR 标题的 Conventional Commits 检查 |
| `CODE_OF_CONDUCT.md` | Contributor Covenant 2.1 中文版 |
| `CHANGELOG.md` | Keep a Changelog 格式,`[Unreleased]` 由现有提交整理而来 |
| `docs/development/release-process.md` | 版本号规则、发布前检查清单、打 tag、CHANGELOG 维护 |
| `docs/development/release-readiness.md` | 本文档 |

**这些文件本身不改变项目的成熟度**,它们只是让协作有章可循。上面列的 P0 / P1
才是决定发布质量的部分。

---

## 8. 已核实通过的项目

列出来是为了说明审查覆盖到了哪些方面,以及哪些说法是经过验证的。

**资产数量全部属实**(实测):

| 声称 | 实测 |
|---|---|
| 17 个事件模型 | 17 ✓ |
| 253 个统计变量 | 253 ✓ |
| 170 条策略模板 | 170 ✓ |
| 15 个风险标签 | 15 ✓ |
| 39 个画像变量 | 按 `module` 统计:base 19 / realtime 97 / slot 98 / profile 39 = 253 ✓ |
| 订单 70 / 账号 60 / 访客 40 | 按 `category` 统计 ORDER 70 / ACCOUNT 60 / VISITOR 40 ✓ |
| `HTTP_DYNAMIC` 30 个基础字段 | 30 ✓ |
| 6 处语义差异 | `docs/migration/from-1x.md` 实列 6 条 ✓ |
| 52 个测试 | `node --test` 实跑 52 pass / 0 fail ✓ |
| 需配置才生效的策略 10 条 | `index.json` 与 `docs/reference/strategies.md:795` 一致 ✓ |

其中前三个数字被 `tools/validate_seeds.py:21-25` 硬编码为断言,数量对不上 CI 会红
—— 这个机制值得保留。

**其他已核实项**:

- `make validate` 通过;`make test` 通过(参考引擎 52 个测试 + 采集器全部包)
  —— **但 `make lint` 失败,见 P0-0**
- 采集器测试覆盖率实测:脱敏 90.4%、事件模型 82%、流水线 72.7%,
  与 `4442758` 的提交信息一致;`sink` 包覆盖率 0%,提交信息未提及这一点
- `go vet ./...` 通过
- `check_doc_links.py` 报告 163 个仓库内链接全部可达
- 生成文档与 seeds 一致(三个 `--check` 全部通过)
- 采集器落地后 `seeds/events/` 的 184 个字段已全部完成敏感级别分级
  (`sensitive` 39 / `pii` 47 / `internal` 98),与 `docs/security/privacy.md`
  中的表格一致
- `LICENSE` 是未经改动的官方 Apache-2.0 全文。附录中的
  `Copyright [yyyy] [name of copyright owner]`(第 191 行)**保留占位符是正确的**,
  GitHub 官方模板就是这样,不需要填
- CI 顶层 `permissions: contents: read`,无 job 申请额外权限
- `.gitignore` 对密钥类文件覆盖到位(`.env`、`*.pem`、`*.key`、`*.p12`、`*.jks`、
  `secrets/`,以及针对 `**/sanitize_rules.json` 的专门规则)
- 外部链接均指向常见正常域名,未发现指向不存在或可疑地址的情况
- README「核心特点」表中标 ✅ 的两项(开箱即用的风控知识、告警可解释)
  均可由读者用 `node run.js` 亲自验证

---

## 相关文档

- [发布流程](release-process.md)
- [贡献指南](../../CONTRIBUTING.md)
- [更新日志](../../CHANGELOG.md)
- [安全策略](../../SECURITY.md)
- [seeds 资产清单与审计结论](../../seeds/INVENTORY.md)
