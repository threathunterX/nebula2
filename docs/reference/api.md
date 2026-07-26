# API 参考

控制面全部接口。**默认拒绝**:未认证返回 401,认证但越权返回 403,只有
`/actuator/health` 匿名可读。

> 本文手工维护。OpenAPI 自动生成尚未实现 —— 在那之前,以
> [`apps/console-api/`](../../apps/console-api/) 的说明与代码为准。

---

## 认证

**两类主体,权限互不重叠。**

| 主体 | 方式 | 能访问 |
|---|---|---|
| 人(ADMIN / OPERATOR / VIEWER) | HTTP Basic + Argon2id | `/api/v2/**` |
| 服务(业务系统、引擎) | `Authorization: Bearer svc_<id>.<secret>` | 按作用域 |

分开的理由:业务系统的令牌散布在几十台应用服务器上,泄露概率远高于管理员账号。两者
共用一套凭据时,一个业务侧令牌泄露就意味着策略配置同时失守。

**服务令牌作用域**:

| 作用域 | 可访问 |
|---|---|
| `checkRisk` | `POST /checkRisk` |
| `metadata:read` | `GET /api/v2/metadata/**` |

令牌可绑定 `allowed_cidrs`,与令牌是 **AND** 关系:令牌正确但来源 IP 不在网段内一样
401。校验的所有失败路径(不存在、已吊销、已过期、密文不符、来源不匹配)返回同一个
401,不透露原因 —— 否则攻击者能用错误信息区分「令牌不存在」和「令牌对但 IP 不对」,
后者等于确认了一个有效令牌。

---

## 1. `POST /checkRisk`

业务系统同步查询。**与 1.x 保持契约兼容**,请求与响应结构不变。

需要 `SCOPE_checkRisk`。**人类账号调不了这个接口**(403)。

```json
{"check_item": [{"type": "IP", "value": "198.51.100.77"}], "scene_type": "ACCOUNT"}
```

```json
{"success": true, "error_code": "", "final_decision": "review",
 "final_rule_hit": "IP多次登录失败", "rule_hits": []}
```

多条名单命中时 `final_decision` 取最严格的:`reject` > `review` > `accept`。

直接查 Redis,不经过数据库。名单失效由 Redis TTL 自动完成。

---

## 2. 元数据(供引擎)

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/api/v2/metadata/version` | `metadata:read` 或任意人类角色 |
| GET | `/api/v2/metadata/bundle` | 同上 |

`/version` 返回 `{"version": 6}`,供引擎轮询;`/bundle` 返回全量事件、变量、策略。

`bundle` 的 `status` 参数默认 `online,test` —— `inedit` 是没写完的草稿,`outline` 是已
下线的,发给引擎等于让草稿直接影响线上判定。

响应中的 `version` 先于内容读取。反过来会得到「较新的版本号 + 偏旧的内容」,引擎认为
自己已是最新,改动永远不生效。

---

## 3. 策略

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/api/v2/strategies` | 任意角色。支持 `category` / `status` 过滤 |
| GET | `/api/v2/strategies/{name}` | 任意角色。返回**行版本**与定义 |
| PUT | `/api/v2/strategies/{name}` | ADMIN / OPERATOR |
| PUT | `/api/v2/strategies/{name}/status` | ADMIN / OPERATOR |
| GET | `/api/v2/strategies/{name}/revisions` | 任意角色 |
| GET | `/api/v2/strategies/{name}/revisions/{version}` | 任意角色 |

详情响应把行元数据与定义分开:

```json
{
  "name": "IP多次登录失败",
  "version": 7,              // 行版本,写入时的乐观并发用它
  "status": "test",
  "requires_config": false,
  "definition": { "version": "2.0", ... }   // 领域模型版本,与行版本无关
}
```

