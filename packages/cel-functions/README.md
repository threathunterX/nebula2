# CEL 业务扩展函数

策略条件中的复杂逻辑用 [CEL](https://github.com/google/cel-spec) 表达。本目录定义星云在标准 CEL 之上注册的**业务扩展函数**。

选择 CEL 而非 1.x 自研 SPL 的理由见 [ADR-0003](../../docs/adr/0003-cel-replaces-spl.md)。

## 规范性要求

- 每个函数必须在**所有实现中语义一致**(引擎 Java 端、控制面校验、前端补全提示)
- 函数必须是**纯函数**且**可终止**:不得有副作用,不得依赖外部可变状态,执行代价必须有上限
- 参数与返回值类型固定,类型不符在**策略保存时**即拒绝,不留到运行时

---

## 函数清单

### `inTimeWindow(start, end) -> bool`

判断触发事件的发生时间(事件时间,非处理时间)是否落在每日的某个时间窗内。

| 参数 | 类型 | 说明 |
|---|---|---|
| `start` | string | 起始时刻,格式 `HH:MM`,含 |
| `end` | string | 结束时刻,格式 `HH:MM`,不含 |

**跨零点**:当 `start > end` 时表示跨零点窗口。例如 `inTimeWindow("22:00", "06:00")` 覆盖晚 10 点到次日早 6 点。

**时区**:使用部署配置的时区(`nebula.timezone`,默认 `Asia/Shanghai`),不是 UTC。风控规则里的"深夜"是业务含义,必须按当地时间判断。

```javascript
// 深夜下单
inTimeWindow("00:00", "06:00")
```

> 内置资产中有 2 条策略使用(`用户深夜多次请求下单`、`用户深夜请求下单金额过大`),由 1.x 的 `time` 条款转换而来。

---

### `ipLocation(ip, level) -> string`

查询 IP 的地理归属。

| 参数 | 类型 | 说明 |
|---|---|---|
| `ip` | string | IP 地址,通常传事件字段如 `c_ip` |
| `level` | string | 粒度:`country` / `province` / `city` |

**查询失败或无结果时返回 `"unknown"`**,不抛异常、不返回 null。这样条件判定不会因地理库缺数据而中断——与[算子语义规格](../../docs/reference/operators.md)中 IP 地理条件算子的约定一致。

```javascript
// 归属地为上海
ipLocation(c_ip, "province") == "上海市"

// 归属地在若干省份之一
ipLocation(c_ip, "province") in ["上海市", "北京市"]

// 排除境内
ipLocation(c_ip, "country") != "中国"
```

> 内置资产中有 1 条策略使用(`测试-地域FUNCTION`),由 1.x 的 `getlocation` 条款转换而来。

---

### `checkNotice(keyType, keyValue, strategyName, withinSeconds) -> int`

查询某个主体在过去一段时间内命中某条策略的次数,用于**策略级联**——"先命中了 A,现在又出现 B"这类组合判定。

| 参数 | 类型 | 说明 |
|---|---|---|
| `keyType` | string | 主体类型:`ip` / `uid` / `did` / `page` |
| `keyValue` | string | 主体值,通常传事件字段如 `c_ip` |
| `strategyName` | string | 被查询的策略名 |
| `withinSeconds` | int | 回溯时间窗(秒) |

```javascript
// 该 IP 一小时内曾被判定为爬虫
checkNotice("ip", c_ip, "IP大量访问", 3600) > 0
```

> 这是 1.x SPL 中**唯一真正可用**的业务函数(`$CHECKNOTICE`)。内置资产中没有策略使用它,但保留该能力,因为策略级联是风控运营的常见需求。
>
> **性能注意**:它需要查询告警存储,代价高于纯内存判定。引擎会对单次策略求值中的调用次数设上限,超限时策略保存即被拒绝。

---

## 上下文变量

CEL 表达式中可直接引用的标识符:

| 标识符 | 说明 |
|---|---|
| 事件字段名 | 触发事件的字段,如 `c_ip`、`uid`、`did`、`page`、`method`。字段集由事件模型决定 |
| `timestamp` | 事件时间(毫秒) |

变量值请通过 comparison 条件的 `left.kind = "variable"` 引用,**不要**在 CEL 中访问——变量求值涉及状态读取,放在 CEL 里会绕开引擎的依赖分析与调度。

---

## 新增函数的要求

新增一个扩展函数需要同时提供:

1. 本文档中的定义(签名、语义、边界行为)
2. 引擎实现
3. 控制面的类型校验
4. 单元测试,必须覆盖:正常路径、参数类型不符、查询失败/无结果、边界值
5. 前端补全提示的元数据

缺少任何一项 CI 都会失败。这与算子的要求一致,理由见 [ADR-0005](../../docs/adr/0005-schema-single-source-of-truth.md)。

> 🚧 实现中。当前本目录只有定义,尚无代码。
