# 配置项参考

按组件列出全部配置项。**凡是凭据,一律只从环境变量注入**,配置文件里不存在任何可用
凭据的默认值或 fallback —— 见 [SECURITY.md](../../SECURITY.md)。

> 本文手工维护,与代码可能脱节。核对方式写在每节末尾,发现不一致以代码为准并请提 issue。

---

## 1. 凭据(全部组件共用)

`deploy/compose/gen-env.sh` 生成随机值写入 `.env`(权限 600,已被 `.gitignore` 排除)。

| 变量 | 用途 | 缺失时的行为 |
|---|---|---|
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | 元数据库 | 控制面**启动失败** |
| `CLICKHOUSE_USER` / `CLICKHOUSE_PASSWORD` | 明细与告警存储 | 引擎跳过落库;控制面告警接口返回 503 |
| `REDIS_PASSWORD` | 名单存储 | 控制面**启动失败**,不会静默连接无密码实例 |
| `NEBULA_HMAC_KEY` | 采集端 hash 脱敏的密钥 | 采集器**启动失败** |

**没有默认口令。** 首次启动时控制面若发现库中无任何账号,会生成一个随机口令的
`admin` 并**只打印一次**。

---

## 2. collector(采集器)

全部通过命令行参数配置,无配置文件时也能跑。

| 参数 | 默认 | 说明 |
|---|---|---|
| `-source` | `stdin` | 数据源:`stdin` / `file` / `http` |
| `-source-path` | — | `-source=file` 时的文件路径 |
| `-source-addr` | — | `-source=http` 时的监听地址,端点为 `/v2/events` |
| `-out` | stdout | 输出文件路径 |
| `-events` | — | 事件模型目录,**不给则无法按敏感级别脱敏** |
| `-config` | — | JSON 配置文件,用于自定义脱敏规则 |
| `-strict` | `false` | 事件类型不在模型中时丢弃整条,而不是丢弃未知字段 |
| `-quiet` | `false` | 不输出运行摘要 |

**`-events` 不是可选的**:不给它,采集器不知道哪些字段是 `sensitive`,脱敏退化为只对
出厂高危字段名生效。生产部署必须给。

配置文件中的脱敏规则见[接入指南 §3](../guide/integration.md)。

核对:`/tmp/nebula-collector --help`

---

## 3. engine(计算引擎)

**命令行参数**(非凭据):

| 参数 | 默认 | 说明 |
|---|---|---|
| `--brokers` | `localhost:9092` | Kafka / Redpanda 地址 |
| `--source-topic` | `nebula.events` | 事件输入 topic |
| `--sink-topic` | `nebula.notice` | 告警输出 topic |
| `--group` | `nebula-engine` | 消费组 |
| `--console-url` | — | 控制面地址。**给了就从控制面加载元数据**,不再读 `--seeds` |
| `--seeds` | `seeds` | 本地资产目录,仅在未给 `--console-url` 时使用 |

**环境变量**:

| 变量 | 默认 | 说明 |
|---|---|---|
| `NEBULA_CONSOLE_TOKEN` | — | 服务令牌,需 `metadata:read` 作用域。用 `--console-url` 时必填 |
| `CLICKHOUSE_URL` | `http://127.0.0.1:8123` | |
| `CLICKHOUSE_USER` / `CLICKHOUSE_PASSWORD` | — | 两者都给才落库,否则跳过 |
| `REDIS_HOST` / `REDIS_PORT` | `127.0.0.1` / `6379` | |
| `REDIS_PASSWORD` | — | 给了才写名单 |
| `NEBULA_HMAC_KEY` | — | 事件明细 pii 列的 HMAC 密钥。配了保护列却缺它时**启动失败** |
| `NEBULA_PII_HMAC_COLUMNS` | `uid,did,sid` | 做 HMAC 的列,逗号分隔。显式设为空串可关闭 |

**凭据不走命令行参数是刻意的**:`ps aux` 对同机所有用户可见,一个能读全部策略的令牌
出现在进程列表里等于没有保护。

Flink 自身的配置(并行度、状态后端、Checkpoint 间隔)走 Flink 的机制,compose 中用
`FLINK_PROPERTIES` 注入,见 `deploy/compose/docker-compose.yml`。

核对:`apps/engine/src/main/java/cn/threathunter/nebula/engine/flink/NebulaJob.java`

---

## 4. console-api(控制面)

配置在 `apps/console-api/src/main/resources/application.yml`,全部支持环境变量覆盖。

| 变量 | 默认 | 说明 |
|---|---|---|
| `NEBULA_CONSOLE_PORT` | `8080` | 监听端口 |
| `POSTGRES_HOST` / `POSTGRES_PORT` | `127.0.0.1` / `5432` | |
| `POSTGRES_DB` | `nebula` | |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | — | **必填** |
| `REDIS_HOST` / `REDIS_PORT` | `127.0.0.1` / `6379` | |
| `REDIS_PASSWORD` | — | **必填** |
| `NEBULA_CLICKHOUSE_URL` | `http://127.0.0.1:8123` | |
| `NEBULA_CLICKHOUSE_USER` / `_PASSWORD` | 回落到 `CLICKHOUSE_USER` / `_PASSWORD` | 不给则告警查询返回 503 |

只有 `/actuator/health` 匿名可读,其余全部需要认证。

核对:`grep -oE '\$\{[A-Z_]+' apps/console-api/src/main/resources/application.yml`

---

## 5. 影响数据保留与合规的项

这几项直接关系到你保留了多久的个人信息,**部署前必须按当地法规确认**:

| 位置 | 当前值 | 说明 |
|---|---|---|
| `deploy/schema/clickhouse/001_events.sql` | 事件明细 30 天 | 原始事件含 `pii` 字段 |
| `deploy/schema/clickhouse/002_notices.sql` | 告警 90 天 | 与告警查询接口的最大跨度一致 |
| `deploy/schema/clickhouse/003_hourly_rollup.sql` | 小时聚合 90 / 365 天 | 聚合后不含主体标识 |
| 名单 TTL | 策略的 `action.ttl` | 逐条策略配置,单位秒 |

改保留期要同时改 SQL 里的 `TTL` 与告警接口的 `MAX_RANGE`,否则会出现「接口允许查 90 天
但数据只留 30 天」这类沉默的不一致。

> **注意**:TTL 是按事件时间算的。灌入历史数据(时间戳很旧)时,数据会在 ClickHouse
> 后台合并时被清掉 —— 表现为「刚写进去、查得到,过一会儿就没了」。这在本地测试时撞到过。

详见[隐私设计与合规](../security/privacy.md)。

---

## 相关文档

| | |
|---|---|
| [Lite 部署](../../deploy/compose/README.md) | compose 的完整说明 |
| [部署](../operations/deployment.md) | 形态选择与凭据注入 |
| [SECURITY.md](../../SECURITY.md) | 凭据管理的硬性要求 |
