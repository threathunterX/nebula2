# 更新日志

本文件记录本项目所有值得注意的变更。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/),
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。发布流程与版本号规则见
[发布流程](docs/development/release-process.md)。

> **当前尚未发布任何版本。** 下面 `[Unreleased]` 段落中的内容全部来自尚未打 tag 的提交。
> 首个 tag 计划为 `v0.1.0`,含义见发布流程文档中的「0.x 阶段的语义」。

## [Unreleased]

### Added

- **领域模型定义(单一真相源)**:以 JSON Schema 定义事件、变量、策略、告警四类领域对象
  (`packages/domain-schema/`),并以 `enums.json` 收敛枚举。目的是根除 1.x 中
  Python 与 Java 两套模型各自漂移的问题。
- **内置风控资产**:从 1.x 出厂配置迁移并审计的 17 个事件模型、253 个统计变量、
  170 条策略模板、15 个风险标签(`seeds/`),已剔除全部真实客户与个人信息。
  清单与逐项审计结论见 `seeds/INVENTORY.md`,需使用方替换的占位值见 `seeds/PLACEHOLDERS.md`。
- **算子语义规格**(`docs/reference/operators.md`):逐个定义算子的输入输出类型、
  空窗口行为、null 处理,并标注与 1.x 的每一处差异。配套的类型推导规则见
  `docs/reference/type-inference.md`。
- **参考引擎**(`packages/reference-engine/`):算子语义规格的可运行实现,零外部依赖,
  只用 Node 内置模块。用途是验证规格自洽、为 golden 回归测试生成基线、
  为后续的 Java 引擎提供可对照的参照物。**不用于生产。**
- **变量计算图与事件继承**:参考引擎按策略引用构建变量依赖闭包(253 个变量中取 49 个)、
  拓扑排序,实现 event / filter / aggregate / dual / sequence / top 六类节点;
  并实现事件模型的单继承链匹配,使定义在父事件上的变量与策略也能被子事件触发。
  引用变量的 38 条策略因此可以真正跑起来。
- **可实跑的场景**:撞库场景(160 条合成事件,准确挑出 2 个攻击 IP,零误报)与
  爬虫场景(单 IP 5 分钟 400 次请求,6 条独立策略交叉印证,40 个正常浏览 IP 零误报),
  固定随机种子,输出可复现。
- **告警可解释性**:每条告警附带判定依据(哪个指标、当前值多少、超了什么阈值)——
  这是 1.x 从未落地的能力。
- **CEL 业务扩展函数的规范语义**(`packages/cel-functions/`):定义 `inTimeWindow`、
  `ipLocation`、`checkNotice` 的语义与边界行为。
- **隐私标注体系**:变量新增 `sensitivity` 与 `value_masking` 两个字段及相应 schema 约束,
  强制 profile 层可读类型变量显式声明敏感度级别。该检查首次运行即逼出 13 个未评估变量,
  最终 20 个标注为 `pii`(要求 HMAC 存储)、7 个标注为 `internal`,逐条判定理由记录在
  `docs/security/privacy.md`。
- **工程校验工具**(`tools/`):
  - `validate_seeds.py` —— seeds 资产的 schema 符合性、引用完整性、隐私标注齐全性校验
  - `check_no_pii.py` —— 扫描仓库是否混入真实个人信息或客户标识
  - `check_doc_links.py` —— 文档内部链接可达性检查,首次运行即抓到 19 个失效链接
  - `gen_variable_reference.py` / `gen_strategy_reference.py` / `gen_seeds_index.py` ——
    参考文档与索引的生成器,均支持 `--check` 模式作为 CI 门禁
  - `convert_strategies.py` —— 1.x 扁平 terms 结构到 2.0 条件树的转换器
- **CI 门禁**(`.github/workflows/ci.yml`):凭据扫描(gitleaks)、领域模型与资产校验、
  隐私合规检查、文档与资产一致性、参考引擎规格符合性,共五道。
- **Makefile**:实现文档中引用的各项命令。尚未实现的目标(`gen-java`、`gen-ts`、
  `lint`、`golden-verify`、`golden-capture`、`up`)以 🚧 标注并明确打印「尚未实现」。
- **文档体系**:核心概念(风控数据模型、系统架构)、1.x 迁移指南(含 6 处语义差异)、
  隐私设计与合规、5 篇架构决策记录、golden 回归测试规范,以及自动生成的
  变量全表与策略模板参考。
- **快速开始**:从占位提纲改写为可实跑的教程,每条命令均已验证,示例输出与实际
  运行结果逐字对齐。
- **协作与治理文件**:行为准则(Contributor Covenant 2.1)、issue 模板
  (缺陷报告 / 功能建议 / 策略模板贡献)、Pull Request 模板、本更新日志、
  发布流程文档、发布就绪度审查报告、PR 标题的 Conventional Commits 检查。

