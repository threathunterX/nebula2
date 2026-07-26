# Lite 模式部署

单机运行星云 2.0 的依赖组件。面向本地评估与中小规模,资源占用约 6~8GB 内存。

## 前置条件

Docker(macOS 上如果没有 Docker Desktop,可用 [Colima](https://github.com/abiosoft/colima),免管理员权限):

```bash
brew install colima docker docker-compose
mkdir -p ~/.docker/cli-plugins
ln -sf "$(brew --prefix)/bin/docker-compose" ~/.docker/cli-plugins/docker-compose
colima start --cpu 4 --memory 6 --disk 40 --vm-type=vz
```

## 启动

```bash
cd deploy/compose
./gen-env.sh          # 生成 .env,凭据为随机值
docker compose up -d
docker compose ps     # 等四个组件都是 healthy
```

**零默认口令是硬性要求**:`gen-env.sh` 每次生成随机凭据,`.env` 权限 600 且已在 `.gitignore` 中。compose 文件里不含任何可用凭据 —— 缺少环境变量时会直接报错退出,而不是回落到默认值。

## 组件

| 服务 | 端口 | 用途 |
|---|---|---|
| Redpanda | 9092 | 消息总线(Kafka 协议兼容),兼作事件持久化与重放 |
| PostgreSQL | 5432 | 策略、变量、事件模型等元数据 |
| ClickHouse | 8123 / 9000 | 事件明细与聚合结果 |
| Redis | 6379 | 黑白名单与画像热层 |

选 Redpanda 而非 Kafka:单节点资源占用远低于 Kafka + ZooKeeper,协议兼容,Lite 模式下体验更好。Cluster 模式可换回 Kafka。

## 跑通端到端链路

```bash
# 1. 建主题
docker compose exec -T redpanda rpk topic create nebula.events nebula.notice --brokers localhost:29092

# 2. 采集器读原始日志,脱敏后写出
cd ../../apps/collector && go build -o nebula-collector ./cmd/nebula-collector
set -a; . ../../deploy/compose/.env; set +a
./nebula-collector -events ../../seeds/events < raw.jsonl > masked.jsonl

# 3. 灌入 Kafka
cd ../../deploy/compose
docker compose exec -T redpanda rpk topic produce nebula.events --brokers localhost:29092 < ../../apps/collector/masked.jsonl

# 4. 跑引擎作业
cd ../../apps/engine && mvn -q -DskipTests package
cd ../.. && java --add-opens java.base/java.util=ALL-UNNAMED \
  -cp "apps/engine/target/classes:$(cat /tmp/engine_cp.txt)" \
  cn.threathunter.nebula.engine.flink.NebulaJob \
  --brokers localhost:9092 --seeds seeds

# 5. 看告警
docker compose exec -T redpanda rpk topic consume nebula.notice --brokers localhost:29092 -o start -n 10 -f '%v\n'
```

## 已知限制

- **引擎并行度必须为 1**。变量按不同维度分组,一次 keyBy 无法同时满足,见 [engine README](../../apps/engine/README.md#并行化--这套架构最实质的工程难点)。
- **Flink 未容器化**。当前用宿主机 JVM 直接跑作业,尚未提供 JobManager / TaskManager 容器。
- **控制面与前端尚未实现**,因此 PostgreSQL 目前是空库 —— 它已就位但还没有表结构。
- **ClickHouse 尚未接入**,事件明细还没有写入链路。

## 清理

```bash
docker compose down          # 停止,保留数据
docker compose down -v       # 停止并删除数据卷
colima stop                  # 停止虚拟机
```
