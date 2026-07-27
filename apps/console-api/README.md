# console-api —— 控制面

策略与变量管理、名单查询、`/checkRisk` 对外接口。Java 21 + Spring Boot 3。

合并了 1.x 的三个服务(`apiserver` 自研 Netty 框架、`nebula_web` Tornado、`nebula_query_web` Flask)。合并的理由不只是简化部署:那三者当年用两种语言实现、各维护一份领域模型,是模型漂移的直接来源。

## 状态

| 能力 | 状态 |
|---|---|
| 策略查询与状态切换 | ✅ |
| 变量查询(按模块 / 敏感级别) | ✅ |
| `/checkRisk` 同步风险查询 | ✅ |
| 审计日志 | ✅ |
| 认证与授权(Argon2id 口令 + 角色 + 服务令牌) | ✅ |
| OIDC 对接企业身份源 | 🚧 |
| 告警查询与趋势(ClickHouse) | ✅ |
| 账号管理(建号、列表) | ✅ |
| 策略创建与编辑(schema 校验 + 修订历史) | ✅ |

所有接口默认拒绝。未认证请求返回 401,认证但越权返回 403,只有 `/actuator/health` 匿名可读。

## 运行

```bash
cd apps/console-api && mvn -q -DskipTests package
set -a; . ../../deploy/compose/.env; set +a
java -jar target/nebula-console-api-0.5.0.jar
```

凭据只从环境变量注入,配置文件里不含任何可用凭据。缺少 `REDIS_PASSWORD` 时**启动即失败**,不会静默连接无密码实例。

## 认证

**两类主体,权限互不重叠。**

- **人**(管理员 / 操作员 / 观察员)—— 口令登录,访问 `/api/v2/**` 管理接口,**不能**调用 `/checkRisk`
- **服务**(业务系统)—— Bearer 令牌,调用 `/checkRisk`,**不能**访问任何管理接口

分开的理由:业务系统的令牌散布在几十台应用服务器上,泄露概率远高于管理员账号。两者共用一套凭据时,一个业务侧令牌泄露就意味着策略配置同时失守。

### 首次启动

库中没有任何用户时,自动创建 `admin` 并把随机口令**打印一次**到启动日志:

```
已创建初始管理员账号。此口令只显示这一次,请立即记录并尽快更换。
  用户名: admin
  口令:   <24 字节随机数的 Base64url>
```

口令以 Argon2id 存储,日志之外任何地方都取不回明文。丢了只能直接改库中的哈希。

### 角色

| 角色 | 读元数据 | 改策略状态 | 签发令牌 |
|---|---|---|---|
| `VIEWER` | ✅ | ✖ | ✖ |
| `OPERATOR` | ✅ | ✅ | ✖ |
| `ADMIN` | ✅ | ✅ | ✅ |

### 服务令牌

```bash
curl -u admin:<口令> -XPOST localhost:8080/api/v2/tokens \
  -H 'Content-Type: application/json' \
  -d '{"description":"订单系统","scopes":["checkRisk"],"allowed_cidrs":["10.1.0.0/16"]}'
```

响应中的 `token` 是 `svc_<id>.<密文>` 格式,**只出现这一次**;库里只存密文的 SHA-256。

`allowed_cidrs` 与令牌是 **AND** 关系:令牌正确但来源 IP 不在网段内,一样 401。若写成「IP 在白名单 **或** token 匹配」,任一成立即放行 —— 两道防线各自都能被单独绕过,等于只有一道。

调用时:

```bash
curl -H "Authorization: Bearer svc_xxx.yyy" -XPOST localhost:8080/checkRisk ...
```

令牌校验的所有失败路径(不存在、已吊销、已过期、密文不符、来源不匹配)返回同一个 401,不透露失败原因 —— 否则攻击者可以用错误信息区分「令牌不存在」和「令牌对但 IP 不对」,后者等于确认了一个有效令牌。密文比对走常量时间。

## 接口

### `/checkRisk` —— 业务系统同步查询

**与 1.x 保持契约兼容**,请求与响应结构不变,接入代码无需改动。这是 2.0 明确承诺不变更的对外契约之一。

