# Helm Chart

星云 2.0 的 Kubernetes 部署。**在真实 k3s 上装起来并跑通过**,不是只做了 `helm lint`。

```bash
kubectl create namespace nebula

helm install nebula ./nebula -n nebula \
  --set credentials.postgresPassword="$(openssl rand -hex 16)" \
  --set credentials.clickhousePassword="$(openssl rand -hex 16)" \
  --set credentials.redisPassword="$(openssl rand -hex 16)" \
  --set credentials.hmacKey="$(openssl rand -hex 24)"
```

口令**必须显式给**,chart 不会替你生成随机值 —— 理由见下方「口令为什么不自动生成」。
已有 Secret 时用 `--set credentials.existingSecret=<name>`。

---

## 验证过什么

在 Colima 起的 k3s(v1.35.0,单节点,6 CPU / 10 GiB)上实际安装:

| 检查 | 结果 |
|---|---|
| `helm install` | `STATUS: deployed` |
| 7 个 Pod | 全部 `Running` |
| `schema-init` / `seed-load` 两个 Job | 均 `Complete` |
| 控制面 `/actuator/health` | 200 |
| `/api/v2/stats` | 事件 17 / 变量 253 / 策略 170 / 标签 15 —— **出厂资产完整导入** |
| `/api/v2/strategies` | 返回真实策略名 |
| 口令未显式设置时 | `helm template` 直接失败并指出是哪个 |
| `existingSecret` 模式 | 不渲染 Secret,43 处引用指向给定名字 |

整套常驻资源(`kubectl top`):

| 组件 | CPU | 内存 |
|---|---|---|
| ClickHouse | 53m | 311Mi |
| TaskManager | 15m | 395Mi |
| JobManager | 16m | 311Mi |
| 控制面 | 9m | 249Mi |
| Redpanda | 9m | 230Mi |
| PostgreSQL | 2m | 90Mi |
| Redis | 7m | 3Mi |

合计约 **1.6 GiB**、110m CPU —— 空载状态,不含实际流量。

## 没验证过什么

**如实列出来,免得有人把上面的结果当成生产背书:**

- **多节点。** 只在单节点 k3s 上装过。跨节点调度、Pod 亲和性、跨可用区的存储都没测。
- **真实流量下的表现。** 装起来之后只验了接口可用,没有灌流量。容量数字见
  [容量规划](../../docs/operations/capacity.md),那是在裸机上测的。
- **升级路径。** 只做过全新安装与卸载重装,没做过 `helm upgrade` 的滚动更新,
  尤其是**存储组件带数据时的升级**。
- **Ingress / TLS。** chart 不含 Ingress。控制面的 Service 默认 `ClusterIP`,
  要从集群外访问需要自己加 Ingress 并终结 TLS。
- **备份与恢复。** 完全没有。

## 这份 chart 刻意不做的

不是遗漏,是取舍:

- **存储组件是单副本 StatefulSet,没有高可用。** 生产上应当换成各自的 Operator
  (CloudNativePG、Altinity ClickHouse Operator、Redpanda Operator)或托管服务 ——
  在 Kubernetes 里自己维护有状态集群是另一个量级的工作,不该由一份应用 chart 顺带承担。
- **引擎是 Flink 的 session 集群,不是 Flink Kubernetes Operator。** 没有作业级的
  自动恢复与滚动升级。
- **没有 NetworkPolicy。** 同命名空间内任意 Pod 都能连上数据库。

## 口令为什么不自动生成

很多 chart 会在没给口令时生成一个随机值。这里**刻意不这么做**:

Helm 每次 `upgrade` 都会重新渲染模板,自动生成的值会在升级时变掉,而数据库里的口令
不会跟着变 —— 结果是升级之后连不上。**这类故障只在升级时出现,首次安装完全正常**,
极难提前发现。

宁可让首次安装多一步显式设置。

## 本地镜像

四个镜像需要本地构建(`nebula/schema-init`、`nebula/seed-load`、`nebula/console-api`、
`nebula/engine`):

```bash
docker build -t nebula/schema-init:dev -f deploy/schema/Dockerfile deploy/schema
docker build -t nebula/seed-load:dev   -f deploy/seed/Dockerfile .
docker build -t nebula/console-api:dev -f apps/console-api/Dockerfile .
docker build -t nebula/engine:dev      -f apps/engine/Dockerfile .
```

Colima 的 k3s 用 Docker 作为运行时,本地构建完直接可用,不需要 `ctr images import`。
用 containerd 运行时的集群(标准 k3s、kind)需要额外导入或推到镜像仓库,
并把 `image.registry` 指过去。

## 装的时候踩到的两个坑

都是**真装才会发现**的,记在这里省得下次再撞:

1. **建表工具读的是 `CLICKHOUSE_URL`,不是 `NEBULA_CLICKHOUSE_URL`。** 后者是控制面用的。
   给错的表现是回落到 `127.0.0.1` 然后连接被拒 —— 而 PostgreSQL 那半段已经跑完了,
   看起来像「ClickHouse 挂了」。现在两个名字都给。

2. **ClickHouse 镜像靠 `CLICKHOUSE_DB` 建库。** 漏了它,建表阶段报
   `Database nebula does not exist`,同样容易被误读成 ClickHouse 有问题。

## 卸载

```bash
helm uninstall nebula -n nebula
kubectl -n nebula delete pvc --all     # PVC 不会随 uninstall 删除,这是刻意的
```

`helm uninstall` **不会删 PVC** —— 数据比一次误操作更重要。确实要清空时再执行第二条。