> **这两个 `version` 不是一回事。** 早先详情接口直接返回 definition,客户端拿不到行版本,
> 想做乐观并发只能去猜。第一个真实客户端(管理界面)一上来就把 `definition.version`
> 的 `"2.0"` 当成行版本发了过去,PUT 稳定返回 409。接口让正确用法无法表达时,错的是接口。

写入请求体:

```json
{"definition": {...}, "expected_version": 2, "change_note": "阈值 100 调到 50"}
```

`expected_version` **必填**,新建传 0。版本冲突返回 **409**(不是 400 —— 请求本身没错,
是状态变了):

```json
{"error": "版本冲突:你基于 v1 修改,当前已是 v2。请重新拉取后再提交", "current_version": 2}
```

校验失败返回 400,**一次给出全部问题**:

```json
{"error": "策略定义校验未通过",
 "problems": ["counter 引用了不存在的事件:ORDER_SUBMITT(策略结构合法,但上线后永不命中,且不会报错)"]}
```

`status` 取值:`inedit` / `test` / `online` / `outline`。

---

## 4. 变量与统计

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/api/v2/variables` | 任意角色。支持 `module` / `sensitivity` 过滤 |
| GET | `/api/v2/variables/{name}` | 任意角色 |
| GET | `/api/v2/stats` | 任意角色 |

变量目前**只读**。

---

## 5. 告警

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/api/v2/alerts` | 任意角色 |
| GET | `/api/v2/alerts/trend` | 任意角色 |

**`from` / `to` 必填**(ISO-8601),跨度不超过 90 天。告警表按 `toDate(notice_time)`
分区,不给范围就是全表扫描。

| 参数 | 默认 | 说明 |
|---|---|---|
| `scene` / `strategy` / `decision` / `check_type` / `subject` | — | 过滤 |
| `include_test` | `false` | 是否含 `test` 状态策略产生的告警 |
| `sort` | `notice_time` | 仅 `notice_time` / `risk_score` |
| `page` / `size` | `0` / `50` | `size` 上限 500 |

响应里的 `subject_masked` 标明主体值是否被掩码:**VIEWER 看掩码值,OPERATOR / ADMIN 看
原值**。按 `subject` 精确查询会单独记审计 —— 那是「查某个人」,与浏览列表不是一回事。

`variable_values` 是判定依据(哪个指标、当前值、比较符、阈值)。1.x 该字段被写死为空
字符串。

`/trend` 读物化视图维护的 `notices_hourly`,不是明细表。

---

## 6. 账号与令牌

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/api/v2/users/me` | 任意已认证的人类角色。返回自己的账号与角色 |
| GET | `/api/v2/users` | ADMIN。不返回口令哈希 |
| POST | `/api/v2/users` | ADMIN |
| PUT | `/api/v2/users/{username}/enabled` | ADMIN。停用/启用,**不能停用自己** |
| POST | `/api/v2/users/{username}/password` | ADMIN。重置口令,新口令只返回一次 |
| GET | `/api/v2/tokens` | ADMIN。只返回元数据,**不含明文也不含哈希** |
| POST | `/api/v2/tokens` | ADMIN |
| DELETE | `/api/v2/tokens/{tokenId}` | ADMIN。吊销 |

`/api/v2/users/me` 存在是为了让界面能按角色决定显示什么 —— 没有它,前端只能把管理员
才用得上的入口显示给所有人,点进去才报 403。服务令牌调它返回 403:令牌不是人,没有角色。

**停用与吊销都不删行。** 账号名会出现在审计日志的 `actor` 列,令牌 ID 出现在
`resource_id` 列;删掉行会让那些记录指向一个查不到的对象,而追查通常正发生在停用之后。
重复停用 / 重复吊销返回 404 而不是 200 —— 「刚刚停掉一个还在用的」和「它早就停了」是
两种处境,不该看到同样的结果。

`last_used_at` 只在令牌**校验成功**时更新,失败的尝试不计入。它回答的是「这个令牌还有
人在用吗」,这是清理陈旧令牌时唯一能依据的事实。

**都不接受调用方指定口令或令牌明文** —— 由服务端生成并只返回一次。让调用方传口令意味着
它会出现在请求体、反向代理日志、shell 历史和 CI 变量里。

```json
{"username": "viewer", "display_name": "安全运营只读", "roles": ["VIEWER"]}
```

```json
{"description": "订单系统", "scopes": ["checkRisk"], "allowed_cidrs": ["10.1.0.0/16"]}
```

---

## 7. 领域 schema

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/api/v2/schema` | 任意已认证的人类角色。列出可取的 schema |
| GET | `/api/v2/schema/{which}` | 同上。`strategy` / `event-model` / `variable-model` / `notice` / `enums` |