```bash
curl -XPOST localhost:8080/checkRisk -H 'Content-Type: application/json' \
  -d '{"check_item":[{"type":"IP","value":"198.51.100.77"}],"scene_type":"ACCOUNT"}'
```

多条名单命中时,`final_decision` 取**最严格**的一个:`reject` > `review` > `accept`。

延迟敏感路径:直接查 Redis 名单,不经过数据库、不做复杂计算。名单的失效由 Redis TTL 自动完成。

### 管理接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v2/stats` | 资产统计与元数据版本 |
| GET | `/api/v2/strategies` | 策略列表,可按 `category` / `status` 过滤 |
| GET | `/api/v2/strategies/{name}` | 策略完整定义 |
| PUT | `/api/v2/strategies/{name}/status` | 切换状态,记审计 |
| PUT | `/api/v2/strategies/{name}` | 新建或更新定义,校验 + 记历史 |
| GET | `/api/v2/strategies/{name}/revisions` | 修订历史 |
| GET | `/api/v2/strategies/{name}/revisions/{version}` | 某个历史版本的完整定义 |
| GET | `/api/v2/variables` | 变量列表,可按 `module` / `sensitivity` 过滤 |
| GET | `/api/v2/variables/{name}` | 变量完整定义 |
| POST | `/api/v2/tokens` | 签发服务令牌,仅 ADMIN,记审计 |
| GET | `/api/v2/users` | 账号清单(不含口令哈希),仅 ADMIN |
| POST | `/api/v2/users` | 建号,口令由服务端生成且只返回一次,仅 ADMIN |
| GET | `/api/v2/alerts` | 告警查询,必须带时间范围 |
| GET | `/api/v2/alerts/trend` | 按小时的告警趋势 |
| GET | `/api/v2/metadata/version` | 元数据版本号,供引擎轮询 |
| GET | `/api/v2/metadata/bundle` | 事件 / 变量 / 策略全量,供引擎加载 |

### 告警查询

```bash
curl -u admin:<口令> -G localhost:8080/api/v2/alerts \
  --data-urlencode 'from=2026-07-25T00:00:00Z' \
  --data-urlencode 'to=2026-07-26T00:00:00Z' \
  --data-urlencode 'scene=ACCOUNT' \
  --data-urlencode 'decision=reject'
```

引擎产出的告警此前只写进 ClickHouse 和 Redis,没有读取入口 —— 系统在报什么,
运营看不到。`/checkRisk` 回答的是「这个主体现在有没有风险」,回答不了「昨天哪条
策略在报、报了多少、依据是什么」。返回里的 `variable_values` 就是判定依据
(1.x 该字段被写死为空字符串)。

几条硬约束:

- **必须带时间范围**,且不超过 90 天。`notices` 表按 `toDate(notice_time)` 分区,
  不给范围就是全表扫描,一个手滑的请求能把线上写入拖垮
- **主体值按角色分级**:`VIEWER` 看掩码值,`OPERATOR` / `ADMIN` 看原值。响应里的
  `subject_masked` 标明当前是哪种
- **按主体精确查询单独记审计** —— 那是「查某个人」,与浏览列表不是一回事。审计里
  存的是掩码值:要能回答「谁在什么时候查了谁」,不需要把标识再抄一份到保留期
  更长的表里
- 查询条件全部走 ClickHouse 的 `{name:Type}` 参数化,排序列走白名单。会话开
  `readonly=1`,即便 SQL 被构造错了也执行不了写操作

`/trend` 读的是物化视图维护的 `notices_hourly`,不是明细表 —— 跨天按小时汇总在
明细表上要扫全部分区。

### 策略编辑

```bash
curl -u admin:<口令> -XPUT localhost:8080/api/v2/strategies/IP下单不支付 \
  -H 'Content-Type: application/json' \
  -d '{"definition": {...}, "expected_version": 2, "change_note": "阈值 100 调到 50"}'
```

