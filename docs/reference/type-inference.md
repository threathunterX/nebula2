# 类型推导规则

> **规范性文档。** 变量定义的合法性由本文规则判定,校验发生在**保存时**而非运行时。

## 为什么需要它

一个变量定义是否可计算,取决于三者的组合:

```
窗口类型(period.type) × 操作数类型(object_type) × 算子(function.method)
                              ↓
                    合法?→ 输出类型是什么?
```

这三者构成一个封闭的推导系统。1.x 已经实现了这套推导(`variable_function.py` 的五张算子表加 `value_type.py`),是整套体系中设计最完整的部分,2.0 保留其结构。

但 1.x 的推导只用于**配置层校验**,引擎侧的实现是另一套代码,两者不一致——推导认为合法的组合,引擎可能没实现。2.0 把推导表作为 schema 的一部分,并要求引擎实现与之逐项对应(见 [ADR-0005](../adr/0005-schema-single-source-of-truth.md))。

---

## 一、窗口类型分组

算子的可用性首先由窗口类型决定。

| 分组 | period.type | 说明 |
|---|---|---|
| **无窗口** | `self` | 对当前事件的值直接变形,不累积状态 |
| **简单窗口** | `last_n_seconds`、`ever` | 单层滑动或无界累积 |
| **周期窗口** | `hourly` | 整点对齐的滚动窗口 |
| **复合窗口** | `last_n_hours`、`last_n_days`、`today` | 在周期窗口结果之上做二次聚合 |

复合窗口是**二次聚合**:它不直接消费事件,而是合并多个周期窗口的结果。因此它可用的算子集与前三者不同——例如可以 `merge` 多个小时的 map,但不能对原始事件做 `count`。

---

## 二、算子可用性矩阵

✅ 可用 / ➖ 不可用

| 算子 | 无窗口 | 简单窗口 | 周期窗口 | 复合窗口 |
|---|:---:|:---:|:---:|:---:|
| `count` | ✅ | ✅ | ✅ | ✅ |
| `sum` | ✅ | ✅ | ✅ | ✅ |
| `max` / `min` | ✅ | ✅ | ✅ | ✅ |
| `avg` | ✅ | ✅ | ➖ | ✅ |
| `variance` / `stddev` | ✅ | ✅ | ➖ | ✅ |
| `cv` | ➖ | ✅ | ➖ | ✅ |
| `distinct_count` | ✅ | ✅ | ✅ | ✅ |
| `distinct` | ✅ | ✅ | ✅ | ✅ |
| `first` / `last` | ✅ | ✅ | ✅ | ✅ |
| `lastn` | ➖ | ✅ | ✅ | ✅ |
| `collection` | ➖ | ✅ | ✅ | ✅ |
| `group_count` / `group_sum` | ➖ | ✅ | ✅ | ✅ |
| `top` / `topn` | ✅ | ✅ | ✅ | ✅ |
| `merge` / `merge_value` | ➖ | ✅ | ✅ | ✅ |
| `last_value` | ➖ | ✅ | ➖ | ➖ |
| `global_latest` | ➖ | ✅ | ➖ | ➖ |
| `+` `-` `*` `/` | ✅ | ✅ | ✅ | ✅ |

> `avg`、`variance`、`stddev` 在周期窗口上不可用,是因为它们需要保存中间统计量(计数、和、平方和)才能正确合并,而周期窗口的存储布局按定长设计。需要小时级均值时,用 `sum` 与 `count` 两个变量,再用 `dual` 变量相除。

---

## 三、操作数类型约束

| 操作数类型 | 可用算子 |
|---|---|
| `long` / `double` | `count` `sum` `max` `min` `avg` `variance` `stddev` `cv` `distinct_count` `distinct` `first` `last` `lastn` `collection` `top` `group_count` `group_sum` `global_latest` |
| `string` | `count` `distinct_count` `distinct` `first` `last` `lastn` `collection` `group_count` `global_latest` |
| `bool` | `count` `distinct_count` `first` `last` `global_latest` |
| `map` | `merge` `merge_value` `last_value` `global_latest` |
| `list` | `count` `distinct_count` `collection` |

**类型不匹配时拒绝保存**,并给出明确错误信息(哪个算子、哪个类型、可用的替代)。

---

## 四、输出类型推导

| 算子 | 输入类型 | 输出类型 |
|---|---|---|
| `count`、`distinct_count` | 任意 | `long` |
| `sum`、`max`、`min` | `long` | `long` |
| `sum`、`max`、`min` | `double` | `double` |
| `avg`、`variance`、`stddev`、`cv` | `long` / `double` | `double` |
| `first`、`last`、`global_latest` | T | T |
| `distinct`、`lastn`、`collection` | T | `list⟨T⟩` |
| `group_count` | 任意 | `map⟨string, long⟩` |
| `group_sum` | `long` / `double` | `map⟨string, 同输入⟩` |
| `top`、`topn` | `map⟨string, N⟩` | `mmap⟨string, N⟩` |
| `merge` | `map⟨K, V⟩` | `map⟨K, V⟩` |
| `merge_value` | `map⟨K, 数值⟩` | `map⟨K, 数值⟩` |
| `last_value` | `map⟨K, V⟩` | `V` |

### 二元运算

| 左 | 右 | 运算 | 输出 |
|---|---|---|---|
| `long` | `long` | `+` `-` `*` | `long` |
| `long` | `long` | `/` | **`double`** |
| 任一为 `double` | | 全部 | `double` |

`long / long → double` 是刻意的:风控中的比率(失败率、转化率)几乎都由两个计数相除得到,返回整数会丢失全部精度。

---

## 五、分组键的影响

`groupbykeys` 的个数不改变输出类型,但改变状态形态与查询方式:

| 个数 | 状态形态 | 查询时需提供 |
|---|---|---|
| 0 | 全局单值 | 无 |
| 1 | 一级 key | 1 个键值 |
| 2 | 二级 key | 2 个键值 |

**上限为 2 个分组键。** 更高维度的统计应拆成多个变量——三级以上分组的状态规模会随基数相乘而爆炸,在风控的数据规模下不可行。

---

## 六、维度的自动推导

变量的 `dimension` 字段可以留空,由引擎按以下顺序推导:

1. `type` 为 `event` → 空
2. 有 `source` → 继承来源的维度;多个来源时维度必须一致,否则拒绝
3. 按 `groupbykeys[0]` 映射:`c_ip → ip`、`uid → uid`、`did → did`、`page` / `uri_stem → page`,其余 → `others`
4. 无 `groupbykeys` 且类型不是 `event` / `filter` → `global`

推导结果会在保存时写回,不保持为空——这样查询侧不需要重复推导逻辑。

---

## 七、校验时机与错误处理

| 时机 | 校验内容 |
|---|---|
| **保存变量时** | 窗口 × 类型 × 算子组合合法;输出类型可推导;引用的来源存在;引用不成环 |
| **保存策略时** | 引用的变量存在且已启用;比较运算两侧类型兼容;CEL 表达式编译通过且返回 bool |
| **引擎启动/热更新时** | 再次校验(防止绕过控制面直接改数据库);不合法的变量记录错误并跳过,不影响其他变量 |

**运行时不做类型校验。** 所有类型问题必须在前两个阶段拦截——这是与 1.x 最重要的区别,1.x 的大量类型问题以运行时异常的形式暴露,而且只影响单个算子,难以发现。
