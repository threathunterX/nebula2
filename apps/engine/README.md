# engine —— 计算引擎

## 状态

| 层 | 状态 |
|---|---|
| **算子层**(聚合算子、HLL、事件元信息) | ✅ 已实现,与参考引擎共享测试向量 |
| **条件算子**(24 个,含类型严格与短路求值) | ✅ 已实现,共享向量 |
| **窗口层**(滑动 / 滚动 / 无界、迟到处理) | ✅ 已实现,共享向量 |
| **变量计算图**(依赖闭包、拓扑排序、六类节点、事件继承) | ✅ 已实现,跨语言快照对照 |
| **规则引擎**(条件树求值、内联计数器、CEL、告警去重与可解释性) | ✅ 已实现,端到端告警对照 |
| **Flink 接入**(ProcessFunction,MiniCluster 中实跑验证) | ✅ 单并行度 |
| Flink 多维度分区(并行化) | 🚧 见下文 |
| CEP 序列模式检测 | 🚧 |

当前可编译、可测试,能加载仓库里真实的 170 条策略与 253 个变量,把事件流跑成风险告警,并已能作为 Flink 作业在 MiniCluster 中实际运行。

**但还不能上生产**:只支持单并行度,没有接 Kafka / ClickHouse,没有 Checkpoint 配置。原因见下文的「并行化」一节。

## Flink 接入

`flink/` 包是 Flink 唯一出现的地方。算子、条件、窗口、变量图、规则五层都不依赖它 —— 因此可以脱离集群单元测试,也可以在离线回放等场景复用。该包只做「把 Flink 的生命周期与数据流接到引擎上」,不重复实现任何判定语义。

`FlinkJobTest` 在**进程内启动真实的 Flink 集群**(MiniCluster),提交作业,让事件真的流过算子,再把产出的告警与参考引擎的固化快照逐条比对。不是 mock,不是接口测试。

### 并行化 —— 这套架构最实质的工程难点

当前实现要求**并行度为 1**。

原因是变量按不同维度分组:`ip__visit_count__5m__rt` 按 IP 分组,`uid__account_login_count_fail__5m__rt` 按账号分组,还有按设备、按页面的。一次 `keyBy` 只能选一个维度 —— 按 IP 分区后,账号维度变量的状态就被拆到了不同并行实例上,每个实例只看到部分数据,结果错误。

正确做法是按维度拆成多条链路再汇聚:

```
events ─┬─ keyBy(c_ip) → IP 维度变量 ─┐
        ├─ keyBy(uid)  → 账号维度变量 ─┼→ 汇聚 → 策略判定 → 告警
        ├─ keyBy(did)  → 设备维度变量 ─┤
        └─ keyBy(page) → 页面维度变量 ─┘
```

难点在汇聚:策略判定需要同一条事件在多个维度上的变量值,而这些值分散在不同的并行实例中。可选方案有 broadcast state、多级 keyBy 后 join、或把策略判定下推到各维度再做投票 —— 各有代价,需要按实际流量特征选型。

在此之前,单并行度实现保证了**语义正确**,可用于验证链路、小流量场景与回归对照。

## 为什么先做算子层,而且不依赖 Flink

算子层是引擎里语义最密集、最容易出错的部分:「空窗口返回什么」「null 怎么处理」「值相等时怎么排序」这类规定,一处猜错就会让风控结果悄悄失真,而且很难在集成测试里发现。

把它做成**不依赖 Flink 的纯计算**,带来三个好处:

1. 单元测试不需要集群,秒级反馈
2. 可以与 [JS 参考引擎](../../packages/reference-engine/)逐算子对照
3. 将来在 Flink 之外复用(比如离线回放工具)时不用拆依赖

Flink 的 `AggregateFunction` / `KeyedProcessFunction` 封装在上层,只做状态管理与调度,不重复实现语义。

## 跨语言语义一致性

这是本模块最重要的机制。

Java 实现与 JS 参考实现读**同一份**测试向量:

```
tests/golden/vectors/
├── operators.json        34 个算子行为向量
├── conditions.json       38 个条件算子向量
├── windows.json           9 个窗口与迟到处置向量
├── murmur3.json           8 个哈希向量
├── graph-scenario.json    变量图对照场景(事件序列 + 探针)
├── graph-expected.json    参考引擎固化的变量值
├── notice-scenario.json   端到端告警对照场景
└── notice-expected.json   参考引擎固化的告警
              ↓                          ↓
    Java: *VectorTest / GraphSnapshotTest    JS: vectors.test.js
```

对照分三个层级,逐级覆盖更完整的链路:

| 层级 | 对照内容 | 守住什么 |
|---|---|---|
| 算子 | operators / conditions / windows / murmur3 | 单个算子的输入输出 |
| 变量图 | graph-scenario / graph-expected | 图传播与剪枝、按 key 分槽、事件继承、dual 与 aggregate 的时间语义 |
| **告警** | notice-scenario / notice-expected | 全部 170 条策略的端到端判定:条件树、内联计数器、去重、可解释性 |

其中 **graph 与 notice 是端到端对照**:两个引擎读同一批事件、跑同一批真实变量资产,逐 key 比对最终值。这比逐算子对照更进一步 —— 它覆盖图的传播与剪枝、按 key 分槽、事件继承链匹配,以及 dual 与 aggregate 不同的时间语义。

两套实现之间的语义漂移因此在结构上不可能发生:任何一方改了算子行为,共享向量立刻会在另一方失败。

**这个机制经过实证,三个层级各做了负例验证:**

| 注入的偏差 | 被抓到的表现 |
|---|---|
| `max` 空窗口从 `null` 改成 `0` | 2 个算子向量失败,指明规格条款 §2.2;JS 侧全绿,定位到 Java 侧 |
| 告警去重窗口从 300 秒改成 1 秒 | 告警数从 13 变成 37 |
| 内联计数器忽略过滤条件 | 告警数从 13 变成 9 |

第一个是实现者最容易按直觉猜错的地方(`sum` 空窗口确实返回 0);后两个则说明端到端对照能抓到算子层面看不出来的问题。

哈希向量单独存在的原因:HLL 的去重计数依赖 MurmurHash3 **逐位一致**,两种语言的实现哪怕差一位,同一批数据的基数估计就会不同,golden 对照也就失去意义。向量覆盖了空串、ASCII、UTF-8 多字节,以及 tail 分支的各种长度。

## 已实现的算子

计数 `count` `group_count` · 数值 `sum` `max` `min` `avg` `variance` `stddev` `cv` `group_sum` · 去重 `distinct_count` · 取值 `first` `last` `lastn` `distinct` `collection` `last_value` `global_latest` · 合并 `merge` `merge_value` · 排序 `top` `topn`

语义定义见[算子语义规格](../../docs/reference/operators.md)。**规格是权威,实现服从规格**;若发现规格有歧义,先修规格再改实现。

有一个测试会强制这条纪律:`everyOperatorIsCovered` —— 新增算子若没有对应的共享向量,构建失败。

## 开发

```bash
cd apps/engine
mvn test          # 含共享向量对照
mvn verify
```

依赖:JDK 21、Maven 3.9+。运行时零依赖(Jackson 与 JUnit 都是 test scope)。

## 与 1.x 的关系

1.x 的计算层是两套语义不一致的引擎:`greyhound`(Esper CEP,1 分钟粒度 5 分钟滑窗,精确去重)与 `bordercollie`(自研,1 小时滚动窗,HLL 近似)。同一个「去重计数」在两边算出不同的数。

2.0 合并为一套,理由与代价见 [ADR-0002](../../docs/adr/0002-flink-as-unified-engine.md)。