**校验分两层。** 结构按 [`packages/domain-schema/strategy.schema.json`](../../packages/domain-schema/strategy.schema.json)
校验 —— 用同一份 schema 而不是在 Java 里另写一套字段检查,后者会随时间与 schema
分歧,而「同一个领域模型在两处各写一份、逐渐漂移」正是 1.x 最大的结构性问题。

引用则是 schema 管不了的那层:`counter.event` 指向的事件、`groupby` / `operand` /
`filter.object` 用到的字段都必须真实存在。`"event": "ORDER_SUBMITT"` 在结构上完全
合法,策略能保存、能上线,然后**永远不命中也永远不报错** —— 运营看到的只是「这条
策略没量」,查不出原因。这类静默失效比直接报错危险得多。

校验不通过时一次返回全部问题,不让人改一条再提交一次。

**`expected_version` 必填**(新建传 0)。两个人同时编辑同一条策略时,后提交的会
收到 409 而不是静默覆盖 —— 风控阈值被无声覆盖的代价是「昨天调好的今天没了,而且
没人知道」。

每次写入把改动后的完整定义存进 `strategy_revisions`。**回滚就是把某个旧版本重新
提交一次**,因此回滚本身也产生新版本,历史只增不改。没有历史时,「昨天这条策略
为什么突然报了十倍」查不出来:定义表里只有当前值,阈值被谁在什么时候从 100 改到
10 没有任何痕迹;审计日志记了「发生过一次修改」,但记不下改前改后的完整定义 ——
那是业务数据,不该塞进审计表。

### 元数据下发

引擎启动时带 `--console-url` 就从这里取事件、变量与策略,不再读本地 `seeds/`:

```bash
export NEBULA_CONSOLE_TOKEN='svc_xxx.yyy'   # 需要 metadata:read 作用域
flink run nebula-engine.jar --console-url http://console:8080 --brokers ...
```

此前控制面把策略写进 PostgreSQL、引擎从文件加载,**同一份领域模型有两个事实
来源**。运营改完策略引擎毫无察觉,而两边的分歧不会有任何报错 —— 1.x 就是这么
走过来的。现在数据库是唯一事实来源,`seeds/` 退回它本来的角色:首次导入的种子
数据。

`/bundle` 默认只下发 `online` 与 `test` 状态的策略。`inedit` 是没写完的草稿,
`outline` 是已下线的,把它们发给引擎等于让草稿直接影响线上判定。

**拉取失败即启动失败,不回落到本地文件。** 回落看起来更健壮,实际是最糟的结果:
作业带着一份不知多旧的策略跑起来,且没有任何迹象表明它没连上控制面。宁可起不来。

`/version` 单独提供是为了让引擎轮询一个整数而不是每次传全量。响应里的 `version`
先于内容读取 —— 反过来会得到「较新的版本号 + 偏旧的内容」,引擎认为自己已是最新,
改动永远不生效。

## 元数据为什么用 JSONB

策略与变量的权威结构在 [`packages/domain-schema/`](../../packages/domain-schema/) 的 JSON Schema 中。拆成关系表等于把同一套结构维护两遍 —— 那正是 1.x 领域模型漂移的根源(Python 的 `nebula_meta` 与 Java 的 `com.threathunter.variable` 各写一份,逐渐分歧)。

JSONB + GIN 索引既能按内容检索,又保持单一真相源。例如「找出全部引用了某变量的策略」:

```sql
SELECT name FROM strategies
WHERE definition @> '{"condition":{"conditions":[{"left":{"variable":"ip__visit_count__5m__rt"}}]}}';
```

1.x 把策略 JSON 存进 MySQL 的 blob 字段,这类查询做不到。

同时,高频过滤字段(状态、场景、敏感级别)提升为独立列并加了约束 —— 非法的 `category` 或 `status` 在库层就被拒绝,不依赖应用层自觉。

## 审计

全部写操作与个人信息查询都留痕。1.x 完全没有审计能力,而个保法要求对个人信息的处理活动可追溯。

**查询个人信息时只记录查询条件与命中量,不记录返回的数据本身** —— 把个人信息复制进审计日志会让问题更严重,而不是更安全。

审计表按月分区,保留 730 天,用分区裁剪而非 DELETE 清理(后者在大表上会长时间持锁)。
