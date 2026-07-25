# 核心概念:风控数据模型

星云的数据模型由四层构成,每一层都建立在前一层之上:

```
事件 Event  →  变量 Variable  →  策略 Strategy  →  名单 Notice
业务行为       统计特征          判定规则          风险结论
```

理解这四层,就理解了整个系统。

---

## 一、事件(Event)—— 业务行为的标准化表达

事件是系统的输入单元。无论数据来自旁路镜像流量、Nginx 日志还是业务 SDK 埋点,最终都被还原成统一结构的事件。

### 继承机制

所有事件以 `HTTP_DYNAMIC`(动态资源请求)为根,通过**单继承**派生:

```
HTTP_DYNAMIC (30 个基础字段)
├── ACCOUNT_LOGIN          + uid, result, captcha, login_channel …
├── ACCOUNT_REGISTRATION   + uid, invite_code, register_channel …
├── ORDER_SUBMIT           + order_id, 金额, 收货信息, 商品信息 …
├── TRANSACTION_ESCROW     + transaction_id, escrow_type, 金额 …
├── ACTIVITY_DO            + activity_name, activity_gain_amount …
└── …共 17 个内置事件
```

子事件自动获得父事件的全部字段。存储时只保存增量字段,读取时自动合并——这样新增一个业务事件通常只需声明几个特有字段。

### 30 个基础字段

每个事件都有的字段,分为几组:

| 组 | 字段 |
|---|---|
| 主体标识 | `c_ip` 客户端 IP、`uid` 用户 ID、`did` 设备 ID、`sid` 会话 ID |
| 请求 | `host`、`uri_stem`、`uri_query`、`method`、`referer`、`useragent`、`cookie` |
| 响应 | `status`、`s_type`、`s_bytes`、`request_time` |
| 内容 | `c_body` 请求体、`s_body` 响应体、`c_type`、`c_bytes` |
| 网络 | `c_port`、`s_ip`、`s_port`、`xforward` |
| 派生 | `page` 归一化页面路径、`platform`、`geo_province`、`geo_city`、`referer_hit`、`notices` |

外加运行时注入的 `id`、`pid`、`timestamp`。

> **隐私提示**:`c_body`、`s_body`、`cookie`、`uri_query` 极易携带口令、令牌、身份证号等敏感信息。2.0 要求这些字段在**采集端就地脱敏**,原文不进入下游。详见[隐私设计](../security/privacy.md)。

### 派生事件

有两个特殊事件不来自采集,而是由过滤条件派生:

- `HTTP_CLICK` —— 用户真实点击(POST 请求,或返回 HTML 且响应体大于 1000 字节的 GET)
- `HTTP_INCIDENT` —— 已命中过风控策略的请求(`notices` 非空)

---

## 二、变量(Variable)—— 在事件流上计算统计特征

单条事件说明不了什么问题。"这个 IP 登录失败了"不是风险,"这个 IP 十分钟内登录失败 50 次"才是。变量就是把事件流聚合成有判别力的统计特征。

### 命名规范

```
ip__visit_dynamic_distinct_count_did__1h__slot
└┬┘  └──────────┬──────────────────┘ └┬┘ └─┬┘
 │              │                     │    └─ 模块:计算层
 │              │                     └────── 窗口:1 小时
 │              └──────────────────────────── 语义:动态请求关联的不同设备数
 └─────────────────────────────────────────── 维度:按 IP 统计
```

分段用**双下划线**,复合维度用单下划线连接(如 `did_ip`、`uid_geo_city`)。

### 四层窗口

| 模块 | 窗口 | 用途 | 内置数量 |
|---|---|---|---|
| `realtime` | 5 分钟滑动 | 实时突发行为,如瞬时高频访问 | 97 |
| `slot` | 1 小时滚动 | 小时级画像,如本小时关联设备数 | 98 |
| `profile` | 长期/无界 | 账号长期特征,如最近 10 次登录 IP | 39 |
| `base` | 无窗口 | 事件层与过滤层 | 19 |

### 六种变量类型

| 类型 | 语义 | 例子 |
|---|---|---|
| `event` | 事件解包,计算图的根 | — |
| `filter` | 过滤 + 字段派生,无状态 | 只保留登录失败的事件 |
| `aggregate` | 窗口内按 key 聚合(核心) | 每个 IP 的登录失败次数 |
| `dual` | 两个变量做二元运算 | 失败率 = 失败数 / 总数 |
| `sequence` | 相邻两次事件求差 | 两次点击的时间间隔 |
| `top` | 按值排序取前 N | 访问量最高的 20 个页面 |

