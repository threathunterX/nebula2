# 监控

## 为什么这一节重要

**风控系统的失效往往是静默的。** 策略不再命中不会报错、不会中断,只是告警变少 ——
而「今天告警怎么少了」和「今天风险确实少」在没有指标时无法区分。

上游改了一个字段名、策略被误切到 `outline`、地理库挂了导致含位置判断的策略集体失效 ——
这些都不会产生任何异常日志。能发现它们的只有指标。

---

## 引擎

Flink 的指标体系,分组 `nebula`。通过 Flink Web UI(`/jobs/{id}/vertices/{vid}/metrics`)
或 Flink 的 metrics reporter 导出到 Prometheus。

| 指标 | 类型 | 怎么用 |
|---|---|---|
| `secondsSinceLastNotice` | Gauge | **最重要的一个。** 距上次产出告警的秒数,-1 表示从未产出。事件在进、告警不出是策略配错或字段变化最典型的表现 |
| `eventsIn` | Counter | 进入判定的事件数。停止增长 = 上游断了 |
| `eventsSkipped` | Counter | 因缺少 `name` 或 `timestamp` 被丢弃的事件。**上游字段改名会让全部事件落进这里,而链路看起来完全正常** |
| `noticesOut` | Counter | 产出的告警数 |
| `metadataVersion` | Gauge | 当前生效的元数据版本。排查「我改的策略生效了吗」第一个看它 |
| `metadataReloads` | Counter | 热更新次数 |
| `coldStartedVariables` | Counter | 累计冷启动的变量数。新引入的变量要经过一个完整窗口期才给出有意义的值 |

### 建议的告警项

```
# 事件在进但告警长时间不出 —— 最值得盯的一条
nebula_secondsSinceLastNotice > 3600 and rate(nebula_eventsIn[10m]) > 0

# 上游字段变化:被跳过的事件占比突然升高
rate(nebula_eventsSkipped[5m]) / rate(nebula_eventsIn[5m]) > 0.05

# 元数据版本长时间不变,而控制面上确实改过策略 —— 说明热更新链路断了
```

Flink 自带的 `numRecordsIn` / `numRecordsOut` / `currentEmitEventTimeLag` / checkpoint
成功率同样要看,它们反映的是管道健康而不是判定健康,两类都需要。

---

## 控制面

Prometheus 端点 `/actuator/prometheus`,由 Micrometer 提供。

> **这个端点需要认证。** 它会暴露请求量、错误率与各接口的调用分布 —— 也就是业务量级。
> 裸奔的 `/actuator/prometheus` 是一个免费的商业情报接口。抓取方用一个 `VIEWER`
> 账号即可。

主要看:

| 指标 | 怎么用 |
|---|---|
| `http_server_requests_seconds_count{uri="/checkRisk"}` | 业务系统的查询量。突然归零 = 接入方出问题了 |
| `http_server_requests_seconds{uri="/checkRisk",quantile="0.99"}` | `/checkRisk` 在业务的同步路径上,它的延迟直接影响下单/登录 |
| `http_server_requests_seconds_count{status="401"}` | 认证失败量。持续升高可能是撞库,也可能是某个接入方的令牌过期了 |
| `http_server_requests_seconds_count{status="429"}` | 触发登录锁定的次数 |
| `hikaricp_connections_pending` | 数据库连接池排队。持续 > 0 说明池子太小或有慢查询 |

`/actuator/health` 匿名可读,用于存活探针。

---

## 采集器

用 `-metrics-addr` 启用 Prometheus 端点:

```bash
nebula-collector -source http -source-addr 0.0.0.0:9000 \
                 -metrics-addr 127.0.0.1:9100 -events seeds/events
```

> **端口独立于接入端口是有意的。** 接入端口通常只对业务网段开放,而指标要给监控系统
> 抓 —— 合在一起意味着要么监控系统进不来,要么接入端口对监控网段敞开。

| 指标 | 类型 | 怎么用 |
|---|---|---|
| `nebula_collector_events_received_total` | Counter | 接收条数。停止增长 = 上游断了 |
| `nebula_collector_events_emitted_total` | Counter | 成功写出条数 |
| `nebula_collector_events_dropped_total` | Counter | 被丢弃条数(事件类型不在模型中等)。**上游改字段名时这个会涨,而链路本身看起来完全正常** |
| `nebula_collector_write_errors_total` | Counter | 写出失败次数 |
| `nebula_collector_mask_applied_total{field}` | Counter | 按字段的脱敏命中。**某个字段的命中数突然归零,通常意味着上游不再发这个字段了 —— 而那意味着它的原值可能正从别的字段流过去** |
| `nebula_collector_mask_errors_total` | Counter | 脱敏执行失败次数 |
| `nebula_collector_uptime_seconds` | Gauge | 已运行秒数,可用于发现反复重启 |

`/healthz` 用于存活探针。

> 端点由 Go 标准库实现,**没有引入 client_golang** —— 采集器的依赖面刻意保持很小,
> 而 Prometheus 的文本格式几十行就够。为这点格式引入一个需要长期跟踪 CVE 的依赖不划算。

---

## 排查顺序

告警变少时,按这个顺序倒推,每一步都能独立判断成败:

1. **事件还在进吗** —— `nebula_eventsIn` 是否还在增长;Kafka topic 水位是否还在涨
2. **事件被跳过了吗** —— `nebula_eventsSkipped` 占比是否突然升高(上游字段改名)
3. **引擎拿到的是哪版策略** —— `nebula_metadataVersion` 与控制面 `/api/v2/metadata/version` 是否一致
4. **策略状态对吗** —— 是否有人把策略切到了 `outline`;审计日志里能查到是谁在什么时候切的
5. **最后才怀疑阈值** —— 内置阈值来自 1.x 当年某个站点,本来就需要按自己的流量校准

**不要从第 5 步开始。** 调阈值能让告警重新出现,但如果真实原因是第 2 步,
调完之后你会得到一批错误的告警,而且再也不会去查真正的原因。

---

## 相关文档

| | |
|---|---|
| [部署](deployment.md) | 暴露面与端口 |
| [配置项参考](../reference/configuration.md) | 全部配置项 |
| [接入指南](../guide/integration.md) | 接入后的四步验证 |
