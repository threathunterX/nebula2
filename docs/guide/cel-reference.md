# CEL 表达式参考

条件树里的字段比较和计数器覆盖了绝大多数判定。CEL 用来表达它们表达不了的东西:时间
窗口、地理位置。

内置 170 条策略中有 **5 条**用到 CEL。

> **当前实现的是 CEL 的一个子集**,不是完整的
> [Common Expression Language](https://github.com/google/cel-spec)。本文只写已经实现
> 的部分 —— 写了没实现的会让人按它设计策略,然后在运行时撞上
> `未实现的 CEL 函数`。选 CEL 而不是自建 SPL 的理由见
> [ADR-0003](../adr/0003-cel-replaces-spl.md)。

---

## 1. 在策略里怎么写

```json
{"left": {"kind": "cel", "expression": "inTimeWindow(\"00:00\", \"06:00\")"},
 "op": "==", "right": {"kind": "constant", "value": "true"}}
```

表达式里可以直接引用事件字段名(如 `c_ip`),不需要前缀。

---

## 2. 已实现的函数

### `inTimeWindow(start, end)`

判断事件时间是否落在一天中的某个时段。参数为 `"HH:MM"` 格式。

**边界**:`start` 含、`end` **不含**。`start > end` 表示跨零点。

```
inTimeWindow("00:00", "06:00")   // 凌晨,不含 06:00 整
inTimeWindow("22:00", "06:00")   // 跨零点:22:00 到次日 06:00
```

事件没有 `timestamp` 或不是数字时返回 `false`,不报错。

典型用途:深夜下单、非工作时间批量操作。

### `ipLocation(ip, level)`

解析 IP 的地理位置。`level` 取 `province`(默认)或 `city`。

```
ipLocation(c_ip, "province")
```

**查询失败、无结果、IP 为空,一律返回字符串 `"unknown"`,不抛异常。** 这是规格明确
规定的:地理库缺失不应该让整条策略报错,而应该让它按「位置未知」参与比较。否则地理库
一挂,所有含位置判断的策略集体失效且原因难查。

`unknown` 会正常参与 `==` / `!=` 比较,所以 `ipLocation(c_ip, "province") != "浙江"` 在
地理库不可用时会**成立**。写策略时注意这一点。

> 地理库本身需要你自己提供,仓库不含任何 IP 地理数据。

---

## 3. `checkNotice` —— 策略级联

```
checkNotice(keyType, keyValue, strategyName, withinSeconds) -> int
```

该主体在过去 `withinSeconds` 内命中 `strategyName` 的次数。用于「先命中了 A,
现在又出现 B」这类组合判定。

```javascript
// 该 IP 一小时内曾被判定为爬虫
checkNotice("ip", c_ip, "IP大量访问", 3600) > 0
```

`keyType` 取名单的 check_type(`IP` / `USER` / `DeviceID` / `OrderID`),也接受 1.x 的
别名 `ip` / `uid` / `did` / `order_id`。**未知取值抛错**,不静默当成某个默认值。

三条容易踩的语义:

- 时间窗 **[now − withinSeconds×1000, now)**,终点**不含** —— 同一条事件里先求值的
  策略刚产出的告警不算,否则结果会依赖策略的求值顺序。
- **数的是已产出的告警**,被去重压掉的不算。
- 引擎在内存里保留告警历史,保留期取全部策略里最大的那个 `withinSeconds`。

完整定义见 [`packages/cel-functions/`](../../packages/cel-functions/)。

## 4. 尚未实现

调用未实现的函数会抛 `IllegalArgumentException`,消息里指出函数名 —— **不会静默返回
false**。静默返回假会让一条级联策略永远不命中且没人发现。

`checkNotice` 的**单次求值调用次数上限**规格里写了但尚未实现:当前没有任何地方限制
一条策略里调用几次。

CEL 语言本身的算术、宏(`has`、`all`、`exists`)、列表与映射操作目前都**没有**支持。
需要复杂逻辑时用条件树的 and / or / not 嵌套。

---

## 4. 求值语义

- 表达式的结果与 `op`、`right` 组合成一个完整条件,不能单独作为条件
- 布尔结果与常量比较时,常量写成字符串 `"true"` / `"false"`
- 表达式内不做类型强转,类型不匹配按[类型推导规则](../reference/type-inference.md)处理

**性能上限尚未实现** —— 没有求值次数或超时限制。表达式写得过于复杂会直接拖慢作业,
目前靠自觉。

---

## 相关文档

| | |
|---|---|
| [CEL 扩展函数定义](../../packages/cel-functions/) | 规范性语义 |
| [ADR-0003](../adr/0003-cel-replaces-spl.md) | 为什么用 CEL 替代自建 SPL |
| [策略开发指南](strategy.md) | 条件树的三种形式 |
| [算子语义规格](../reference/operators.md) | 计数器算子 |
