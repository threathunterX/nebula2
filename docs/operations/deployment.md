# 部署

## 1. 两种形态

| | Lite | Cluster |
|---|---|---|
| 编排 | `docker compose` | Helm / Kubernetes(**🚧 未开始**) |
| 组件 | 全部单节点 | 多副本 |
| 高可用 | 无 | 有 |
| 适用 | 评估、开发、中小规模 | 生产 |

**当前只有 Lite 模式可用。** `deploy/helm/` 是空目录。本文如实说明这一点 —— 如果你需要
的是高可用生产部署,现在还不能用星云 2.0。

---

## 2. Lite 模式

完整步骤见 [`deploy/compose/README.md`](../../deploy/compose/README.md),这里只讲要点。

```bash
cd deploy/compose
./gen-env.sh          # 生成随机凭据到 .env(权限 600)
docker compose up -d
```

起来的顺序是有依赖的,不是随便排的:

```
postgres / clickhouse / redis / redpanda   (等 healthy)
        ↓
schema-init          建表,幂等,完成即退出
        ↓
seed-load            导入 17 事件 / 253 变量 / 170 策略,幂等
        ↓
console-api          控制面
        ↓
jobmanager / taskmanager
```

`console-api` 依赖 `seed-load` 的 `service_completed_successfully`,不是 `service_started`。
**理由**:库空着的时候控制面能启动、能通过健康检查、能登录,然后每个管理请求都返回空 ——
「服务健康但什么也管不了」是最难判断的一种状态,不如不让它起来。

### 资源

约 6–8 GB 内存。ClickHouse 和 Flink 是大头。Redpanda 已限制 `--memory=1G`。

### 数据卷

| 卷 | 内容 | 丢了会怎样 |
|---|---|---|
| `postgres-data` | 策略、变量、账号、审计 | **不可恢复**,策略改动全部丢失 |
| `clickhouse-data` | 事件明细与告警 | 历史数据丢失,不影响当前判定 |
| `redis-data` | 风险名单 | 名单清空,重新累积即可 |
| `redpanda-data` | 消息 | 未消费的事件丢失 |
| `flink-checkpoints` | 算子状态 | 窗口计数从零开始 |

`docker compose down -v` **会删除全部卷**。只想停服务用 `docker compose down`。

---

## 3. 凭据注入

**一律从环境变量注入,配置文件里不存在任何可用凭据。** 这是硬性要求,见
[SECURITY.md](../../SECURITY.md)。

| 方式 | 适用 |
|---|---|
| `.env` 文件 | Lite 模式。`gen-env.sh` 生成随机值,文件权限 600 且已被 `.gitignore` 排除 |
| 环境变量 | 直接注入容器 |
| Kubernetes Secret | Cluster 模式(🚧) |
| Vault 等 | 由外部注入为环境变量,星云不直接对接 |

缺凭据时的行为是**启动失败**,不是降级:控制面缺 `REDIS_PASSWORD` 直接起不来,不会静默
连接一个无密码实例。

### 首个管理员口令

```bash
docker compose logs console-api | grep -A4 已创建初始管理员账号
```

**只打印一次**,以 Argon2id 存储,之后任何地方都取不回明文。丢了只能直接改库中的哈希。

---

## 4. 引擎作业的提交

作业 jar 已在引擎镜像的 `/opt/flink/usrlib/` 里。提交需要一个 `metadata:read` 作用域的
服务令牌,且 `allowed_cidrs` 要写 **compose 网络的网段**而不是 `127.0.0.1` —— 作业跑在
容器里,控制面看到的来源 IP 是容器网络地址。

```bash
docker network inspect nebula_default -f '{{(index .IPAM.Config 0).Subnet}}'
```

完整命令见 [Lite 部署说明](../../deploy/compose/README.md#提交引擎作业)。

**策略改动需要重启作业才生效。** 引擎在启动时拉取一次元数据,热更新尚未实现。改完策略
后重新提交作业即可,Flink 的 savepoint 机制可以保留状态。

---

## 5. 暴露面

默认映射到宿主的端口:

| 端口 | 服务 | 是否应对外 |
|---|---|---|
| 8080 | 控制面 API | 视情况。**已有认证**,但仍建议限制来源 |
| 8081 | Flink Web UI | **不应对外**。无认证,能看到作业详情与部分数据 |
| 5432 / 8123 / 6379 / 9092 | 存储与消息 | **不应对外** |

Lite 模式的 `docker-compose.yml` 为了本地调试把这些端口都映射了出来。**放到任何非本机
环境前叠加生产覆盖文件:**

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

它关掉四个存储端口,并把 Flink Web UI 绑到回环。叠加之后确认一遍:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml config | grep published
```

只应剩下 8080(控制面)与 8081(Flink UI,绑在 `127.0.0.1`)。

> **为什么必须用 `!reset` 而不是 `ports: []`。** compose 对 ports 这类列表的合并规则
> 是**追加**:写 `ports: []` 什么也不会发生,写别的值只会多映射一个端口 —— 两种写法
> 都会让人以为端口关了,而数据库仍然对外可达。覆盖文件里用的是 `!reset`(需要
> Compose v2.24 以上)。

Flink Web UI 没有认证,这是 Flink 自身的默认行为,不是本项目的配置疏漏 —— 但结果一样,
所以单独提醒。

---

## 6. 升级

1. `git pull` 后重新 `docker compose build`
2. `docker compose up -d` —— `schema-init` 与 `seed-load` 都是幂等的,会自动补上新增的表
   与资产
3. 重新提交引擎作业

**注意**:`seed-load` 会用仓库里的种子覆盖同名策略。如果你在控制面改过内置策略,升级会
把改动覆盖掉。自定义策略请用新的名字,或者升级前先导出。

> 这是当前的一个粗糙之处 —— 「出厂资产」与「用户改动」没有分离。策略修订历史
> (`strategy_revisions`)保留了被覆盖前的版本,可以据此恢复。

---

## 相关文档

| | |
|---|---|
| [Lite 部署说明](../../deploy/compose/README.md) | compose 的完整步骤与踩过的坑 |
| [配置项参考](../reference/configuration.md) | 全部配置项 |
| [容量规划](capacity.md) | 资源估算 |
| [监控](monitoring.md) | 可观测性 |
| [SECURITY.md](../../SECURITY.md) | 凭据管理与安全设计 |