变量之间可以互为输入,构成一张**有向无环计算图**。一条事件进入后沿图向下传播,某个节点的过滤条件不满足即剪枝,该分支下游不再计算。

### 长期画像变量

39 个 `profile` 变量是最有价值的资产,它们刻画账号的长期行为基线,用于识别"这次行为和这个账号平时不一样":

```
uid__account_login_ip_last10__profile          账号最近 10 个登录 IP
uid__account_login_geocity_last10__profile     账号最近 10 个登录城市
uid_did__account_login_count_succ__profile     账号-设备登录成功次数分布
uid__registration__account__ip__profile        账号注册时的 IP
uid_useragent__visit_dynamic_count__profile    账号的 UA 分布
```

---

## 三、策略(Strategy)—— 把统计特征变成判定

策略是一棵条件树,加上一个处置动作。

```yaml
name: IP多次登录失败
category: ACCOUNT              # 场景:账号
tags: [撞库]
trigger:
  event: ACCOUNT_LOGIN         # 由登录事件触发
condition:
  op: and
  conditions:
    - left:  { kind: event_field, field: result }
      op:    "=="
      right: { kind: constant, value: "F" }        # 本次登录失败
    - left:  { kind: counter, counter: {
                event: ACCOUNT_LOGIN, window: 600,
                algorithm: count, groupby: [c_ip],
                filter: { object: result, operation: "==", value: "F" } } }
      op:    ">"
      right: { kind: constant, value: 5 }          # 且该 IP 10 分钟内失败超过 5 次
action:
  decision: review             # 判定为待审核
  check_type: IP
  check_value: c_ip            # 把这个 IP 列入名单
  ttl: 300                     # 有效期 5 分钟
```

条件的三种形式:

1. **比较** —— 事件字段、变量引用或内联计数器,与某个值比较
2. **CEL 表达式** —— 复杂逻辑用 [CEL](../guide/cel-reference.md) 表达,在沙箱中求值
3. **逻辑组合** —— `and` / `or` / `not` 任意嵌套

内置 170 条策略模板覆盖:订单 70 条(恶意占库存、批量下单、午夜大额)、账号 60 条(撞库、盗号、批量注册、跳跃访问)、访客 40 条(高频访问、恶意扫描、SQL 注入、XSS)。

策略按 **IP / 设备 / 账号三个维度镜像设计**——同一个风险模式往往有三条策略,分别从三个主体维度检测。

---

## 四、名单(Notice)—— 风险结论

策略命中后产出一条告警,同时把风险主体写入名单:

| 字段 | 说明 |
|---|---|
| `key` | 风险主体的值,如具体的 IP 或账号 |
| `check_type` | 主体类型:IP / USER / DeviceID / OrderID |
| `decision` | `accept` 白名单 / `review` 待审核 / `reject` 阻断 |
| `scene_name` | 风险场景 |
| `strategy_name` | 命中的策略 |
| `risk_score` | 风险分 |
| `expire` | 名单失效时间 |
| `variable_values` | **触发时的变量值快照**(2.0 新增落地,用于解释"为什么判定为风险") |
| `trigger_event` | 触发事件全文 |

业务系统通过 `/checkRisk` 同步查询名单,决定放行、二次验证还是阻断:

```json
{
  "final_decision": "review",
  "final_rule_hit": "IP多次登录失败",
  "final_key_hit": "ip",
  "final_value_hit": "198.51.100.23",
  "expire_time": 1700000000000,
  "rule_hits": [ { "rule_name": "...", "decision": "review", "remark": "..." } ]
}
```

---

## 串起来看

一次撞库攻击在这个模型里的完整流转:

1. 攻击者用代理池发起大量登录请求 → collector 采集为 **`ACCOUNT_LOGIN` 事件**,`result=F`
2. 引擎实时更新**变量** `ip__account_login_count_fail__5m__rt`,该 IP 的值攀升到 50
3. **策略**「IP多次登录失败」的条件满足(本次失败 且 10 分钟内失败 > 5 次)
4. 产出**名单**:该 IP 被标记为 `review`,场景 `ACCOUNT`,标签「撞库」,有效期 5 分钟
5. 业务系统下次收到该 IP 的登录请求时,调用 `/checkRisk` 拿到 `review`,弹出验证码

---

## 延伸阅读

- [算子语义参考](../reference/operators.md) —— 每个聚合算子的精确定义
- [变量全表](../reference/variables.md) —— 253 个内置变量
- [策略开发指南](../guide/strategy.md) —— 如何编写自己的策略
- [隐私设计](../security/privacy.md) —— 敏感字段如何处理
