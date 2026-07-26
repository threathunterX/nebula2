# engine —— 计算引擎

## 状态

| 层 | 状态 |
|---|---|
| **算子层**(聚合算子、HLL、事件元信息) | ✅ 已实现,与参考引擎共享测试向量 |
| 窗口层(滑动 / 滚动 / 无界、迟到处理) | 🚧 |
| 变量计算图(DAG 构建与调度) | 🚧 |
| 规则引擎(CEL + CEP) | 🚧 |
| Flink 作业封装 | 🚧 |

当前可编译、可测试,但**还不是一个能跑的引擎** —— 它只有算子层。

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
tests/golden/vectors/operators.json   35 个算子行为向量
tests/golden/vectors/murmur3.json      8 个哈希向量
        ↓                    ↓
  Java: SharedVectorTest   JS: vectors.test.js
```

两套实现之间的语义漂移因此在结构上不可能发生:任何一方改了算子行为,共享向量立刻会在另一方失败。

**这个机制经过实证。** 把 Java 的 `max` 空窗口从 `null` 改成 `0`(这正是实现者最容易按直觉猜错的地方),两个向量立即失败并指明规格条款 §2.2,同时 JS 侧全绿 —— 准确定位了漂移发生在哪一侧。

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