### Changed

- **策略模板转为 2.0 结构**:170 条策略从 1.x 的扁平 `terms` 结构转换为 2.0 的
  条件树 + `action` 结构,覆盖全部 7 类条款(607 个)。`sleep` 条款转为策略级
  `delay` 字段,`time` / `getlocation` 条款转为 CEL 表达式。转换后 170/170 通过
  schema 校验,并纳入 `validate_seeds.py`。
- **策略索引改为派生生成**:`seeds/strategies/index.json` 此前是手工维护的,
  策略转 2.0 后仍带着 1.x 的 `status=online` 与 `term_count`,与实际文件脱节而无人发现。
  现由 `gen_seeds_index.py` 从策略文件生成并纳入 CI 的 `--check` 门禁。
  重新生成后,「需要配置才能生效」的策略数量从 7 条修正为 10 条。
- **策略生效状态规范化**:170 条策略的生效截止时间原本全部落在过去
  (2017-10-27 ~ 2024-01-06)而状态均为 `online`,照原样分发会导致导入后每条策略
  都处于已过期状态、一条都不会触发且没有任何提示。已清空截止时间,同时把状态改为
  `test`,避免清空后导入即全量生效造成告警洪水。
- **迟到检测的水位线定义为流级**:此前规格未定义水位线的作用域。若按 key 维护,
  每个新 key 的首个事件永远不会被判为迟到,攻击者可用不断变化的 key 绕过检测。
  规格已补入该条,参考引擎按流级实现。
- **事件继承链纳入事件匹配**:规格此前未写明按事件名匹配时是否要考虑继承链。
  一条 `ACCOUNT_LOGIN` 事件同时也是一条 `HTTP_DYNAMIC` 事件,不考虑继承会导致
  定义在父事件上的变量与策略永远不被触发。
- **HyperLogLog 参数变更**:去重计数的近似模式改用 `log2m = 14`(16384 个 register,
  标准误差约 0.8%)。1.x 用的是 `log2m = 9`(误差约 4.6%)且与「前 20 个精确」的
  哈希集合混用,两者哈希函数还不同。**这是与 1.x 不兼容的语义变更。**
- **README 的项目状态改为逐项标注**:特性表区分「✅ 已实现」与「🚧 设计完成待实现」,
  明确写出当前能做什么、不能做什么,不做超前宣传。
- **策略参考文档适配 2.0 结构**:解析层重写以读取条件树,支持任意嵌套的
  and / or / not 渲染,新增「延迟求值(delay)策略」一节。

### Fixed

- **隐私检查的 User-Agent 版本号误报**:`Chrome/120.0.0.0` 这类版本号在形态上与 IP
  无法区分,导致 `check_no_pii.py` 误报。改为按上下文判别:紧跟斜杠的、或以 `.0.0`
  结尾的四段数字视为版本号。已用负例验证判别未被削弱(植入 `一个真实公网 IP` 仍会被拦截)。
- **迁移指南中关于策略 score 的失实表述**:原文称 1.x 策略的 `score`「全部是 0」,
  实际是 169/170 为 0,`设备请求下单行为单一` 为 1。已按数据修正。

### 已知问题(从 1.x 继承,保留原样仅标注)

以下缺陷源自 1.x 出厂数据,涉及风控业务判断,**未做修改**,仅记录并加了回归测试。
详见 `seeds/INVENTORY.md` 与 `packages/reference-engine/test/engine.test.js`:

- 6 条「多主体请求下单」策略的计数器过滤条件写成 `page contains "^\s*$"`,
  `contains` 是子串包含而非正则,该条件永不成立,计数器恒为 0,策略永不命中。
  作者本意显然是 `!regex`。已实证:构造 30 条完全符合该策略表面意图的订单事件,
  原策略命中 0 次;仅把 `contains` 改为 `!regex`,同一批事件命中 1 次。
- 含未配置占位符的策略(`IP请求登录前未访问必要资源` 等)判定「访问某页面的次数 == 0」,
  而占位页面不存在,条件恒真。在 160 条事件的场景中产生 84 条告警,把 40 个正常用户
  全部打中。这批策略在 `seeds/INVENTORY.md` 中单独标记 🔧,共 10 条需配置后才能生效。
- `IP集中请求登录` 写入名单的主体是设备号而非 IP,`设备集中请求登录` 反之。
  策略名与实际行为不符。
- 另有页面路径写成字面量、备注与实际条件不符等 4 类数据问题,逐条记录在
  `seeds/INVENTORY.md`。

[Unreleased]: https://github.com/threathunterX/nebula2/commits/main
