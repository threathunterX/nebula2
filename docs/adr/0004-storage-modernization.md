# ADR-0004:用 ClickHouse 取代自研存储格式

- **状态**:已接受
- **日期**:2026-07

## 背景

1.x 的存储层几乎全部自研:

- **事件日志**:自定义二进制格式,按小时目录分 16 片,配 LevelDB 做索引(`greatdant` 组件),含手写的 `BufferedRandomAccessFile`、`ShardFile`、`IndexWriter/IndexReader`
- **小时聚合结果**:每小时一个独立的 LevelDB 库,key 编码为 `{1字节维度前缀}{key字节}`
- **画像数据**:Aerospike
- **元数据与告警**:MySQL 5.6

这套组合带来的问题:

- "查最近 N 小时"需要逐小时打开 LevelDB 再在应用层合并,而合并逻辑对去重集合只做 `addAll` 不去重,结果系统性偏高
- 磁盘清理靠 crontab 脚本,其中一条规则是磁盘超 80% 就删最旧的 5 个目录,会静默删除未过期数据
- Aerospike 的企业版授权与运维复杂度,对私有化部署是实际负担
- 自研格式没有 schema 演进能力,加字段要改解析代码

## 决策

| 用途 | 1.x | 2.0 |
|---|---|---|
| 事件明细 | 自研二进制 + LevelDB 索引 | **ClickHouse** |
| 小时聚合 | 每小时一个 LevelDB | **ClickHouse 物化视图** |
| 画像 | Aerospike | **Redis/Valkey(热)+ ClickHouse(冷)** |
| 元数据 | MySQL 5.6 | **PostgreSQL 16** |
| 名单 | MySQL + Redis | **Redis/Valkey** |
| 消息与重放 | 自研 babel(Redis pub/sub 或 RabbitMQ) | **Kafka / Redpanda** |

## 理由

**ClickHouse 一次解决四个问题**:列存与高压缩比适合事件明细;物化视图天然表达小时聚合;原生 TTL 按分区自动过期,替代危险的清理脚本;SQL 查询取代自研的查询任务框架与 CSV 导出。`greatdant` 整个组件因此不再需要。

**PostgreSQL 的 JSONB** 适合策略与变量这类文档型数据。1.x 把策略 JSON 存进 MySQL 的 blob 字段,无法索引、无法在数据库层校验;JSONB + GIN 索引可以直接按策略内容检索。`notice` 表用原生分区替代手写的过期清理任务。

**Kafka 兼作消息总线与事件持久化**。1.x 需要 babel 传消息、再单独把事件落盘一份用于离线重放;Kafka 的 retention 天然提供重放能力,两件事合一。

## 代价

组件数量从"MySQL + Redis + Aerospike"变为"PostgreSQL + Redis + ClickHouse + Kafka",Lite 模式下的内存占用上升。通过 compose 编排与合理的默认配置把单机资源需求控制在约 8GB。