**原样下发 `packages/domain-schema/` 的内容,与服务端校验用的是同一份文件。**

存在的理由是让界面能从领域模型派生取值(category 有哪些、比较算子有哪些、decision
有哪些),而不是在前端另抄一份。抄一份的后果不是编译错误,而是**界面允许的和服务端
接受的不一样**:schema 加了个算子界面选不到,删了个算子界面还能选中然后保存时 400。
两种都表现为「这个功能好像坏了」,而没有任何地方会报警。

不加工成「界面友好」的结构 —— 加工就是第二次表达,同样会漂移,只是把漂移从前端挪到
了服务端。

---

## 8. 数据主体权利

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/api/v2/privacy/subject/{type}/{value}/export` | ADMIN |
| DELETE | `/api/v2/privacy/subject/{type}/{value}` | ADMIN |

`type` 取名单的主体类型 `IP` / `USER` / `DeviceID` / `OrderID`(旧写法 `ip` / `uid` / `did`
仍然可用)。两个接口都记审计,审计里存的是<b>掩码后</b>的主体值。

导出返回该主体的事件明细与风险告警:

```json
{
  "subject": {"type": "USER", "value": "u-10086"},
  "events": [{"event_time": "2026-07-26 12:00:00.000", "event_name": "ACCOUNT_LOGIN", "...": "..."}],
  "notices": [{"notice_time": "...", "strategy_name": "...", "decision": "deny", "...": "..."}]
}
```

删除返回实际清理的范围:

```json
{
  "subject": {"type": "IP", "value": "203.0.113.7"},
  "redis_keys_removed": 2,
  "rollup_tables_purged": ["events_hourly"],
  "irreversible": true
}
```

**ClickHouse 的删除是异步 mutation**,接口返回不代表数据已经消失。删得掉什么、删不掉
什么(以及 HMAC 密钥轮换带来的不可逆后果)见[隐私设计](../security/privacy.md#四数据主体权利)。

---

## 9. 错误码

| 码 | 含义 |
|---|---|
| 400 | 请求参数或策略定义不合法,`problems` 里是全部问题 |
| 401 | 未认证,或令牌校验失败(不区分具体原因) |
| 403 | 已认证但无权限 |
| 404 | 资源不存在 |
| 409 | 版本冲突 |
| 502 | 下游存储(ClickHouse)不可用 |
| 503 | 未配置 ClickHouse 凭据,告警查询不可用 |

| 429 | 登录失败次数超限,该「来源 IP + 账号」组合已被锁定 |

**登录失败已有计数与锁定**(按「来源 IP + 账号」组合),但**管理接口的全局限流尚未实现**
—— 见 [SECURITY.md](../../SECURITY.md) 的「规划中」一节。暴露到不可信网络前请在前置代理上做。

---

## 尚未实现

名单的手工加白 / 加黑与批量导入、事件模型与变量的写接口、OpenAPI 自动生成、
管理接口的全局限流。

---

## 相关文档

| | |
|---|---|
| [控制面](../../apps/console-api/) | 实现说明与设计理由 |
| [接入指南](../guide/integration.md) | 业务系统怎么接 |
| [配置项参考](configuration.md) | 全部配置项 |
