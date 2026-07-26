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
- **采集器**(`apps/collector/`,Go,零外部依赖,单二进制):事件模型与单继承链解析、
  数据源 stdin / file / http、输出 stdout / file、运行指标与脱敏命中统计。
  Kafka / syslog / Zeek 数据源尚未实现。
- **采集端脱敏引擎**:支持 drop / hash / partial / regex 四种动作,规则优先级为
  显式配置 > 字段声明 > 敏感级别推导 > 出厂高危字段规则。
  `c_body` 与 `uri_query` 采用正则脱敏而非整体丢弃,保留非敏感参数以便排查问题
  —— 这是可用性与隐私之间刻意的折中,规则可按业务覆盖。
- **事件字段敏感级别分级**:隐私文档从一开始就规定事件字段要分级,
  但 1.x 的事件模型里 184 个字段一个都没标注 —— 这个缺口是端到端跑通脱敏时才暴露的
  (`uid` 和 `c_ip` 原样输出)。现已完成全部分级:`sensitive` 39 / `pii` 47 /
  `internal` 98。两条隐私不变式已固化为测试:全部字段必须标注敏感级别、
  `sensitive` 必须声明脱敏方式,新增字段时无法跳过评估。
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
  隐私合规检查、文档与资产一致性、参考引擎规格符合性、采集器编译与测试,共六道。
- **Makefile**:实现文档中引用的各项命令。尚未实现的目标(`gen-java`、`gen-ts`、
  `golden-verify`、`golden-capture`、`up`)以 🚧 标注并明确打印「尚未实现」。
- **文档体系**:核心概念(风控数据模型、系统架构)、1.x 迁移指南(含 6 处语义差异)、
  隐私设计与合规、5 篇架构决策记录、golden 回归测试规范,以及自动生成的
  变量全表与策略模板参考。
- **快速开始**:从占位提纲改写为可实跑的教程,每条命令均已验证,示例输出与实际
  运行结果逐字对齐。
- **协作与治理文件**:行为准则(Contributor Covenant 2.1)、issue 模板
  (缺陷报告 / 功能建议 / 策略模板贡献)、Pull Request 模板、本更新日志、
  发布流程文档、发布就绪度审查报告、PR 标题的 Conventional Commits 检查。
- **控制面认证与授权**(`apps/console-api/auth/`):此前控制面完全没有认证,任何能连到
  端口的人都能改策略、查名单。现在人与服务分成两类主体且权限不重叠 —— 管理员不能调
  `/checkRisk`,服务令牌碰不到任何管理接口。针对性修复 1.x 的三处失败:口令由无盐单次
  SHA1 改为 **Argon2id**;服务令牌只存 SHA-256 哈希、明文仅在签发响应中出现一次
  (1.x 的 5 个 token 明文写在配置文件里,泄露后既无从察觉也无法轮换);令牌与来源网段
  是 **AND** 关系(1.x 是「来源 IP 在白名单 **或** token 匹配」,拿到 token 即可从任意
  来源冒充内部身份)。零默认口令:首次启动生成随机管理员口令并只打印一次。
  令牌校验的全部失败路径返回同一个 401,不透露失败原因,密文比对走常量时间。
- **账号管理**(`GET`/`POST /api/v2/users`):没有这个接口时系统里永远只有引导阶段那一个
  管理员,角色划分写在配置里却没人能被赋予,「最小权限」只是一句话。接口不接受调用方
  指定口令 —— 口令由服务端生成并只返回一次。
- **告警查询与趋势**(`GET /api/v2/alerts`、`/alerts/trend`):引擎产出的告警此前只写进
  ClickHouse 和 Redis,没有任何读取入口 —— 系统在报什么,运营看不到。`/checkRisk` 回答的是
  「这个主体现在有没有风险」,回答不了「昨天哪条策略在报、报了多少、依据是什么」。
  主体值按角色分级:`VIEWER` 看掩码值,`OPERATOR` / `ADMIN` 看原值;按主体精确查询单独
  记审计,且审计里存的是掩码值。查询条件全部走 ClickHouse 的 `{name:Type}` 参数化,
  排序列走白名单,会话开 `readonly=1`。强制带时间范围且不超过 90 天 —— 告警表按
  `toDate(notice_time)` 分区,不给范围就是全表扫描。
