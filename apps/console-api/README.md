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
| 认证与授权(OIDC / RBAC) | 🚧 |
| 策略创建与编辑(需接 schema 校验) | 🚧 |
| 告警查询与报表(需接 ClickHouse) | 🚧 |

**当前没有认证**,不可暴露到可信网络之外。

## 运行

```bash
cd apps/console-api && mvn -q -DskipTests package
set -a; . ../../deploy/compose/.env; set +a
java -jar target/nebula-console-api-0.1.0-SNAPSHOT.jar
```

凭据只从环境变量注入,配置文件里不含任何可用凭据。缺少 `REDIS_PASSWORD` 时**启动即失败**,不会静默连接无密码实例。

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
| GET | `/api/v2/variables` | 变量列表,可按 `module` / `sensitivity` 过滤 |
| GET | `/api/v2/variables/{name}` | 变量完整定义 |

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
