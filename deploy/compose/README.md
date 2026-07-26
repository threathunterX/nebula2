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
# 0. 建表(幂等)
../schema/apply.sh

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

# 5. 看告警(Kafka)
docker compose exec -T redpanda rpk topic consume nebula.notice --brokers localhost:29092 -o start -n 10 -f '%v\n'

# 6. 查 ClickHouse(事件与告警都已落库)
set -a; . .env; set +a
curl -s "http://127.0.0.1:8123/?user=$CLICKHOUSE_USER&password=$CLICKHOUSE_PASSWORD" \
  --data-binary "SELECT c_ip, uniqMerge(uid_count) AS uids, countMerge(request_count) AS reqs
                 FROM nebula.events_hourly WHERE event_name='ACCOUNT_LOGIN'
                 GROUP BY c_ip HAVING uids > 3 FORMAT TSVWithNames"
```

## 已知限制

- **`NebulaJob` 固定单并行度**。变量按不同维度分组,一次 `keyBy` 无法同时满足。
  需要并行时用 `NebulaParallelJob`(按维度拆链路再汇聚,并行度 1/2/4 结果一致),
  见 [engine README](../../apps/engine/README.md#并行化)。
- **Flink 未容器化**。当前用宿主机 JVM 直接跑作业,尚未提供 JobManager / TaskManager 容器。
- **控制面与前端尚未实现**,因此 PostgreSQL 目前是空库 —— 它已就位但还没有表结构。
- **Flink 未容器化**,当前用宿主机 JVM 直接跑作业。

## 清理

```bash
docker compose down          # 停止,保留数据
docker compose down -v       # 停止并删除数据卷
colima stop                  # 停止虚拟机
```

## 起来之后是什么状态

`docker compose up -d` 会依次完成:建表 → 导入 170 条策略与 253 个变量 → 启动
控制面 → 启动 Flink 集群。首次启动的管理员口令只打印一次:

```bash
docker compose logs console-api | grep -A4 已创建初始管理员账号
```

种子导入是编排的一部分,不是「起来之后再手动跑一下」—— 库空着的时候控制面能
登录但什么也管不了,引擎拉到的 bundle 是空的,而这两者都不会报错。「起来了」
和「能用」应该是同一件事。

导入幂等(全部 `ON CONFLICT DO UPDATE`),重复 `up` 不会出问题。

## 提交引擎作业

infra 起来后,Flink 集群在 http://localhost:8081,作业 jar 已经在镜像的
`/opt/flink/usrlib/` 里。提交前需要一个 `metadata:read` 作用域的服务令牌:

```bash
curl -u admin:<首次启动打印的口令> -XPOST localhost:8080/api/v2/tokens \
  -H 'Content-Type: application/json' \
  -d '{"description":"计算引擎","scopes":["metadata:read"],"allowed_cidrs":["172.18.0.0/16"]}'
```

`allowed_cidrs` 要写 **compose 网络的网段**,不是 `127.0.0.1`。作业跑在容器里,
控制面看到的来源 IP 是容器网络地址;写成回环地址会一直 401,而错误信息(刻意)
不会告诉你是哪一步不对。查网段:

```bash
docker network inspect nebula_default -f '{{(index .IPAM.Config 0).Subnet}}'
```

然后提交:

```bash
docker compose exec -e NEBULA_CONSOLE_TOKEN='svc_xxx.yyy' jobmanager \
  flink run -d /opt/flink/usrlib/nebula-engine.jar \
    --console-url http://console-api:8080 \
    --brokers redpanda:29092 \
    --source-topic nebula.events --sink-topic nebula.notice
```

启动日志里会打印一行 `元数据来源: 控制面 http://console-api:8080 v6(事件 17 /
变量 253 / 策略 170)` —— 版本号对不上就是没拉到最新的。

## 几个踩过的坑

**引擎按 Java 17 编译,不是 21。** Flink 1.20 官方镜像最高只到 Java 17,用 21 编
出来的 class 文件在上面加载不了,而且报错发生在提交作业时而不是构建时。控制面是
独立进程,不受此限。

**连接器和 Jackson 都打进作业 jar。** Flink 发行版只含运行时:连接器是单独发布的
构件,Jackson 被重定位到了 `org.apache.flink.shaded.jackson2.*`。用 `provided` 时
本地测试(依赖都在 classpath 上)一路绿灯,提交到集群才 ClassNotFoundException。

**checkpoint 目录在镜像里就要建好并归 flink 所有。** 命名卷首次挂载会沿用镜像中该
路径的属主;镜像里没有就新建一个 root 的空目录,而 Flink 以 flink 用户运行 ——
作业能提交、能进 RUNNING,在第一次 checkpoint 时才失败。

**建表脚本打进镜像,不从宿主 bind mount。** bind mount 让「能不能起来」取决于仓库
在宿主上的位置和 Docker 的文件共享配置,失败表现为目录为空而不是报错。