- **策略编辑**(`PUT /api/v2/strategies/{name}`):170 条策略此前是只读的,系统没法适应
  业务变化。校验分两层:结构按 `packages/domain-schema/strategy.schema.json` 校验(schema
  随构建打进 jar);引用则是 schema 管不了的那层 —— `counter.event` 指向的事件、`groupby`
  / `operand` / `filter.object` 用到的字段必须真实存在,因为 `"event": "ORDER_SUBMITT"`
  结构上完全合法、能保存能上线,然后永远不命中也永远不报错。`expected_version` 必填,
  版本冲突返回 409 而不是静默覆盖。
- **策略修订历史**(`strategy_revisions` 表):每次写入存一份改动后的完整快照,回滚表现为
  一个新版本,历史只增不改。没有历史时「昨天这条策略为什么突然报了十倍」查不出来 ——
  定义表里只有当前值;审计日志记得了「发生过修改」,记不下改前改后的完整定义。
- **元数据下发**(`GET /api/v2/metadata/bundle`、`/version`):引擎带 `--console-url` 即从
  控制面加载事件、变量与策略。令牌从环境变量 `NEBULA_CONSOLE_TOKEN` 取,不走命令行参数
  (`ps aux` 对同机所有用户可见)。`/bundle` 默认只下发 `online` 与 `test` 状态的策略 ——
  `inedit` 是没写完的草稿,发给引擎等于让草稿直接影响线上判定。
- **容器化与部署编排**:三个组件此前一个 Dockerfile 都没有,全靠手工启动。现在
  `docker compose up -d` 依次完成建表 → 导入 170 条策略 → 启动控制面 → 启动 Flink 集群,
  提交作业即产出告警(已从 `down -v` 全新验证)。采集器用 distroless(14MB,镜像里没有
  shell 也没有包管理器);引擎基于官方 `flink:1.20` 镜像;建表与种子导入打进镜像而不是
  bind mount 宿主目录 —— 后者让「能不能起来」取决于仓库在宿主上的位置和 Docker 的文件
  共享配置,而失败表现为目录为空、不是报错。
- **CI 新增两栏**:控制面测试(认证与授权矩阵)、容器镜像构建。后者还断言了四件事:
  作业 jar 是 Java 17 字节码、自带 KafkaSource 与 Jackson、checkpoint 目录属主是 flink、
  采集器镜像里不存在 shell。

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
- **`pii` 级别的默认处理位置由采集端改为存储层**:最初把 `pii` 的默认动作设成
  采集端 HMAC,端到端跑通后发现那会直接打断风控 —— `c_ip` 一旦在采集端哈希,
  地理定位、IP 信誉、跨维度关联全部失效。正确分层是:`sensitive` 在采集端就地脱敏
  (原文不出边界),`pii` 保持原值、由存储层加密保护。隐私文档原本写对了,
  是实现搞混了;文档已补一节把这条界限讲清楚。
- **README 的项目状态改为逐项标注**:特性表区分「✅ 已实现」与「🚧 设计完成待实现」,
  明确写出当前能做什么、不能做什么,不做超前宣传。
- **策略参考文档适配 2.0 结构**:解析层重写以读取条件树,支持任意嵌套的
  and / or / not 渲染,新增「延迟求值(delay)策略」一节。
- **引擎的编译目标由 Java 21 改为 17**:作业 jar 要提交给 Flink 执行,而 Flink 1.20 官方
  镜像最高只到 Java 17 —— 用 21 编出来的 class 文件(版本 65)在上面根本加载不了,而且
  报错发生在提交作业时、不是构建时。这是运行环境施加的约束。控制面是独立进程,仍用 21。
- **Kafka 连接器与 Jackson 由 `provided` 改为打进作业 jar**:Flink 发行版只含运行时,
  连接器是单独发布的构件,Jackson 被重定位到 `org.apache.flink.shaded.jackson2.*`。
  用 `provided` 时本地测试(依赖都在 classpath 上)一路绿灯,提交到集群才
  `ClassNotFoundException`。
