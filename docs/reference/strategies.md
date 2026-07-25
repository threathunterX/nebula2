# 策略模板参考

<!-- 本文件由 tools/gen_strategy_reference.py 自动生成,请勿手工编辑。 -->

> ⚠️ **本文由 `tools/gen_strategy_reference.py` 从 `seeds/strategies/` 自动生成,请勿手工编辑。**
> 修改策略模板本身请改 `seeds/`,随后重新运行生成器;CI 会用 `--check` 校验本文与 seeds 一致。

本文覆盖从 Nebula 1.x 继承的全部 **170 条内置策略模板**(`seeds/strategies/`,源表 `nebula.strategy_cust`)。
它们是**模板**而不是开箱即用的生产策略:阈值来自 1.x 当年的业务流量,处置动作全部是「待人工审核」,启用前请先读[重要提示](#重要提示启用前必读)。

## 目录

- [概览](#概览)
- [如何读懂一条策略](#如何读懂一条策略)
- [按场景分组的策略全表](#按场景分组的策略全表)
- [三维度镜像设计](#三维度镜像设计)
- [需要配置才能生效的策略](#需要配置才能生效的策略)
- [重要提示(启用前必读)](#重要提示启用前必读)

---

## 概览

### 按场景大类(category)

| category | 策略数 | 占比 | 说明 |
|---|---:|---:|---|
| `ORDER` | 70 | 41% | 订单与交易场景:下单、取消、支付 |
| `ACCOUNT` | 60 | 35% | 账号场景:注册、登录、密码、邀请码 |
| `VISITOR` | 40 | 24% | 访客场景:纯 HTTP 流量特征,不需要解析业务语义 |
| **合计** | **170** | | |

### 按风险标签(tag)

标签是 1.x 的风险分类,一条策略可带多个标签(实际数据中每条至多一个)。

| 标签 | 策略数 | | 标签 | 策略数 |
|---|---:|---|---|---:|
| 单一下单 | 24 | | SQL注入 | 4 |
| 不同下单 | 21 | | 单一访问 | 4 |
| 高频登录 | 17 | | 特殊UA | 4 |
| 高频访问 | 12 | | 邀请注册 | 4 |
| 高频注册 | 11 | | XSS | 3 |
| 高频关联 | 8 | | 下单不支付 | 3 |
| 跳跃访问 | 7 | | 取消订单 | 3 |
| 关联下单 | 6 | | RFI | 2 |
| 关联注册 | 6 | | ngx_lua_waf | 2 |
| 关联登录 | 6 | | 午夜下单 | 2 |
| 恶意扫描 | 6 | | 目录遍历 | 2 |
| 高频下单 | 6 | | 一天关联 | 1 |
| 特殊下单 | 5 | | | |

另有 **1** 条策略没有任何标签。

### 按名单主体类型(checktype)

命中后写入哪一类风险名单,决定了业务侧该拦谁。

| 名单主体 | 含义 | 策略数 | 占比 | 名单有效期分布 |
|---|---|---:|---:|---|
| `IP` | IP | 83 | 49% | 5 分钟×76、1 小时×6、5 小时×1 |
| `DeviceID` | 设备 | 45 | 26% | 5 分钟×39、1 小时×6 |
| `USER` | 账号 | 42 | 25% | 5 分钟×36、1 小时×6 |
| **合计** | | **170** | | |

### 统计窗口分布

内联计数器(`count`)所用的窗口长度,反映 1.x 的口径偏好。

| 窗口 | 计数器数量 |
|---|---:|
| 5 分钟 | 30 |
| 10 分钟 | 17 |
| 30 分钟 | 32 |
| 1 小时 | 68 |

---

## 如何读懂一条策略

后面的表格是归纳结果。要看懂原始模板,先读这一节 —— 以 **IP多次登录失败** (`strategies/IP多次登录失败.json`)为例,把它的 JSON 逐条翻译成人话。

### 1. 头部字段

```json
{
  "app": "nebula",
  "name": "IP多次登录失败",
  "category": "ACCOUNT",
  "tags": [
    "高频登录"
  ],
  "remark": ">5 in 10min F",
  "score": 0,
  "status": "online"
}
```

| 字段 | 本例取值 | 含义 |
|---|---|---|
| `app` | nebula | 应用命名空间,内置资产统一为 `nebula`,不是客户名 |
| `name` | IP多次登录失败 | 策略名,全局唯一,也是名单来源的标识 |
| `category` | ACCOUNT | 场景大类,决定写入哪个名单集合 |
| `tags` | 高频登录 | 风险标签,用于报表聚合 |
| `remark` | >5 in 10min F | 1.x 作者留下的速记备注,通常是「阈值 + 窗口」 |
| `score` | 0 | 风险分权重(1.x 未落地,见文末提示) |
| `status` | online | `online` 生效 / `test` 只观察不产出 / `offline` 停用 |
| `group_id` | 2 | 1.x 的策略分组编号,2.0 未使用 |
| `is_locked` | false | 是否被编辑锁定 |
| `start_effect / end_effect` | 2017-01-10 / 2022-01-02 | 生效起止时间(毫秒时间戳),继承自 1.x 的历史值 |
| `version` | 1542255171782 | 模板版本号 |

### 2. terms:条件与动作的列表

`terms` 是策略的主体,是一个**扁平列表**。1.x 的语义是:

- 所有 term **之间只有 AND 关系**,不支持 OR 和嵌套(2.0 支持嵌套布尔,见[从 1.x 迁移](../migration/from-1x.md));
- 每条 term 形如 `left` `op` `right`,`op` 为空表示这条 term 不是比较、而是一个**动作或修饰**(例如写名单、延时、限定时段);
- `scope` 取 `realtime`(实时窗口)或 `profile`(离线画像);
- `remark` 是作者对该条件的注释。

`left` 的 `type`/`subtype` 决定了这条 term 是什么。全部 170 条策略中出现过的类型:

| type | subtype | 出现次数 | 含义 |
|---|---|---:|---|
| `event` | — | 227 | 取当前事件的某个字段做比较,是最基础的过滤条件。 |
| `func` | `setblacklist` | 170 | 命中后的处置动作:把某个主体(IP / 账号 / 设备)写入风险名单,并附带决策与有效期。 |
| `func` | `count` | 147 | 在策略里就地定义一个窗口计数器(等价于临时变量):指定源事件、分组键、统计对象、窗口长度与过滤条件。 |
| `func` | `getvariable` | 57 | 引用 `seeds/variables/` 中已定义的统计变量,复用其计算结果。 |
| `func` | `sleep` | 3 | 延迟一段时间后再判定,用于「做了 A 却始终没做 B」这类否定式风险。 |
| `func` | `time` | 2 | 限定事件发生的时钟区间,用于「深夜下单」这类与时段相关的风险。 |
| `func` | `getlocation` | 1 | 由 IP 求归属地并与给定地域比较。 |

(`right` 侧共出现 431 次 `constant` 常量,不再单列。)

### 3. 逐条翻译本例的 terms

**term 1 —— 事件条件 event**

```json
{
  "left": {
    "config": {
      "event": [
        "nebula",
        "ACCOUNT_LOGIN"
      ],
      "field": "page"
    },
    "subtype": "",
    "type": "event"
  },
  "op": "!regex",
  "remark": "",
  "right": {
    "config": {
      "value": "^\\s*$"
    },
    "subtype": "",
    "type": "constant"
  },
  "scope": "realtime"
}
```

→ 「账号-登录」事件的伪静态页面加工后地址(page) 非空

**term 2 —— 事件条件 event**

```json
{
  "left": {
    "config": {
      "event": [
        "nebula",
        "ACCOUNT_LOGIN"
      ],
      "field": "result"
    },
    "subtype": "",
    "type": "event"
  },
  "op": "==",
  "remark": "",
  "right": {
    "config": {
      "value": "F"
    },
    "subtype": "",
    "type": "constant"
  },
  "scope": "realtime"
}
```

→ 「账号-登录」事件的登陆结果(result) = `F`

**term 3 —— 内联计数器 count**

```json
{
  "left": {
    "config": {
      "algorithm": "count",
      "condition": [
        {
          "left": "c_ip",
          "op": "=",
          "right": "c_ip"
        },
        {
          "left": "result",
          "op": "==",
          "right": "F"
        },
        {
          "left": "page",
          "op": "!regex",
          "right": "^\\s*$"
        }
      ],
      "groupby": [
        "c_ip"
      ],
      "interval": 600,
      "operand": [
        "c_ip"
      ],
      "sourceevent": [
        "nebula",
        "ACCOUNT_LOGIN"
      ],
      "trigger": {
        "event": [
          "nebula",
          "ACCOUNT_LOGIN"
        ],
        "keys": [
          "c_ip"
        ]
      }
    },
    "subtype": "count",
    "type": "func"
  },
  "op": ">",
  "remark": "",
  "right": {
    "config": {
      "value": "5"
    },
    "subtype": "",
    "type": "constant"
  },
  "scope": "realtime"
}
```

→ 最近 10 分钟内、按 客户端ip(c_ip) 分组的「账号-登录」事件(满足 登陆结果(result) = `F`、伪静态页面加工后地址(page) 非空)累计事件次数 > `5`

**term 4 —— 名单处置 setblacklist**

```json
{
  "left": {
    "config": {
      "checkpoints": "",
      "checktype": "IP",
      "checkvalue": "c_ip",
      "decision": "review",
      "name": "ACCOUNT",
      "remark": "",
      "ttl": 300
    },
    "subtype": "setblacklist",
    "type": "func"
  },
  "op": "",
  "remark": "",
  "right": null,
  "scope": "realtime"
}
```

→ 把本次事件的 c_ip 作为IP写入 ACCOUNT 名单,决策 `review`,有效期 5 分钟

合起来,这条策略的意思是:

> 同一 IP:10 分钟内「账号-登录」事件(登陆结果(result) = `F`)的次数 > `5`

> 命中后:review · IP名单(c_ip) · ACCOUNT · 5 分钟。

几个容易踩的点:

- `page` 是**伪静态化之后的页面标识**,不是原始 URI;`page 不匹配正则 ^\s*$` 这类条件在多数策略里都有,含义是「只统计能解析出页面的请求」,属于噪声过滤;
- 内联计数器的 `condition` 里那条 `c_ip = c_ip` 是把分组键绑定到自身的样板写法,不是真实过滤条件;
- `trigger` 指定**哪个事件触发本次判定**,它可以与 `sourceevent`(被统计的事件)不同 —— 「下单不支付」正是靠这一点实现的:下单事件触发,却去数支付页面的访问量;
- `algorithm` 为 `distinct_count` 时统计的是 `operand` 的**去重个数**,为 `count` 时统计**事件条数**。

---

## 按场景分组的策略全表

「检测什么」由 `remark` 与 `terms` 归纳而来;「命中后处置」的四段依次是 **决策 · 名单主体(取值字段) · 名单集合 · 有效期**。

### 账号 · 登录与撞库(23 条)

识别撞库、暴力破解、盗号后的批量登录。

| 策略名 | 检测什么 | 命中后处置 | 标签 |
|---|---|---|---|
| **IP关联多用户请求登录** | 同一 IP:变量 `ip__account_login_distinct_count_uid__5m__rt`(IP登录不同UID数[5m]) > `5`(原始备注:`>5用户 in 5min`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 关联登录 |
| **IP关联多设备请求登录** | 同一 IP:5 分钟内「账号-登录」事件的设备ID(did)去重数 > `5`(原始备注:`>5设备 in 5min`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 关联登录 |
| **IP多次登录失败** | 同一 IP:10 分钟内「账号-登录」事件(登陆结果(result) = `F`)的次数 > `5`(原始备注:`>5 in 10min F`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 高频登录 |
| **IP多次登录成功** | 同一 IP:10 分钟内「账号-登录」事件(登陆结果(result) = `T`)的次数 > `5`(原始备注:`>5 in 10min T`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 高频登录 |
| **IP多次请求登录** | 同一 IP:变量 `ip__account_login_count__5m__rt`(IP登录请求总数[5m]) > `5`(原始备注:`>5 in 5min`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 高频登录 |
| **IP换密码请求登录单账号** | 同一 IP:变量 `ip__account_login_distinct_count_uid__5m__rt`(IP登录不同UID数[5m]) = `1`;变量 `ip__account_login_distinct_count_password__5m__rt`(IP不同登录密码数[5m]) > `5`(原始备注:`>5 in 5min`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 高频登录 |
| **IP相同密码请求登录不同账号** | 同一 IP:变量 `ip__account_login_distinct_count_uid__5m__rt`(IP登录不同UID数[5m]) = `3`;变量 `ip__account_login_distinct_count_password__5m__rt`(IP不同登录密码数[5m]) = `1`(原始备注:`>3 in 5min`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 高频登录 |
| **IP集中请求登录** | 同一设备:30 分钟内「账号-登录」事件的次数 > `5`;30 分钟内「动态资源请求」事件的伪静态页面加工后地址(page)去重数 ≤ `4`(原始备注:`>5 ，页面<=4 in 30min`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 高频登录 |
| **用户在多个IP请求登录** | 同一账号:10 分钟内「账号-登录」事件的客户端ip(c_ip)去重数 > `2`(原始备注:`>2 ip in 10min`) | `review` · 账号名单(uid) · ACCOUNT · 5 分钟 | 关联登录 |
| **用户在多个设备请求登录** | 同一账号:10 分钟内「账号-登录」事件的设备ID(did)去重数 > `2`(原始备注:`>2 did in 10min`) | `review` · 账号名单(uid) · ACCOUNT · 5 分钟 | 关联登录 |
| **用户多次登录失败** | 同一账号:10 分钟内「账号-登录」事件(登陆结果(result) = `F`)的次数 > `3`(原始备注:`>3 in 10min T`) | `review` · 账号名单(uid) · ACCOUNT · 5 分钟 | 高频登录 |
| **用户多次登录成功** | 同一账号:10 分钟内「账号-登录」事件(登陆结果(result) = `T`)的次数 > `3`(原始备注:`>3 in 10min T`) | `review` · 账号名单(uid) · ACCOUNT · 5 分钟 | 高频登录 |
| **用户多次请求登录** | 同一账号:5 分钟内「账号-登录」事件的次数 > `3`(原始备注:`>3 in 5min`) | `review` · 账号名单(uid) · ACCOUNT · 5 分钟 | 高频登录 |
| **用户换密码登录** | 同一账号:10 分钟内「账号-登录」事件的登陆验证密码(password)去重数 > `2`(原始备注:`>2 密码 in 10min`) | `review` · 账号名单(uid) · ACCOUNT · 5 分钟 | 高频登录 |
| **用户相同密码请求登录** | 同一账号:10 分钟内「账号-登录」事件的次数 > `2`;10 分钟内「账号-登录」事件的登陆验证密码(password)去重数 = `1`(原始备注:`>2, 1密码 in 10min`) | `review` · 账号名单(uid) · ACCOUNT · 5 分钟 | 高频登录 |
| **设备在多个IP请求登录** | 同一设备:5 分钟内「账号-登录」事件的客户端ip(c_ip)去重数 > `5`(原始备注:`>5设备 in 5min`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 关联登录 |
| **设备多次登录失败** | 同一设备:10 分钟内「账号-登录」事件(登陆结果(result) = `F`)的次数 > `5`(原始备注:`>5 in 10min F`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 高频登录 |
| **设备多次登录成功** | 同一设备:10 分钟内「账号-登录」事件(登陆结果(result) = `T`)的次数 > `5`(原始备注:`>5 in 10min T`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 高频登录 |
| **设备多次请求登录** | 同一设备:变量 `did__account_login_count__5m__rt`(DID登录请求总数[5m]) > `5`(原始备注:`>5 in 5min`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 高频登录 |
| **设备多用户请求登录** | 同一设备:5 分钟内「账号-登录」事件的登陆用户名(uid)去重数 > `5`(原始备注:`>5用户 in 5min`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 关联登录 |
| **设备换密码请求登录单账号** | 同一设备:变量 `did__account_login_distinct_count_uid__5m__rt`(DID登录不同UID数[5m]) = `1`;变量 `did__account_login_distinct_count_password__5m__rt`(DID不同登录密码数[5m]) > `5`(原始备注:`>5 in 5min`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 高频登录 |
| **设备相同密码请求登录不同账号** | 同一设备:变量 `did__account_login_distinct_count_uid__5m__rt`(DID登录不同UID数[5m]) > `3`;变量 `did__account_login_distinct_count_password__5m__rt`(DID不同登录密码数[5m]) = `1`(原始备注:`>3 in 5min`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 高频登录 |
| **设备集中请求登录** | 同一 IP:30 分钟内「账号-登录」事件的次数 > `5`;30 分钟内「动态资源请求」事件的伪静态页面加工后地址(page)去重数 ≤ `4`(原始备注:`>5 ，页面<=4 in 30min`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 高频登录 |

### 账号 · 注册与批量开号(21 条)

识别机器注册、养号、邀请返利套利。

| 策略名 | 检测什么 | 命中后处置 | 标签 |
|---|---|---|---|
| **IP使用相同邀请码注册** | 同一 IP:1 小时内「账号-注册」事件的次数 > `3`;1 小时内「账号-注册」事件的注册渠道(register_channel)去重数 = `1`(原始备注:`>3 register_count, 1 register_channel in 1h`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 邀请注册 |
| **IP使用相同邀请码注册5m** | 同一 IP:变量 `ip__account_regist_count__5m__rt`(IP注册请求总数[5m]) > `2`;5 分钟内「账号-注册」事件的注册渠道(register_channel)去重数 = `1`(原始备注:`>2 register_count, same register_channel in 5m`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 邀请注册 |
| **IP多个用户请求注册** | 同一 IP:10 分钟内「账号-注册」事件的注册名(uid)去重数 > `2`(原始备注:`>2 uid in 10m`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 关联注册 |
| **IP多个设备请求注册** | 同一 IP:10 分钟内「账号-注册」事件的设备ID(did)去重数 > `2`(原始备注:`>2 did in 10m`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 关联注册 |
| **IP多次使用相同密码注册** | 同一 IP:变量 `ip__account_regist_distinct_count_password__5m__rt`(IP不同注册密码数[5m]) = `1`;变量 `ip__account_regist_count__5m__rt`(IP注册请求总数[5m]) > `3`(原始备注:`>3, 1密码 in 5m`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 高频注册 |
| **IP多次注册失败** | 同一 IP:5 分钟内「账号-注册」事件(注册结果(result) = `F`)的次数 > `5`(原始备注:`>5 in 5m`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 高频注册 |
| **IP多次注册成功** | 同一 IP:5 分钟内「账号-注册」事件(注册结果(result) = `T`)的次数 > `5`(原始备注:`>5 in 5m`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 高频注册 |
| **IP多次请求注册** | 同一 IP:5 分钟内「账号-注册」事件的次数 > `10`(原始备注:`>10 in 5m`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 高频注册 |
| **IP集中请求注册接口** | 同一 IP:30 分钟内「账号-注册」事件的次数 > `5`;30 分钟内「动态资源请求」事件的伪静态页面加工后地址(page)去重数 ≤ `4`(原始备注:`>5 in 30m, 动态<=4`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 高频注册 |
| **用户在多个IP请求注册** | 同一账号:10 分钟内「账号-注册」事件的客户端ip(c_ip)去重数 > `2`(原始备注:`>2 ip in 10m`) | `review` · 账号名单(uid) · ACCOUNT · 5 分钟 | 关联注册 |
| **用户在多个设备请求注册** | 同一账号:10 分钟内「账号-注册」事件的设备ID(did)去重数 > `2`(原始备注:`>2 ip in 10m`) | `review` · 账号名单(uid) · ACCOUNT · 5 分钟 | 关联注册 |
| **用户多次请求注册** | 同一账号:5 分钟内「账号-注册」事件的次数 > `2`(原始备注:`>2 in 5m`) | `review` · 账号名单(uid) · ACCOUNT · 5 分钟 | 高频注册 |
| **设备使用相同邀请码注册** | 同一设备:1 小时内「账号-注册」事件的次数 > `3`;1 小时内「账号-注册」事件的注册渠道(register_channel)去重数 = `1`(原始备注:`>3 register_count, 1 register_channel in 1h`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 邀请注册 |
| **设备使用相同邀请码注册5m** | 同一设备:变量 `did__account_regist_count__5m__rt`(DID注册请求总数[5m]) > `2`;5 分钟内「账号-注册」事件的注册渠道(register_channel)去重数 = `1`(原始备注:`>2 register_count, same register_channel in 5m`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 邀请注册 |
| **设备在多个IP请求注册** | 同一设备:10 分钟内「账号-注册」事件的客户端ip(c_ip)去重数 > `2`(原始备注:`>2 ip in 10m`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 关联注册 |
| **设备多个用户请求注册** | 同一设备:10 分钟内「账号-注册」事件的注册名(uid)去重数 > `2`(原始备注:`>2 uid in 10m`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 关联注册 |
| **设备多次使用相同密码注册** | 同一设备:变量 `did__account_regist_distinct_count_password__5m__rt`(DID不同注册密码数[5m]) = `1`;变量 `did__account_regist_distinct_count_uid__5m__rt`(DID注册不同UID数[5m]) = `3`(原始备注:`>3, 1密码 in 5m`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 高频注册 |
| **设备多次注册失败** | 同一设备:5 分钟内「账号-注册」事件(注册结果(result) = `F`)的次数 > `10`(原始备注:`>10 in 5m`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 高频注册 |
| **设备多次注册成功** | 同一设备:5 分钟内「账号-注册」事件(注册结果(result) = `T`)的次数 > `10`(原始备注:`>10 in 5m`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 高频注册 |
| **设备多次请求注册** | 同一设备:5 分钟内「账号-注册」事件的次数 > `10`(原始备注:`>10 in 5m`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 高频注册 |
| **设备集中请求注册接口** | 同一设备:30 分钟内「账号-注册」事件的次数 > `5`;30 分钟内「动态资源请求」事件的伪静态页面加工后地址(page)去重数 ≤ `4`(原始备注:`>5 in 30m, 动态<=4`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 高频注册 |

### 账号 · 身份关联异常(9 条)

同一主体在短时间内关联到过多其它主体,是代理池、群控设备、共享账号的典型特征。

| 策略名 | 检测什么 | 命中后处置 | 标签 |
|---|---|---|---|
| **IP关联多个用户** | 同一 IP:变量 `ip__visit_dynamic_distinct_count_uid__5m__rt`(IP关联UID数[5m]) > `5`,前置 客户端ip(c_ip) 包含 `.`(原始备注:`>5 in 5m`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 高频关联 |
| **IP关联多个设备** | 同一 IP:变量 `ip__visit_dynamic_distinct_count_did__5m__rt`(IP关联DID数[5m]) > `5`,前置 客户端ip(c_ip) 包含 `.`(原始备注:`>5 in 5m`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 高频关联 |
| **IP当天关联多个用户** | 同一 IP:变量 `ip__visit_distinct_uid__1h__profile` > `20`,前置 登陆结果(result) = `T`(原始备注:`>20, in 1d`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 一天关联 |
| **用户关联多个IP** | 同一账号:变量 `uid__account_dynamic_distinct_count_ip__5m__rt`(UID关联IP数[5m]) > `3`,前置 客户端ip(c_ip) 包含 `.`(原始备注:`>5 in 5m`) | `review` · 账号名单(uid) · ACCOUNT · 5 分钟 | 高频关联 |
| **用户关联多个IP地域** | 同一账号:变量 `uid__account_dynamic_distinct_count_geo_city__5m__rt`(UID关联不同城市数[5m]) > `2`(原始备注:`>2 in 5m`) | `review` · 账号名单(uid) · ACCOUNT · 5 分钟 | 高频关联 |
| **用户关联多个设备** | 同一账号:变量 `uid__account_dynamic_distinct_count_did__5m__rt`(UID关联DID数[5m]) > `2`,前置 客户端ip(c_ip) 包含 `.`(原始备注:`>2 in 5m`) | `review` · 账号名单(uid) · ACCOUNT · 5 分钟 | 高频关联 |
| **设备关联多个IP** | 同一设备:变量 `did__account_dynamic_distinct_count_ip__5m__rt`(DID关联IP数[5m]) > `5`(原始备注:`>5 in 5m`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 高频关联 |
| **设备关联多个IP地域** | 同一设备:变量 `did__account_dynamic_distinct_count_geo_city__5m__rt`(DID关联不同城市数[5m]) > `2`(原始备注:`>2 in 5m`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 高频关联 |
| **设备关联多个用户** | 同一设备:变量 `did__account_dynamic_distinct_count_uid__5m__rt`(DID关联UID数[5m]) > `2`(原始备注:`>2 in 5m`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 高频关联 |

### 账号 · 访问路径异常(7 条)

正常用户到达登录/注册页前会先加载若干资源;直接打接口说明是脚本。

| 策略名 | 检测什么 | 命中后处置 | 标签 |
|---|---|---|---|
| **IP请求A一段时间内没有请求B** 🔧 | 同一 IP:5 分钟内「动态资源请求」事件(伪静态页面加工后地址(page) = `B`)的次数 = `0`,前置 伪静态页面加工后地址(page) = `A`,另需 等待 5 分钟后再判定后续条件(原始备注:`延迟判断 5m`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 跳跃访问 |
| **IP请求注册前未访问必要资源** 🔧 | 同一 IP:5 分钟内「动态资源请求」事件(伪静态页面加工后地址(page) 包含 `<YOUR_PAYMENT_PAGE_PATH>`)的次数 = `0`(原始备注:`register, no xxx in 5m`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 跳跃访问 |
| **IP请求登录前未访问必要资源** 🔧 | 同一 IP:5 分钟内「动态资源请求」事件(伪静态页面加工后地址(page) 包含 `<YOUR_PAYMENT_PAGE_PATH>`)的次数 = `0`(原始备注:`login, no xxx in 5m`) | `review` · IP名单(c_ip) · ACCOUNT · 5 分钟 | 跳跃访问 |
| **用户请求A一段时间内没有请求B** 🔧 | 同一账号:5 分钟内「动态资源请求」事件(伪静态页面加工后地址(page) = `B`)的次数 = `0`,前置 伪静态页面加工后地址(page) = `A`,另需 等待 5 分钟后再判定后续条件(原始备注:`延迟判断 5m`) | `review` · 账号名单(uid) · ACCOUNT · 5 分钟 | 跳跃访问 |
| **设备请求A一段时间内没有请求B** 🔧 | 同一设备:5 分钟内「动态资源请求」事件(伪静态页面加工后地址(page) = `B`)的次数 = `0`,前置 伪静态页面加工后地址(page) = `A`,另需 等待 5 分钟后再判定后续条件(原始备注:`延迟判断 5m`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 跳跃访问 |
| **设备请求注册前未访问必要资源** 🔧 | 同一设备:5 分钟内「动态资源请求」事件(伪静态页面加工后地址(page) 包含 `<YOUR_PAYMENT_PAGE_PATH>`)的次数 = `0`(原始备注:`register, no xxx in 5m`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 跳跃访问 |
| **设备请求登录前未访问必要资源** 🔧 | 同一设备:5 分钟内「动态资源请求」事件(伪静态页面加工后地址(page) 包含 `<YOUR_PAYMENT_PAGE_PATH>`)的次数 = `0`(原始备注:`login, no xxx in 5m`) | `review` · 设备名单(did) · ACCOUNT · 5 分钟 | 跳跃访问 |

### 订单 · 高频下单(6 条)

单位时间内下单次数异常。

| 策略名 | 检测什么 | 命中后处置 | 标签 |
|---|---|---|---|
| **IP多次请求下单** | 同一 IP:30 分钟内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 30min`) | `review` · IP名单(c_ip) · ORDER · 1 小时 | 高频下单 |
| **IP请求下单行为单一** | 同一 IP:30 分钟内「订单-提交」事件的订单ID(order_id)去重数 > `3`;30 分钟内「动态资源请求」事件的伪静态页面加工后地址(page)去重数 ≤ `4`(原始备注:`>3, <=4页面  in 30min`) | `review` · IP名单(c_ip) · ORDER · 1 小时 | 高频下单 |
| **用户多次请求下单** | 同一账号:30 分钟内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 30min`) | `review` · 账号名单(uid) · ORDER · 1 小时 | 高频下单 |
| **用户请求下单行为单一** | 同一账号:30 分钟内「订单-提交」事件的订单ID(order_id)去重数 > `3`;30 分钟内「动态资源请求」事件的伪静态页面加工后地址(page)去重数 ≤ `4`(原始备注:`>3, <=4页面  in 30min`) | `review` · 账号名单(uid) · ORDER · 1 小时 | 高频下单 |
| **设备多次请求下单** | 同一设备:30 分钟内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 30min`) | `review` · 设备名单(did) · ORDER · 1 小时 | 高频下单 |
| **设备请求下单行为单一** | 同一设备:30 分钟内「订单-提交」事件的订单ID(order_id)去重数 > `3`;30 分钟内「动态资源请求」事件的伪静态页面加工后地址(page)去重数 ≤ `4`(原始备注:`>3, <=4页面  in 30min`) | `review` · 设备名单(did) · ORDER · 1 小时 | 高频下单 |

### 订单 · 下单要素高度集中(24 条)

多笔订单的商品/商户/收货信息高度雷同,典型刷单、薅券。

| 策略名 | 检测什么 | 命中后处置 | 标签 |
|---|---|---|---|
| **IP多次请求下单__同一商品** | 同一 IP:1 小时内「订单-提交」事件的敏感或主要购买产品ID(product_id)去重数 = `1`;1 小时内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 1h, product_id`) | `review` · IP名单(c_ip) · ORDER · 5 分钟 | 单一下单 |
| **IP多次请求下单__同一商户** | 同一 IP:1 小时内「订单-提交」事件的商户号(merchant)去重数 = `1`;1 小时内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 1h, merchant`) | `review` · IP名单(c_ip) · ORDER · 5 分钟 | 单一下单 |
| **IP多次请求下单__同一地址** | 同一 IP:1 小时内「订单-提交」事件的收货人收货地具体地址(receiver_address_detail)去重数 = `1`;1 小时内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 1h, receiver_address_detail`) | `review` · IP名单(c_ip) · ORDER · 5 分钟 | 单一下单 |
| **IP多次请求下单__同一城市** | 同一 IP:1 小时内「订单-提交」事件的收货人收货地城市(receiver_address_city)去重数 = `1`;1 小时内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 1h, receiver_address_city`) | `review` · IP名单(c_ip) · ORDER · 5 分钟 | 单一下单 |
| **IP多次请求下单__同手机号** | 同一 IP:1 小时内「订单-提交」事件的收货人手机(receiver_mobile)去重数 = `1`;1 小时内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 1h, receiver_mobile`) | `review` · IP名单(c_ip) · ORDER · 5 分钟 | 单一下单 |
| **IP多次请求下单__同收货人** | 同一 IP:1 小时内「订单-提交」事件的收货人姓名(user_name)去重数 = `1`;1 小时内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 1h, user_name`) | `review` · IP名单(c_ip) · ORDER · 5 分钟 | 单一下单 |
| **IP多次请求下单__金额较低** | 同一 IP:1 小时内「订单-提交」事件(订单现金金额(order_money_amount) ≤ `100`)的次数 > `5`(原始备注:`>5 in 1h, order_money_amount<= 100`) | `review` · IP名单(c_ip) · ORDER · 5 分钟 | 单一下单 |
| **IP多次请求下单__金额较高** | 同一 IP:1 小时内「订单-提交」事件(订单现金金额(order_money_amount) > `100`)的次数 > `5`(原始备注:`>5 in 1h, order_money_amount > 100`) | `review` · IP名单(c_ip) · ORDER · 5 分钟 | 单一下单 |
| **用户多次请求下单__同一商品** | 同一账号:1 小时内「订单-提交」事件的敏感或主要购买产品ID(product_id)去重数 = `1`;1 小时内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 1h, product_id`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 单一下单 |
| **用户多次请求下单__同一商户** | 同一账号:1 小时内「订单-提交」事件的商户号(merchant)去重数 = `1`;1 小时内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 1h, merchant`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 单一下单 |
| **用户多次请求下单__同一地址** | 同一账号:1 小时内「订单-提交」事件的收货人收货地具体地址(receiver_address_detail)去重数 = `1`;1 小时内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 1h, receiver_address_detail`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 单一下单 |
| **用户多次请求下单__同一城市** | 同一账号:1 小时内「订单-提交」事件的收货人收货地城市(receiver_address_city)去重数 = `1`;1 小时内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 1h, receiver_address_city`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 单一下单 |
| **用户多次请求下单__同手机号** | 同一账号:1 小时内「订单-提交」事件的收货人手机(receiver_mobile)去重数 = `1`;1 小时内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 1h, receiver_mobile`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 单一下单 |
| **用户多次请求下单__同收货人** | 同一账号:1 小时内「订单-提交」事件的收货人姓名(user_name)去重数 = `1`;1 小时内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 1h, user_name`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 单一下单 |
| **用户多次请求下单__金额较低** | 同一账号:1 小时内「订单-提交」事件(订单现金金额(order_money_amount) ≤ `100`)的次数 > `5`(原始备注:`>5 in 1h, order_money_amount<= 100`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 单一下单 |
| **用户多次请求下单__金额较高** | 同一账号:1 小时内「订单-提交」事件(订单现金金额(order_money_amount) > `100`)的次数 > `5`(原始备注:`>5 in 1h, order_money_amount > 100`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 单一下单 |
| **设备多次请求下单__同一商品** | 同一设备:1 小时内「订单-提交」事件的敏感或主要购买产品ID(product_id)去重数 = `1`;1 小时内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 1h, product_id`) | `review` · 设备名单(did) · ORDER · 5 分钟 | 单一下单 |
| **设备多次请求下单__同一商户** | 同一设备:1 小时内「订单-提交」事件的商户号(merchant)去重数 = `1`;1 小时内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 1h, merchant`) | `review` · 设备名单(did) · ORDER · 5 分钟 | 单一下单 |
| **设备多次请求下单__同一地址** | 同一设备:1 小时内「订单-提交」事件的收货人姓名(user_name)去重数 = `1`;1 小时内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 1h, user_name`) | `review` · 设备名单(did) · ORDER · 5 分钟 | 单一下单 |
| **设备多次请求下单__同一城市** | 同一设备:1 小时内「订单-提交」事件的收货人收货地城市(receiver_address_city)去重数 = `1`;1 小时内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 1h, receiver_address_city`) | `review` · 设备名单(did) · ORDER · 5 分钟 | 单一下单 |
| **设备多次请求下单__同手机号** | 同一设备:1 小时内「订单-提交」事件的收货人手机(receiver_mobile)去重数 = `1`;1 小时内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 1h, receiver_mobile`) | `review` · 设备名单(did) · ORDER · 5 分钟 | 单一下单 |
| **设备多次请求下单__同收货人** | 同一设备:1 小时内「订单-提交」事件的收货人姓名(user_name)去重数 = `1`;1 小时内「订单-提交」事件的次数 > `5`(原始备注:`>5 in 1h, user_name`) | `review` · 设备名单(did) · ORDER · 5 分钟 | 单一下单 |
| **设备多次请求下单__金额较低** | 同一设备:1 小时内「订单-提交」事件(订单现金金额(order_money_amount) ≤ `100`)的次数 > `5`(原始备注:`>5 in 1h, order_money_amount<= 100`) | `review` · 设备名单(did) · ORDER · 5 分钟 | 单一下单 |
| **设备多次请求下单__金额较高** | 同一设备:1 小时内「订单-提交」事件(订单现金金额(order_money_amount) > `100`)的次数 > `5`(原始备注:`>5 in 1h, order_money_amount > 100`) | `review` · 设备名单(did) · ORDER · 5 分钟 | 单一下单 |

### 订单 · 下单要素异常分散(21 条)

同一主体的多笔订单要素完全不重合,典型盗卡试单、代下单。

| 策略名 | 检测什么 | 命中后处置 | 标签 |
|---|---|---|---|
| **IP多次请求下单__不同商品** | 同一 IP:1 小时内「订单-提交」事件的敏感或主要购买产品ID(product_id)去重数 > `2`(原始备注:`>2 in 1h, product_id`) | `review` · IP名单(c_ip) · ORDER · 5 分钟 | 不同下单 |
| **IP多次请求下单__不同商户** | 同一 IP:1 小时内「订单-提交」事件的商户号(merchant)去重数 > `2`(原始备注:`>2 in 1h, merchant`) | `review` · IP名单(c_ip) · ORDER · 5 分钟 | 不同下单 |
| **IP多次请求下单__不同地址** | 同一 IP:1 小时内「订单-提交」事件的收货人收货地具体地址(receiver_address_detail)去重数 > `2`(原始备注:`>2 in 1h, receiver_address_detail`) | `review` · IP名单(c_ip) · ORDER · 5 分钟 | 不同下单 |
| **IP多次请求下单__不同城市** | 同一 IP:1 小时内「订单-提交」事件的收货人收货地城市(receiver_address_city)去重数 > `2`(原始备注:`>2 in 1h, receiver_address_city`) | `review` · IP名单(c_ip) · ORDER · 5 分钟 | 不同下单 |
| **IP多次请求下单__不同手机号** | 同一 IP:1 小时内「订单-提交」事件的收货人手机(receiver_mobile)去重数 > `2`(原始备注:`>2 in 1h, receiver_mobile`) | `review` · IP名单(c_ip) · ORDER · 5 分钟 | 不同下单 |
| **IP多次请求下单__不同收货人** | 同一 IP:1 小时内「订单-提交」事件的收货人姓名(user_name)去重数 > `2`(原始备注:`>2 in 1h, user_name`) | `review` · IP名单(c_ip) · ORDER · 5 分钟 | 不同下单 |
| **IP多次请求下单__不同金额** | 同一 IP:1 小时内「订单-提交」事件的订单现金金额(order_money_amount)去重数 > `2`(原始备注:`>2 in 1h, order_money_amount`) | `review` · 设备名单(did) · ORDER · 5 分钟 | 不同下单 |
| **用户多次请求下单__不同商品** | 同一账号:1 小时内「订单-提交」事件的敏感或主要购买产品ID(product_id)去重数 > `2`(原始备注:`>2 in 1h, product_id`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 不同下单 |
| **用户多次请求下单__不同商户** | 同一账号:1 小时内「订单-提交」事件的商户号(merchant)去重数 > `2`(原始备注:`>2 in 1h, merchant`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 不同下单 |
| **用户多次请求下单__不同地址** | 同一账号:1 小时内「订单-提交」事件的收货人收货地具体地址(receiver_address_detail)去重数 > `2`(原始备注:`>2 in 1h, receiver_address_detail`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 不同下单 |
| **用户多次请求下单__不同城市** | 同一账号:1 小时内「订单-提交」事件的收货人收货地城市(receiver_address_city)去重数 > `2`(原始备注:`>2 in 1h, receiver_address_city`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 不同下单 |
| **用户多次请求下单__不同手机号** | 同一账号:1 小时内「订单-提交」事件的收货人手机(receiver_mobile)去重数 > `2`(原始备注:`>2 in 1h, receiver_mobile`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 不同下单 |
| **用户多次请求下单__不同收货人** | 同一账号:1 小时内「订单-提交」事件的收货人姓名(user_name)去重数 > `2`(原始备注:`>2 in 1h, user_name`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 不同下单 |
| **用户多次请求下单__不同金额** | 同一账号:1 小时内「订单-提交」事件的订单现金金额(order_money_amount)去重数 > `2`(原始备注:`>2 in 1h, order_money_amount`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 不同下单 |
| **设备多次请求下单__不同商品** | 同一设备:1 小时内「订单-提交」事件的敏感或主要购买产品ID(product_id)去重数 > `2`(原始备注:`>2 in 1h, product_id`) | `review` · 设备名单(did) · ORDER · 5 分钟 | 不同下单 |
| **设备多次请求下单__不同商户** | 同一设备:1 小时内「订单-提交」事件的商户号(merchant)去重数 > `2`(原始备注:`>2 in 1h, merchant`) | `review` · 设备名单(did) · ORDER · 5 分钟 | 不同下单 |
| **设备多次请求下单__不同地址** | 同一设备:1 小时内「订单-提交」事件的收货人收货地具体地址(receiver_address_detail)去重数 > `2`(原始备注:`>2 in 1h, receiver_address_detail`) | `review` · 设备名单(did) · ORDER · 5 分钟 | 不同下单 |
| **设备多次请求下单__不同城市** | 同一设备:1 小时内「订单-提交」事件的收货人收货地城市(receiver_address_city)去重数 > `2`(原始备注:`>2 in 1h, receiver_address_city`) | `review` · 设备名单(did) · ORDER · 5 分钟 | 不同下单 |
| **设备多次请求下单__不同手机号** | 同一设备:1 小时内「订单-提交」事件的收货人手机(receiver_mobile)去重数 > `2`(原始备注:`>2 in 1h, receiver_mobile`) | `review` · 设备名单(did) · ORDER · 5 分钟 | 不同下单 |
| **设备多次请求下单__不同收货人** | 同一设备:1 小时内「订单-提交」事件的收货人姓名(user_name)去重数 > `2`(原始备注:`>2 in 1h, user_name`) | `review` · 设备名单(did) · ORDER · 5 分钟 | 不同下单 |
| **设备多次请求下单__不同金额** | 同一设备:1 小时内「订单-提交」事件的订单现金金额(order_money_amount)去重数 > `2`(原始备注:`>2 in 1h, order_money_amount`) | `review` · 设备名单(did) · ORDER · 5 分钟 | 不同下单 |

### 订单 · 跨主体关联下单(6 条)

一个 IP/设备下挂多个账号下单,或一个账号跨多 IP/设备下单。

| 策略名 | 检测什么 | 命中后处置 | 标签 |
|---|---|---|---|
| **IP多用户请求下单** | 同一 IP:30 分钟内「订单-提交」事件(伪静态页面加工后地址(page) 包含 `^\s*$`)的下单账号(uid)去重数 > `3`(原始备注:`>3 用户 in 30min`) | `review` · IP名单(c_ip) · ORDER · 1 小时 | 关联下单 |
| **IP多设备请求下单** | 同一 IP:30 分钟内「订单-提交」事件(伪静态页面加工后地址(page) 包含 `^\s*$`)的设备ID(did)去重数 > `3`(原始备注:`>3 设备 in 30min`) | `review` · IP名单(c_ip) · ORDER · 1 小时 | 关联下单 |
| **用户多IP请求下单** | 同一账号:30 分钟内「订单-提交」事件(伪静态页面加工后地址(page) 包含 `^\s*$`)的客户端ip(c_ip)去重数 > `1`(原始备注:`>1 ip in 30min`) | `review` · 账号名单(uid) · ORDER · 1 小时 | 关联下单 |
| **用户多设备请求下单** | 同一账号:30 分钟内「订单-提交」事件(伪静态页面加工后地址(page) 包含 `^\s*$`)的设备ID(did)去重数 > `1`(原始备注:`>1 ip in 30min`) | `review` · 账号名单(uid) · ORDER · 1 小时 | 关联下单 |
| **设备多IP请求下单** | 同一设备:30 分钟内「订单-提交」事件(伪静态页面加工后地址(page) 包含 `^\s*$`)的客户端ip(c_ip)去重数 > `1`(原始备注:`>1 ip in 30min`) | `review` · 设备名单(did) · ORDER · 1 小时 | 关联下单 |
| **设备多用户请求下单** | 同一设备:30 分钟内「订单-提交」事件(伪静态页面加工后地址(page) 包含 `^\s*$`)的下单账号(uid)去重数 > `1`(原始备注:`>1 用户 in 30min`) | `review` · 设备名单(did) · ORDER · 1 小时 | 关联下单 |

### 订单 · 下单不支付与取消(6 条)

占库存、试探风控、恶意锁定优惠。

| 策略名 | 检测什么 | 命中后处置 | 标签 |
|---|---|---|---|
| **IP下单不支付** 🔧 | 同一 IP:30 分钟内「订单-提交」事件的订单ID(order_id)去重数 > `4`;30 分钟内「动态资源请求」事件(伪静态页面加工后地址(page) 包含 `<YOUR_PAYMENT_PAGE_PATH>`)的次数 = `0`(原始备注:`>4 in 30min no pay`) | `review` · IP名单(c_ip) · ORDER · 1 小时 | 下单不支付 |
| **IP多次取消订单** | 同一 IP:30 分钟内「订单-取消」事件的订单ID(order_id)去重数 > `3`(原始备注:`>3 in 30min distinct orderid`) | `review` · IP名单(c_ip) · ORDER · 1 小时 | 取消订单 |
| **用户下单不支付** 🔧 | 同一账号:30 分钟内「订单-提交」事件的订单ID(order_id)去重数 > `4`;30 分钟内「动态资源请求」事件(伪静态页面加工后地址(page) 包含 `<YOUR_PAYMENT_PAGE_PATH>`)的次数 = `0`(原始备注:`>4 in 30min no pay`) | `review` · 账号名单(uid) · ORDER · 1 小时 | 下单不支付 |
| **用户多次取消订单** | 同一账号:30 分钟内「订单-取消」事件的订单ID(order_id)去重数 > `3`(原始备注:`>3 in 30min distinct orderid`) | `review` · 账号名单(uid) · ORDER · 1 小时 | 取消订单 |
| **设备下单不支付** 🔧 | 同一设备:30 分钟内「订单-提交」事件的订单ID(order_id)去重数 > `4`;30 分钟内「动态资源请求」事件(伪静态页面加工后地址(page) 包含 `<YOUR_PAYMENT_PAGE_PATH>`)的次数 = `0`(原始备注:`>4 in 30min no pay`) | `review` · 设备名单(did) · ORDER · 1 小时 | 下单不支付 |
| **设备多次取消订单** | 同一设备:30 分钟内「订单-取消」事件的订单ID(order_id)去重数 > `3`(原始备注:`>3 in 30min distinct orderid`) | `review` · 设备名单(did) · ORDER · 1 小时 | 取消订单 |

### 订单 · 深夜与特殊下单(7 条)

时段异常与金额异常。

| 策略名 | 检测什么 | 命中后处置 | 标签 |
|---|---|---|---|
| **用户深夜多次请求下单** | 同一账号:1 小时内「订单-提交」事件的次数 > `5`,另需 事件发生时刻在 01:00–06:06 之间(原始备注:`>5 in 1h, 0~6`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 午夜下单 |
| **用户深夜请求下单金额过大** | 同一账号:单条事件即命中 订单现金金额(order_money_amount) ≥ `2000`,另需 事件发生时刻在 00:00–06:00 之间(原始备注:`>2,000, 0~6`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 午夜下单 |
| **用户请求下单__金额较大5m** | 同一账号:变量 `uid__order_submit_avg_order_money_amount__5m__rt`(UID平均下单成功金额[5m]) > `1000`,前置 下单账号(uid) 不匹配正则 `^\s*$ `(原始备注:`>1000 in 5m`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 特殊下单 |
| **用户高频请求下单__不同城市5m** | 同一账号:变量 `uid__order_submit_count__5m__rt`(UID下单数[5m]) > `3`;变量 `uid__order_distinct_count_receiver_geo_city__5m__rt`(UID下单不同收货城市数[5m]) > `1`(原始备注:`>3 order, >1 receiver_geo`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 特殊下单 |
| **用户高频请求下单__全部失败5m** | 同一账号:变量 `uid__order_submit_fail_ratio__5m__rt`(UID下单失败比例[5m]) = `1`;变量 `uid__order_submit_count__5m__rt`(UID下单数[5m]) > `3`(原始备注:`>3 order, F=1 in 5m`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 特殊下单 |
| **用户高频请求下单__同一商户5m** | 同一账号:变量 `uid__order_submit_distinct_count_merchant__5m__rt`(UID下单不同商户数[5m]) = `1`;变量 `uid__order_submit_count__5m__rt`(UID下单数[5m]) > `2`,前置 下单账号(uid) 不匹配正则 `^\s*$ `(原始备注:`>2 in 5m, merchant`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 特殊下单 |
| **用户高频请求取消订单5m** | 同一账号:变量 `uid__order_cancel_count__5m__rt`(UID取消订单请求数[5m]) > `3`(原始备注:`>3 cancel in 5m`) | `review` · 账号名单(uid) · ORDER · 5 分钟 | 特殊下单 |

### 访客 · 高频与单一访问(16 条)

无需解析业务语义,仅凭 HTTP 流量特征识别爬虫与压测式访问。

| 策略名 | 检测什么 | 命中后处置 | 标签 |
|---|---|---|---|
| **IP大量动态请求** | 同一 IP:变量 `ip__visit_dynamic_count__5m__rt`(IP动态资源请求量[5m]) > `100`,前置 客户端ip(c_ip) 包含 `.`(原始备注:`>100 in 5m`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 高频访问 |
| **IP大量访问** | 同一 IP:变量 `ip__visit_count__5m__rt`(IP请求量[5m]) > `300`,前置 客户端ip(c_ip) 包含 `.`(原始备注:`>300 in 5m 静态+动态`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 高频访问 |
| **IP大量请求不加载静态资源** | 同一 IP:变量 `ip__visit_count__5m__rt`(IP请求量[5m]) > `50`;变量 `ip__visit_static_count__5m__rt`(IP静态资源请求量[5m]) = `0`,前置 客户端ip(c_ip) 包含 `.`(原始备注:`>50 动态+静态, 0静态 in 5m`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 高频访问 |
| **IP大量请求不带referrer** | 同一 IP:变量 `ip__visit_clicks_count_refererhit__5m__rt`(IP引用页面被请求数[5m]) = `0`;变量 `ip__visit_dynamic_count__5m__rt`(IP动态资源请求量[5m]) > `20`,前置 客户端ip(c_ip) 包含 `.`(原始备注:`>20, 0 referrer in 5m`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 单一访问 |
| **IP大量请求单个接口** | 同一 IP:变量 `ip__visit_dynamic_count__5m__rt`(IP动态资源请求量[5m]) > `10`;变量 `ip__visit_dynamic_distinct_count_page__5m__rt`(IP动态请求不同页面数[5m]) = `1`,前置 客户端ip(c_ip) 包含 `.`(原始备注:`>10 , 页面=1`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 单一访问 |
| **IP大量请求注册接口** | 同一 IP:5 分钟内「动态资源请求」事件(伪静态页面加工后地址(page) 包含 `register`)的次数 > `5`(原始备注:`>5 in 5m 不需要解析 register`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 高频访问 |
| **IP大量请求登录接口** | 同一 IP:5 分钟内「动态资源请求」事件(伪静态页面加工后地址(page) 包含 `login`)的次数 > `5`(原始备注:`>5 in 5m 不需要解析 login`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 高频访问 |
| **IP大量请求相似接口** | 同一 IP:变量 `ip__visit_dynamic_count__5m__rt`(IP动态资源请求量[5m]) > `20`;变量 `ip__visit_dynamic_distinct_count_page__5m__rt`(IP动态请求不同页面数[5m]) < `4`;变量 `ip__visit_dynamic_cv_cbytes__5m__rt`(IP动态请求大小变异系数[5m]) ≤ `0.1`,前置 请求内容大小(c_bytes) > `0`(原始备注:`>20 ,<4 页面  cbytes_cv < 0.1 in 5m`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 单一访问 |
| **IP大量请求签到接口** | 同一 IP:5 分钟内「动态资源请求」事件(伪静态页面加工后地址(page) 包含 `sign`)的次数 > `5`(原始备注:`>5 in 5m 不需要解析 sign`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 高频访问 |
| **IP大量连续GET请求** | 同一 IP:变量 `ip__visit_dynamic_count__5m__rt`(IP动态资源请求量[5m]) > `30`;变量 `ip__visit_dynamic_get_ratio__5m__rt`(IP动态请求GET占比[5m]) = `1`,前置 请求方法(method) = `GET`(原始备注:`>30 in 5m`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 高频访问 |
| **IP大量连续POST请求** | 同一 IP:变量 `ip__visit_dynamic_count__5m__rt`(IP动态资源请求量[5m]) > `30`;变量 `ip__visit_dynamic_post_ratio__5m__rt`(IP动态请求POST占比[5m]) = `1`,前置 请求方法(method) = `POST`(原始备注:`>30 in 5m`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 高频访问 |
| **IP大量连续其他类型请求** | 同一 IP:5 分钟内「动态资源请求」事件(请求方法(method) 不属于 `GET,POST`)的次数 > `20`(原始备注:`>20 in 5m 非GET非POST`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 高频访问 |
| **IP相同UA大量请求单个页面** | 同一 IP:变量 `ip__visit_dynamic_count__5m__rt`(IP动态资源请求量[5m]) > `50`;5 分钟内「动态资源请求」事件的用户代理信息(useragent)去重数 = `1`;5 分钟内「动态资源请求」事件的请求路径(uri_stem)去重数 = `1`,前置 客户端ip(c_ip) 包含 `.`(原始备注:`>50, 1 页面, 1UA`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 高频访问 |
| **IP集中请求部分接口** | 同一 IP:变量 `ip__visit_dynamic_distinct_count_page__5m__rt`(IP动态请求不同页面数[5m]) < `5`;变量 `ip__visit_dynamic_count__5m__rt`(IP动态资源请求量[5m]) > `20`,前置 客户端ip(c_ip) 包含 `.`(原始备注:`>20 , < 5页面 in 5m`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 单一访问 |
| **IP页面停留时间过短App** | 同一 IP:变量 `ip__visit_dynamic_count__5m__rt`(IP动态资源请求量[5m]) > `100`;变量 `ip__visit_clicks_avg_timediff__5m__rt`(IP页面点击间隔平均值[5m]) < `500`,前置 客户端ip(c_ip) 包含 `.`;用户代理信息(useragent) 匹配正则 `.*(iphone\|ipod\|android\|ios\|phone\|ipad).*`(原始备注:`>100, avg < 0.5, in 5m, web`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 高频访问 |
| **IP页面停留时间过短Web** | 同一 IP:变量 `ip__visit_dynamic_count__5m__rt`(IP动态资源请求量[5m]) > `100`;变量 `ip__visit_clicks_avg_timediff__5m__rt`(IP页面点击间隔平均值[5m]) < `800`,前置 客户端ip(c_ip) 包含 `.`;用户代理信息(useragent) 不匹配正则 `.*(iphone\|ipod\|android\|ios\|phone\|ipad).*`(原始备注:`>100, avg < 0.8, in 5m, web`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 高频访问 |

### 访客 · 爬虫与异常 UA(4 条)

直接按 User-Agent 特征识别工具类客户端。

| 策略名 | 检测什么 | 命中后处置 | 标签 |
|---|---|---|---|
| **Java 用户代理** | 同一 IP:单条事件即命中 用户代理信息(useragent) 包含 `java`(原始备注:`java`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 特殊UA |
| **Python 用户代理** | 同一 IP:单条事件即命中 用户代理信息(useragent) 包含 `python`(原始备注:`python`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 特殊UA |
| **Spider 用户代理** | 同一 IP:单条事件即命中 用户代理信息(useragent) 包含 `spider`(原始备注:`spider`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 特殊UA |
| **服务器用户代理** | 同一 IP:单条事件即命中 用户代理信息(useragent) 匹配正则 `.*(feeddemon\|indy library\|alexa toolbar\|asktbfx…`(原始备注:`apachebench\|pycurl, 不包括java\|python`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 特殊UA |

### 访客 · 恶意扫描(6 条)

目录扫描、后台探测、大量错误响应。

| 策略名 | 检测什么 | 命中后处置 | 标签 |
|---|---|---|---|
| **IP响应字节过大** | 同一 IP:单条事件即命中 响应内容大小(s_bytes) > `5242880`(原始备注:`> 5M`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 恶意扫描 |
| **IP大量404返回** | 同一 IP:5 分钟内「动态资源请求」事件(响应状态码(status) = `404`)的次数 > `10`(原始备注:`>10 in 5m`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 恶意扫描 |
| **IP大量4XX返回** | 同一 IP:5 分钟内「动态资源请求」事件(响应状态码(status) > `399`、响应状态码(status) < `500`、响应状态码(status) ≠ `404`)的次数 > `10`(原始备注:`>10 in 5m, 不包含404`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 恶意扫描 |
| **IP扫描管理后台** | 同一 IP:5 分钟内「动态资源请求」事件(请求路径(uri_stem) 匹配正则 `.*(attachments\|upimg\|images\|css\|uploadfiles\|htm…`)的次数 > `10`,前置 请求路径(uri_stem) 匹配正则 `.*(attachments\|upimg\|images\|css\|uploadfiles\|htm…`(原始备注:`> 10 in 5m`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 恶意扫描 |
| **IP访问.asp文件** | 同一 IP:变量 `ip__visit_count__5m__rt`(IP请求量[5m]) ≥ `5`,前置 客户端ip(c_ip) 包含 `.`;请求路径(uri_stem) 以…结尾 `.asp`;响应状态码(status) = `404`(原始备注:`> 5 动态+静态 ,404 .asp`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 恶意扫描 |
| **IP请求可疑文件地址** | 同一 IP:5 分钟内「动态资源请求」事件(请求路径(uri_stem) 匹配正则 `.*(vhost\|bbs\|host\|wwwroot\|www\|site\|root\|hytop\|f…`)的次数 > `10`,前置 请求路径(uri_stem) 匹配正则 `.*(vhost\|bbs\|host\|wwwroot\|www\|site\|root\|hytop\|f…`(原始备注:`> 10 in 5m`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 恶意扫描 |

### 访客 · Web 攻击特征(13 条)

对请求参数做特征匹配,作为 WAF 的补充而非替代。

| 策略名 | 检测什么 | 命中后处置 | 标签 |
|---|---|---|---|
| **visit_directory_traversal_get_ip** | 同一 IP:单条事件即命中 客户端ip(c_ip) 包含 `.`;请求方法(method) = `GET`;请求参数(uri_query) 匹配正则 `\.\.\|/etc/passwd\|c:\\\\\|cmd\.exe\|\\\\\|/`(原始备注:`GET参数中包含目录遍历的特征`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 目录遍历 |
| **visit_directory_traversal_post_ip** | 同一 IP:单条事件即命中 客户端ip(c_ip) 包含 `.`;请求方法(method) = `POST`;请求参数(uri_query) 匹配正则 `\.\.\|/etc/passwd\|c:\\\\\|cmd\.exe\|\\\\\|/`(原始备注:`POST参数中包含目录遍历的特征`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | 目录遍历 |
| **visit_ngx_lua_waf_get_ip** | 同一 IP:单条事件即命中 请求参数(uri_query) 匹配正则 `\.\./\|\:\$\|\$\{\|select.+(from\|limit)\|(?:(union(…`;请求方法(method) = `GET`(原始备注:`GET参数中ngx_lua_waf策略补充`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | ngx_lua_waf |
| **visit_ngx_lua_waf_post_ip** | 同一 IP:单条事件即命中 请求方法(method) = `POST`;请求内容(c_body) 匹配正则 `\.\./\|\:\$\|\$\{\|select.+(from\|limit)\|(?:(union(…`(原始备注:`POST参数中ngx_lua_waf策略补充`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | ngx_lua_waf |
| **visit_rfi_get_ip** | 同一 IP:单条事件即命中 请求参数(uri_query) 匹配正则 `http://\|https://\|ftp://\|php://\|sftp://\|zlib://\|…`;请求方法(method) = `GET`(原始备注:`GET参数中包含rfi远程文件包含的特征`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | RFI |
| **visit_rfi_post_ip** | 同一 IP:单条事件即命中 请求方法(method) = `POST`;请求内容(c_body) 匹配正则 `http://\|https://\|ftp://\|php://\|sftp://\|zlib://\|…`(原始备注:`POST参数中包含rfi远程文件包含的特征`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | RFI |
| **visit_sql_injection_get_ip** | 同一 IP:单条事件即命中 请求参数(uri_query) 匹配正则 `.*(\bselect\b\|\bunion\b\|\bupdate\b\|\bdelete\b\|\…`;请求方法(method) = `GET`(原始备注:`GET参数中的包含SQL语句特征`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | SQL注入 |
| **visit_sql_injection_hardcore_get_ip** | 同一 IP:单条事件即命中 请求参数(uri_query) 匹配正则 `\"\|/\*\|\*/\|\\|\|&&\|--\|;\|\(\|\)\|\'\|,\|#\|@@`;请求方法(method) = `GET`(原始备注:`GET请求中包含SQL的符号`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | SQL注入 |
| **visit_sql_injection_post_hardcore_ip** | 同一 IP:单条事件即命中 请求方法(method) = `POST`;请求内容(c_body) 匹配正则 `\"\|/\*`(原始备注:`POST请求中包含SQL的符号`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | SQL注入 |
| **visit_sql_injection_post_ip** | 同一 IP:单条事件即命中 请求内容(c_body) 匹配正则 `.*(\bselect\b\|\bunion\b\|\bupdate\b\|\bdelete\b\|\…`;请求方法(method) = `POST`(原始备注:`POST参数中包含SQL语句特征`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | SQL注入 |
| **visit_xss_get_ip** | 同一 IP:单条事件即命中 请求参数(uri_query) 匹配正则 ``.*(%3c\|%3e\|<\|>\|[\|]\|~\|`).*``;请求方法(method) = `GET`(原始备注:`GET参数包含跨站脚本攻击特征`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | XSS |
| **visit_xss_post_ip** | 同一 IP:单条事件即命中 请求方法(method) = `POST`;请求内容(c_body) 匹配正则 ``.*(%3c\|%3e\|<\|>\|[\|]\|`).*``(原始备注:`POST参数包含跨站脚本攻击特征`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | XSS |
| **visit_xss_request_ip** | 同一 IP:单条事件即命中 请求参数(uri_query) 匹配正则 `.*script.*script.*`(原始备注:`请求疑似xss`) | `review` · IP名单(c_ip) · VISITOR · 5 分钟 | XSS |

### VISITOR · 未归类(1 条)

| 策略名 | 检测什么 | 命中后处置 | 标签 |
|---|---|---|---|
| **测试-地域FUNCTION** | 同一 IP:单条事件即命中 客户端ip(c_ip) 包含 `.`,另需 `nebula.HTTP_DYNAMIC.c_ip` 的归属地(province)= 上海市(原始备注:`地域function测试策略`) | `review` · IP名单(c_ip) · VISITOR · 5 小时 | — |

🔧 = 含占位符,需要先配置才能生效,见[下文](#需要配置才能生效的策略)。

---

## 三维度镜像设计

1.x 的内置策略遵循一条明确的设计规律:**同一个风险模式,按 IP / 设备 / 账号三个维度各写一条**。
同一族的三条策略结构几乎相同,区别只在分组键(`groupby`)、触发键(`trigger.keys`)和写入的名单主体(`checktype`)。

这样设计的原因是三个维度各有盲区:IP 会被代理池稀释,设备指纹会被改机工具伪造,账号在注册环节还不存在。三条一起跑才能互相补位。

下面的分族由生成器自动识别:去掉策略名的维度前缀,再把名字中剩余的维度词(IP/用户/设备)替换成 `×`,相同者归为一族。

### 分族统计

| | 族数 | 策略数 |
|---|---:|---:|
| 三个维度齐全 | 26 | 84 |
| 只覆盖两个维度 | 15 | 33 |
| 只有单个维度(无镜像) | 34 | 35 |
| 策略名不带维度前缀(`visit_*`、UA 类、测试策略) | — | 18 |
| **合计** | | **170** |

### 三个维度齐全的策略族

这些是最典型的镜像族。上线时建议**整族一起启停**,否则会留下盲区。

| 风险模式 | IP 维度 | 账号维度 | 设备维度 | 阈值/窗口 | 阈值一致 |
|---|---|---|---|---|---|
| 下单不支付 | IP下单不支付 | 用户下单不支付 | 设备下单不支付 | >4 / 30 分钟 | 是 |
| 关联多个× | IP关联多个用户<br>IP关联多个设备 | 用户关联多个IP<br>用户关联多个设备 | 设备关联多个IP<br>设备关联多个用户 | IP关联多个用户:>5 / 5 分钟<br>IP关联多个设备:>5 / 5 分钟<br>用户关联多个IP:>3 / 5 分钟<br>用户关联多个设备:>2 / 5 分钟<br>设备关联多个IP:>5 / 5 分钟<br>设备关联多个用户:>2 / 5 分钟 | **否** |
| 多×请求下单 | IP多用户请求下单<br>IP多设备请求下单 | 用户多IP请求下单<br>用户多设备请求下单 | 设备多IP请求下单<br>设备多用户请求下单 | IP多用户请求下单:>3 / 30 分钟<br>IP多设备请求下单:>3 / 30 分钟<br>用户多IP请求下单:>1 / 30 分钟<br>用户多设备请求下单:>1 / 30 分钟<br>设备多IP请求下单:>1 / 30 分钟<br>设备多用户请求下单:>1 / 30 分钟 | **否** |
| 多次取消订单 | IP多次取消订单 | 用户多次取消订单 | 设备多次取消订单 | >3 / 30 分钟 | 是 |
| 多次登录失败 | IP多次登录失败 | 用户多次登录失败 | 设备多次登录失败 | IP多次登录失败:>5 / 10 分钟<br>用户多次登录失败:>3 / 10 分钟<br>设备多次登录失败:>5 / 10 分钟 | **否** |
| 多次登录成功 | IP多次登录成功 | 用户多次登录成功 | 设备多次登录成功 | IP多次登录成功:>5 / 10 分钟<br>用户多次登录成功:>3 / 10 分钟<br>设备多次登录成功:>5 / 10 分钟 | **否** |
| 多次请求下单 | IP多次请求下单 | 用户多次请求下单 | 设备多次请求下单 | >5 / 30 分钟 | 是 |
| 多次请求下单__不同商品 | IP多次请求下单__不同商品 | 用户多次请求下单__不同商品 | 设备多次请求下单__不同商品 | >2 / 1 小时 | 是 |
| 多次请求下单__不同商户 | IP多次请求下单__不同商户 | 用户多次请求下单__不同商户 | 设备多次请求下单__不同商户 | >2 / 1 小时 | 是 |
| 多次请求下单__不同地址 | IP多次请求下单__不同地址 | 用户多次请求下单__不同地址 | 设备多次请求下单__不同地址 | >2 / 1 小时 | 是 |
| 多次请求下单__不同城市 | IP多次请求下单__不同城市 | 用户多次请求下单__不同城市 | 设备多次请求下单__不同城市 | >2 / 1 小时 | 是 |
| 多次请求下单__不同手机号 | IP多次请求下单__不同手机号 | 用户多次请求下单__不同手机号 | 设备多次请求下单__不同手机号 | >2 / 1 小时 | 是 |
| 多次请求下单__不同收货人 | IP多次请求下单__不同收货人 | 用户多次请求下单__不同收货人 | 设备多次请求下单__不同收货人 | >2 / 1 小时 | 是 |
| 多次请求下单__不同金额 | IP多次请求下单__不同金额 | 用户多次请求下单__不同金额 | 设备多次请求下单__不同金额 | >2 / 1 小时 | 是 |
| 多次请求下单__同一商品 | IP多次请求下单__同一商品 | 用户多次请求下单__同一商品 | 设备多次请求下单__同一商品 | >5 / 1 小时 | 是 |
| 多次请求下单__同一商户 | IP多次请求下单__同一商户 | 用户多次请求下单__同一商户 | 设备多次请求下单__同一商户 | >5 / 1 小时 | 是 |
| 多次请求下单__同一地址 | IP多次请求下单__同一地址 | 用户多次请求下单__同一地址 | 设备多次请求下单__同一地址 | >5 / 1 小时 | 是 |
| 多次请求下单__同一城市 | IP多次请求下单__同一城市 | 用户多次请求下单__同一城市 | 设备多次请求下单__同一城市 | >5 / 1 小时 | 是 |
| 多次请求下单__同手机号 | IP多次请求下单__同手机号 | 用户多次请求下单__同手机号 | 设备多次请求下单__同手机号 | >5 / 1 小时 | 是 |
| 多次请求下单__同收货人 | IP多次请求下单__同收货人 | 用户多次请求下单__同收货人 | 设备多次请求下单__同收货人 | >5 / 1 小时 | 是 |
| 多次请求下单__金额较低 | IP多次请求下单__金额较低 | 用户多次请求下单__金额较低 | 设备多次请求下单__金额较低 | >5 / 1 小时 | 是 |
| 多次请求下单__金额较高 | IP多次请求下单__金额较高 | 用户多次请求下单__金额较高 | 设备多次请求下单__金额较高 | >5 / 1 小时 | 是 |
| 多次请求注册 | IP多次请求注册 | 用户多次请求注册 | 设备多次请求注册 | IP多次请求注册:>10 / 5 分钟<br>用户多次请求注册:>2 / 5 分钟<br>设备多次请求注册:>10 / 5 分钟 | **否** |
| 多次请求登录 | IP多次请求登录 | 用户多次请求登录 | 设备多次请求登录 | IP多次请求登录:>5 / 5 分钟<br>用户多次请求登录:>3 / 5 分钟<br>设备多次请求登录:>5 / 5 分钟 | **否** |
| 请求A一段时间内没有请求B | IP请求A一段时间内没有请求B | 用户请求A一段时间内没有请求B | 设备请求A一段时间内没有请求B | =0 / 5 分钟 | 是 |
| 请求下单行为单一 | IP请求下单行为单一 | 用户请求下单行为单一 | 设备请求下单行为单一 | >3 / 30 分钟 | 是 |

### 只覆盖两个维度的策略族

1.x 没有把这些模式补齐。缺失的那一维通常是可以照着补的 —— 复制一条,改 `groupby`、`trigger.keys`、`condition` 里的键和 `checkvalue`/`checktype` 即可。

| 风险模式 | IP 维度 | 账号维度 | 设备维度 | 阈值/窗口 | 阈值一致 |
|---|---|---|---|---|---|
| 使用相同邀请码注册 | IP使用相同邀请码注册 | — | 设备使用相同邀请码注册 | >3 / 1 小时 | 是 |
| 使用相同邀请码注册5m | IP使用相同邀请码注册5m | — | 设备使用相同邀请码注册5m | >2 / 5 分钟 | 是 |
| 关联多个×地域 | — | 用户关联多个IP地域 | 设备关联多个IP地域 | >2 / 5 分钟 | 是 |
| 在多个×请求注册 | — | 用户在多个IP请求注册<br>用户在多个设备请求注册 | 设备在多个IP请求注册 | >2 / 10 分钟 | 是 |
| 在多个×请求登录 | — | 用户在多个IP请求登录<br>用户在多个设备请求登录 | 设备在多个IP请求登录 | 用户在多个IP请求登录:>2 / 10 分钟<br>用户在多个设备请求登录:>2 / 10 分钟<br>设备在多个IP请求登录:>5 / 5 分钟 | **否** |
| 多个×请求注册 | IP多个用户请求注册<br>IP多个设备请求注册 | — | 设备多个用户请求注册 | >2 / 10 分钟 | 是 |
| 多次使用相同密码注册 | IP多次使用相同密码注册 | — | 设备多次使用相同密码注册 | IP多次使用相同密码注册:>3 / 5 分钟<br>设备多次使用相同密码注册:=1 / 5 分钟 | **否** |
| 多次注册失败 | IP多次注册失败 | — | 设备多次注册失败 | IP多次注册失败:>5 / 5 分钟<br>设备多次注册失败:>10 / 5 分钟 | **否** |
| 多次注册成功 | IP多次注册成功 | — | 设备多次注册成功 | IP多次注册成功:>5 / 5 分钟<br>设备多次注册成功:>10 / 5 分钟 | **否** |
| 换密码请求登录单账号 | IP换密码请求登录单账号 | — | 设备换密码请求登录单账号 | >5 / 5 分钟 | 是 |
| 相同密码请求登录不同账号 | IP相同密码请求登录不同账号 | — | 设备相同密码请求登录不同账号 | IP相同密码请求登录不同账号:=3 / 5 分钟<br>设备相同密码请求登录不同账号:>3 / 5 分钟 | **否** |
| 请求注册前未访问必要资源 | IP请求注册前未访问必要资源 | — | 设备请求注册前未访问必要资源 | =0 / 5 分钟 | 是 |
| 请求登录前未访问必要资源 | IP请求登录前未访问必要资源 | — | 设备请求登录前未访问必要资源 | =0 / 5 分钟 | 是 |
| 集中请求注册接口 | IP集中请求注册接口 | — | 设备集中请求注册接口 | >5 / 30 分钟 | 是 |
| 集中请求登录 | IP集中请求登录 | — | 设备集中请求登录 | >5 / 30 分钟 | 是 |

### 只有单个维度、没有镜像的策略

多数是访客场景(VISITOR)的流量特征策略 —— 这一场景在 1.x 里**只有 IP 一个维度**,因为纯 HTTP 流量里往往拿不到账号,设备号也未必可信。账号/订单场景里的几条(如 `用户换密码登录`、`用户深夜多次请求下单`)则是真正的缺口:换个维度同样成立,1.x 只是没写。

| 策略名 | 维度 | 场景 |
|---|---|---|
| IP关联多用户请求登录 | IP | 账号 · 登录与撞库 |
| IP关联多设备请求登录 | IP | 账号 · 登录与撞库 |
| IP响应字节过大 | IP | 访客 · 恶意扫描 |
| IP大量404返回 | IP | 访客 · 恶意扫描 |
| IP大量4XX返回 | IP | 访客 · 恶意扫描 |
| IP大量动态请求 | IP | 访客 · 高频与单一访问 |
| IP大量访问 | IP | 访客 · 高频与单一访问 |
| IP大量请求不加载静态资源 | IP | 访客 · 高频与单一访问 |
| IP大量请求不带referrer | IP | 访客 · 高频与单一访问 |
| IP大量请求单个接口 | IP | 访客 · 高频与单一访问 |
| IP大量请求注册接口 | IP | 访客 · 高频与单一访问 |
| IP大量请求登录接口 | IP | 访客 · 高频与单一访问 |
| IP大量请求相似接口 | IP | 访客 · 高频与单一访问 |
| IP大量请求签到接口 | IP | 访客 · 高频与单一访问 |
| IP大量连续GET请求 | IP | 访客 · 高频与单一访问 |
| IP大量连续POST请求 | IP | 访客 · 高频与单一访问 |
| IP大量连续其他类型请求 | IP | 访客 · 高频与单一访问 |
| IP当天关联多个用户 | IP | 账号 · 身份关联异常 |
| IP扫描管理后台 | IP | 访客 · 恶意扫描 |
| IP相同UA大量请求单个页面 | IP | 访客 · 高频与单一访问 |
| IP访问.asp文件 | IP | 访客 · 恶意扫描 |
| IP请求可疑文件地址 | IP | 访客 · 恶意扫描 |
| IP集中请求部分接口 | IP | 访客 · 高频与单一访问 |
| IP页面停留时间过短App | IP | 访客 · 高频与单一访问 |
| IP页面停留时间过短Web | IP | 访客 · 高频与单一访问 |
| 用户换密码登录 | 用户 | 账号 · 登录与撞库 |
| 用户深夜多次请求下单 | 用户 | 订单 · 深夜与特殊下单 |
| 用户深夜请求下单金额过大 | 用户 | 订单 · 深夜与特殊下单 |
| 用户相同密码请求登录 | 用户 | 账号 · 登录与撞库 |
| 用户请求下单__金额较大5m | 用户 | 订单 · 深夜与特殊下单 |
| 用户高频请求下单__不同城市5m | 用户 | 订单 · 深夜与特殊下单 |
| 用户高频请求下单__全部失败5m | 用户 | 订单 · 深夜与特殊下单 |
| 用户高频请求下单__同一商户5m | 用户 | 订单 · 深夜与特殊下单 |
| 用户高频请求取消订单5m | 用户 | 订单 · 深夜与特殊下单 |
| 设备多用户请求登录 | 设备 | 账号 · 登录与撞库 |

另有 18 条策略名不带维度前缀:`Java 用户代理`、`Python 用户代理`、`Spider 用户代理`、`visit_directory_traversal_get_ip`、`visit_directory_traversal_post_ip`、`visit_ngx_lua_waf_get_ip`、`visit_ngx_lua_waf_post_ip`、`visit_rfi_get_ip`、`visit_rfi_post_ip`、`visit_sql_injection_get_ip`、`visit_sql_injection_hardcore_get_ip`、`visit_sql_injection_post_hardcore_ip`、`visit_sql_injection_post_ip`、`visit_xss_get_ip`、`visit_xss_post_ip`、`visit_xss_request_ip`、`服务器用户代理`、`测试-地域FUNCTION`。
它们全部按 IP 维度处置。

### ⚠️ 名称与名单主体不一致的策略

生成器发现下列策略的**名字声明的维度**与**实际写入的名单主体**对不上 —— 这是 1.x 数据里的既有问题(复制粘贴时漏改),不是生成器的误判。启用前请逐条确认到底想拉黑谁。

| 策略名 | 名称暗示的主体 | 实际写入的名单 | 实际统计口径(groupby) |
|---|---|---|---|
| IP使用相同邀请码注册 | `IP` | `DeviceID` | `c_ip` |
| IP多次请求下单__不同金额 | `IP` | `DeviceID` | `c_ip` |
| IP集中请求登录 | `IP` | `DeviceID` | `did` |
| 设备多次使用相同密码注册 | `DeviceID` | `IP` | `did` |
| 设备集中请求登录 | `DeviceID` | `IP` | `c_ip` |

---

## 需要配置才能生效的策略

`index.json` 标了 **7 条**含占位符的策略;生成器另外扫出 **3 条**写死了字面占位值的骨架策略。这 **10 条**在配置之前**导入后不会正确工作**(全表中以 🔧 标出)。占位符的完整说明见 [`seeds/PLACEHOLDERS.md`](../../seeds/PLACEHOLDERS.md) 对应条目。

### `<YOUR_PAYMENT_PAGE_PATH>`

能唯一标识你自己**支付/结算页面**的 URL 路径片段,例如 `/order/pay`、`/checkout/confirm`。策略用它来统计「下单之后有没有真的去付款」以及「进入登录/注册前有没有访问过必要页面」。比较运算符是 `contain`,填子串即可。

| 策略名 | 出现位置 | 不配置的后果 |
|---|---|---|
| **IP下单不支付** | `nebula.HTTP_DYNAMIC` 计数器条件 `page contain <YOUR_PAYMENT_PAGE_PATH>` | 计数器恒为 0 —— 策略会把**所有**下单主体都判成「下单不支付」 |
| **IP请求注册前未访问必要资源** | `nebula.HTTP_DYNAMIC` 计数器条件 `page contain <YOUR_PAYMENT_PAGE_PATH>` | 计数器恒为 0 —— 策略会把**所有**登录/注册主体都判成「未访问必要资源」 |
| **IP请求登录前未访问必要资源** | `nebula.HTTP_DYNAMIC` 计数器条件 `page contain <YOUR_PAYMENT_PAGE_PATH>` | 计数器恒为 0 —— 策略会把**所有**登录/注册主体都判成「未访问必要资源」 |
| **用户下单不支付** | `nebula.HTTP_DYNAMIC` 计数器条件 `page contain <YOUR_PAYMENT_PAGE_PATH>` | 计数器恒为 0 —— 策略会把**所有**下单主体都判成「下单不支付」 |
| **设备下单不支付** | `nebula.HTTP_DYNAMIC` 计数器条件 `page contain <YOUR_PAYMENT_PAGE_PATH>` | 计数器恒为 0 —— 策略会把**所有**下单主体都判成「下单不支付」 |
| **设备请求注册前未访问必要资源** | `nebula.HTTP_DYNAMIC` 计数器条件 `page contain <YOUR_PAYMENT_PAGE_PATH>` | 计数器恒为 0 —— 策略会把**所有**登录/注册主体都判成「未访问必要资源」 |
| **设备请求登录前未访问必要资源** | `nebula.HTTP_DYNAMIC` 计数器条件 `page contain <YOUR_PAYMENT_PAGE_PATH>` | 计数器恒为 0 —— 策略会把**所有**登录/注册主体都判成「未访问必要资源」 |

### 另外 3 条「骨架策略」(index.json 未标记)

这几条策略把页面路径写成了 `A`、`B` 这样的字面占位值 —— 它们是 1.x 留下的**模式骨架**,不是可用策略。`index.json` 没有把它们标成 `requires_configuration`(占位符扫描只认 `<YOUR_*>` 形式),但不改同样不会有任何意义:`page == "A"` 在真实流量里永不成立。

| 策略名 | 占位值 | 要填什么 |
|---|---|---|
| **IP请求A一段时间内没有请求B** | `A`、`B` | A = 先访问的页面,B = 本应随后访问的页面;两处 `page` 条件都要换成真实路径 |
| **用户请求A一段时间内没有请求B** | `A`、`B` | A = 先访问的页面,B = 本应随后访问的页面;两处 `page` 条件都要换成真实路径 |
| **设备请求A一段时间内没有请求B** | `A`、`B` | A = 先访问的页面,B = 本应随后访问的页面;两处 `page` 条件都要换成真实路径 |

语义是「访问了 A,但 5 分钟内没有访问 B」—— 用 `sleep` 延迟判定实现,是模板里唯一一组否定式(negative)策略,可以拿它当自定义策略的样板。

**怎么改**:直接编辑 `seeds/strategies/` 下对应文件,把占位符字符串替换掉;或在导入控制台后于策略编辑页修改该条件。替换后重新运行 `python3 tools/validate_seeds.py` 确认仍然合法。

> 这两组策略之所以都用同一个占位符,是因为它们都在问「用户有没有访问过某个关键页面」。「下单不支付」问的是支付页,「未访问必要资源」问的是登录/注册前应当加载的页面 —— 后者在你的站点上很可能是**另一个路径**,不要无脑填成一样的。

---

## 重要提示(启用前必读)

以下不是「注意事项」式的套话,而是这批模板**当前的真实状态**。全部由生成器从数据中统计得出。

### 1. 没有一条策略会自动阻断

170 条策略的处置决策分布:`review` × 170。

也就是说 **170/170 条全部是 `review`(转人工审核)**,没有任何一条会自动拦截、二次验证或限流。1.x 当年设计了处置能力但没有落地,系统实际只能产出「待审核」告警。

**启用前你需要自己决定处置动作**:哪些策略可以直接阻断,哪些只发告警,哪些走二次验证。建议路径是先全部保持 `review` 观察一段时间,拿到误报率之后再逐条提升处置强度。

### 2. 风险分(score)没有落地

score 取值分布:0 × 169、1 × 1。

**169/170 条的 score 为 0**,只有 `设备请求下单行为单一`(score=1) 是例外 —— 这个孤例没有任何配套逻辑,基本可以判定为 1.x 里的遗留噪声,而非有意设计。

score 为 0 意味着**风险评分能力等于没有开启**:命中再多条策略,主体的风险分仍然是 0,无法按分数分级处置。要用起来,需要按自身业务给每条策略赋权 —— 通常按「误报代价 × 风险严重度」定档,而不是拍脑袋给 60/80/100。

### 3. 阈值来自 1.x 当年的业务流量,必须按自己的量级校准

这批模板里的阈值(`>5 in 5m`、`>300 in 5m` 之类)是 1.x 某个电商站点的经验值,窗口集中在 5 分钟–1 小时。**直接照搬几乎一定不合适**:

- 流量比当年大的站点会被淹没在误报里;流量小的站点则永远打不到阈值;
- 移动端 App 与 Web 的行为基线差别很大(模板里 `IP页面停留时间过短App/Web` 就分了两条);
- NAT、企业出口、运营商网关后面的 IP 天然「多用户多设备」,IP 维度阈值尤其需要放宽或加白名单。

**推荐做法**:先把策略置为 `test` 状态跑历史回放或影子流量,统计每条策略的命中量与命中主体分布,再把阈值定在「日均命中量可人工消化」的水位上。另外注意 2.0 的 `distinct_count` 修正了 1.x 的高估问题,同一份流量下新值会**略低于**旧值,基于去重计数的阈值需要下调(见[从 1.x 迁移](../migration/from-1x.md))。

### 4. 其它需要留意的现状

- **导入即全部生效**:status 分布为 `online` × 170。170 条策略的状态是 `online`,导入后会立刻开始产生告警。如需先观察,导入后请批量改为 `test`。
- **生效时间戳是历史值**:所有模板的 `end_effect` 落在 2017-10-27 — 2024-01-06 之间,均已是过去时间。如果引擎严格按这两个字段判生效期,导入后策略会「一条都不命中」—— 需要在导入时重写这两个字段。
- **名单有效期普遍很短**:多数策略的 TTL 是 5 分钟,只够用于实时联防;要做长期黑名单需要自己调 `ttl` 或在下游落库。
- **策略之间会重复命中**:三维度镜像意味着一次攻击往往同时触发 3 条策略,告警去重与合并要在消费侧做,否则运营会被同一事件刷屏。
- **个别策略的备注与实际条件不符**:`IP相同密码请求登录不同账号`(备注 >3 in 5min,实际 `==3`)、`设备多次使用相同密码注册`(备注 >3, 1密码 in 5m,实际 `==3`)。`==` 意味着「不多不少正好等于」,在真实流量里几乎永远不命中,看起来是 1.x 里写错了运算符。本文表格中的「检测什么」以 **terms 实际条件**为准,备注仅供对照。
- **含测试策略**:`测试-地域FUNCTION` 是 1.x 留下的功能验证策略,没有业务含义,建议导入后直接停用或删除。

---

*本文由 `tools/gen_strategy_reference.py` 生成。数据源:`seeds/strategies/`(170 条)、`seeds/events/`、`seeds/variables/`。*