- **元数据的事实来源收敛到数据库**:控制面把策略写进 PostgreSQL、引擎从本地 `seeds/`
  目录加载,是同一份领域模型的两个事实来源 —— 运营改完策略引擎毫无察觉,而两边的分歧
  不会有任何报错。这正是 1.x 走过的路。现在 `seeds/` 退回它本来的角色:首次导入的种子
  数据。拉取失败即启动失败,**不回落到本地文件** —— 回落会让作业带着一份不知多旧的策略
  跑起来,且没有任何迹象表明它没连上控制面。
- **审计日志记录真实操作者**:此前 `MetadataController` 硬编码 `"admin"`、
  `CheckRiskController` 硬编码 `"system"`。审计的意义就在于能追溯到人。
- **`load_seeds_to_postgres.py` 与 `apply_postgres.py` 改为直连数据库**:此前走
  `docker compose exec psql`,只在宿主机上、且容器叫特定名字时成立,放进初始化容器里就
  找不到 docker 命令。前者同时把手写字符串转义拼 SQL 改为参数化语句 —— 这里的输入是仓库
  内经过校验的种子文件,手写转义恰好安全,但它是一种会被照抄到别处的写法。
- **`check_no_pii.py` 补上 RFC 5737 的 `192.0.2.0/24`**(TEST-NET-1):此前会把文档段地址
  判成真实公网 IP,而脚本自己的提示又在推荐用文档段地址。

### Fixed

- **隐私检查的 User-Agent 版本号误报**:`Chrome/120.0.0.0` 这类版本号在形态上与 IP
  无法区分,导致 `check_no_pii.py` 误报。改为按上下文判别:紧跟斜杠的、或以 `.0.0`
  结尾的四段数字视为版本号。已用负例验证判别未被削弱(植入 `一个真实公网 IP` 仍会被拦截)。
- **迁移指南中关于策略 score 的失实表述**:原文称 1.x 策略的 `score`「全部是 0」,
  实际是 169/170 为 0,`设备请求下单行为单一` 为 1。已按数据修正。
- **凭据扫描其实一直没跑起来**:`.gitleaks.toml` 中一条规则用了否定先行断言 `(?!...)`,
  而 gitleaks 用 go-re2 编译正则、RE2 不支持该语法 —— 结果不是配置被拒绝,而是进程直接
  panic。gitleaks-action 在崩溃后仍会去上传 `results.sarif`,日志末尾变成
  `File results.sarif does not exist`,真正的原因埋在几十行 Go 栈里,整个失败看起来像是
  action 的环境问题。也就是说:门禁在 CI 上是红的,但红的原因看着与凭据无关,而扫描本身
  一次也没执行过。排除项已从正则移到 allowlist,CI 改用 gitleaks CLI。
- **`make secrets-scan` 吞掉失败**:写成 `gitleaks detect ... || echo "未安装,跳过"`,
  发现泄露时会走进 `||` 分支 echo 一句然后返回成功。装没装 gitleaks,这个目标都永远是绿的。
- **`/error` 转发未放行导致状态码被改写**:容器把 403 / 404 / 500 转发到 `/error` 时会再走
  一遍安全链,此时上下文已清空,`denyAll` 把真实状态码统统改写成 401 并附上 Basic 挑战。
  表现是「已认证但越权」返回 401 而不是 403,404 也变成 401。这是靠「MockMvc 测出 403、
  线上 curl 得到 401」的不一致发现的 —— MockMvc 默认不走 ERROR 分派,两边正好各露一半。
- **`bootstrapAdmin` 在测试中执行并打印口令**:授权测试需要导入 `SecurityConfig`,而
  `@WebMvcTest` 切片会执行 `ApplicationRunner` —— 每跑一次测试就走一遍建号流程,把一个随机
  口令打进测试输出。那行日志出现在 CI 日志里,和真的凭据泄露看不出区别。已拆分为
  `BootstrapConfig`。
- **ClickHouse 查询中的别名遮蔽**:`SELECT toString(notice_time) AS notice_time` 会让别名在
  `WHERE` 里覆盖同名原列,时间比较变成「String 与 DateTime64 比大小」直接报错。
- **Flink checkpoint 目录属主**:命名卷首次挂载沿用镜像中该路径的属主,镜像里没有该目录
  时 Docker 新建一个 root 的空目录,而 Flink 以 flink 用户运行 —— 作业能提交、能进
  RUNNING,第一次 checkpoint 时才失败。

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
