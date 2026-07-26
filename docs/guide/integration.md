# 接入指南

如何把你自己的业务流量接进星云,以及业务系统如何查询风险结论。

> **先说清楚当前能接什么。** 采集器支持 stdin / file / http 三种数据源,输出到 stdout
> 或文件。**Kafka、syslog、Zeek 旁路数据源与 Kafka 输出尚未实现**(见
> [采集器状态](../../apps/collector/#状态))。也就是说,今天的接入链路是
> `你的数据源 → 采集器 → stdout → 你的投递方式 → Kafka`,中间那一段需要你自己接。

---

> 下文命令都在**仓库根目录**执行,采集器已编译到 `/tmp/nebula-collector`:
>
> ```bash
> go build -o /tmp/nebula-collector ./apps/collector/cmd/nebula-collector
> ```

## 1. 数据进来:三种方式

### 1.1 HTTP 上报(最通用)

采集器起一个 HTTP 端点,你的应用或日志代理往里 POST 事件。

```bash
/tmp/nebula-collector \
  -source http -source-addr 127.0.0.1:9000 \
  -events seeds/events \
  -out /var/log/nebula/events.ndjson
```

上报到 `/v2/events`(`/healthz` 是存活检查):

```bash
curl -XPOST localhost:9000/v2/events -H 'Content-Type: application/json' -d '{
  "name": "ACCOUNT_LOGIN",
  "timestamp": 1784944800000,
  "c_ip": "198.51.100.1",
  "uid": "alice",
  "did": "device-abc",
  "page": "/api/login",
  "result": "F"
}'
```

每行一个 JSON 对象,也接受 NDJSON 批量上报。

### 1.2 文件跟随

已有日志文件时最省事:

```bash
/tmp/nebula-collector -source file -source-path /var/log/app/access.ndjson \
  -events seeds/events
```

### 1.3 标准输入

管道接任何东西,适合从既有工具链改造:

```bash
tail -F /var/log/nginx/access.ndjson | /tmp/nebula-collector -events seeds/events
```

### 1.4 送进 Kafka

**采集器的 Kafka 输出尚未实现。** 当前它写 stdout 或文件,你需要自己投递。评估阶段最
简单的做法是直接管到 `rpk`:

```bash
/tmp/nebula-collector -source file -source-path /var/log/app/access.ndjson \
                      -events seeds/events \
  | docker compose exec -T redpanda rpk topic produce nebula.events
```

生产环境请用 Vector / Filebeat / Fluent Bit 之类成熟的投递组件,不要靠管道——管道断了
不会有人告诉你。

---

## 2. 字段怎么对上

事件模型定义在 [`seeds/events/`](../../seeds/events/),17 个事件以 `HTTP_DYNAMIC` 为根
构成单继承链。上报 JSON 里的 `name` 决定按哪个模型解析。

**必填字段只有两个**:`name` 和 `timestamp`(毫秒时间戳)。缺任何一个都会被引擎丢弃。

其余字段按事件模型的 `properties` 匹配。几个跨事件通用的:

| 字段 | 含义 | 为什么重要 |
|---|---|---|
| `c_ip` | 客户端 IP | 大量策略按 IP 维度聚合 |
| `uid` | 业务账号标识 | 账号维度 |
| `did` | 设备标识 | 设备维度;没有设备指纹时可留空,相关策略自然不命中 |
| `page` / `uri_stem` | 请求路径 | 多条策略用它区分登录、下单、支付 |
| `result` | 业务结果 | 登录类事件用 `F` 表示失败,撞库检测依赖它 |

**模型里没有的字段会被丢弃**(加 `-strict` 时整条事件被丢弃)。要加自己的业务字段,
见 §5。

查某个事件有哪些字段:

```bash
python3 -c "
import json;d=json.load(open('seeds/events/ACCOUNT_LOGIN.json'))
print([p['name'] for p in d['properties']])"
```

注意子事件会继承父事件的字段:`ACCOUNT_LOGIN` 自身只定义登录相关字段,`c_ip`、`page`
这些来自 `HTTP_DYNAMIC`。

---

## 3. 脱敏:接入前必须先看

**采集器在数据离开你的网络边界之前完成脱敏。** 这不是可选项,默认就开着。

两条界限(完整论证见[采集器文档](../../apps/collector/)与[隐私设计](../security/privacy.md)):

- `sensitive`(口令、Cookie、证件号等 39 个字段)—— **采集端就地脱敏**,原文不出边界
- `pii`(IP、账号、订单号等 47 个字段)—— **采集端保持原值**,保护发生在存储层

`pii` 不在采集端哈希是有原因的:`c_ip` 一旦被哈希,地理定位、IP 信誉、跨维度关联全部
失效,风控直接废掉。最初的实现搞错了这一点,端到端跑通才发现。

自定义规则:

```json
{
  "masking": {
    "rules": [
      {"field": "receiver_mobile", "action": "partial", "keep_prefix": 3, "keep_suffix": 4},
      {"field": "internal_token",  "action": "drop"},
      {"field": "user_name",       "action": "hash"}
    ]
  }
}
```

```bash
/tmp/nebula-collector -config masking.json -events seeds/events
```

优先级:显式配置 > 字段声明 > 敏感级别推导 > 出厂高危字段规则。

**接入自定义字段后一定要重新评估脱敏。** 出厂规则只覆盖内置模型里的字段,它预知不了
你新加的 `id_card_no`。

验证脱敏确实生效:

```bash
echo '{"name":"ACCOUNT_LOGIN","timestamp":1784944800000,"c_ip":"198.51.100.1","uid":"alice","password":"hunter2"}' \
  | /tmp/nebula-collector -events seeds/events
# password 应为 <REDACTED>,c_ip 与 uid 应保持原值
```

---

## 4. 业务系统查询风险:`/checkRisk`

这是对外契约,**与 1.x 保持兼容**,原有接入代码无需改动。

先签发一个服务令牌(需要管理员账号):

```bash
curl -u admin:<口令> -XPOST localhost:8080/api/v2/tokens \
  -H 'Content-Type: application/json' \
  -d '{"description":"订单系统","scopes":["checkRisk"],"allowed_cidrs":["10.1.0.0/16"]}'
```

响应里的 `token` **只出现这一次**。`allowed_cidrs` 与令牌是 AND 关系:令牌正确但来源 IP
不在网段内一样拒绝。

查询:

```bash
curl -XPOST localhost:8080/checkRisk \
  -H "Authorization: Bearer svc_xxx.yyy" \
  -H 'Content-Type: application/json' \
  -d '{"check_item":[{"type":"IP","value":"198.51.100.77"}],"scene_type":"ACCOUNT"}'
```

```json
{"success": true, "final_decision": "review", "rule_hits": []}
```

多条名单命中时 `final_decision` 取**最严格**的:`reject` > `review` > `accept`。

这条路径直接查 Redis,不经过数据库、不做复杂计算。名单失效由 Redis TTL 自动完成。

> 服务令牌**只能**调 `/checkRisk`,碰不到任何管理接口;反过来管理员账号也调不了
> `/checkRisk`。1.x 把两者混在一起,拿到 token 既能查风险也能改配置。

---

## 5. 加自己的业务字段

内置的 17 个事件覆盖登录 / 注册 / 下单 / 支付 / 营销,但你的业务一定有它们没有的字段。

1. 复制一个最接近的事件模型,或在 `source` 里继承它
2. 在 `properties` 中加字段,**每个字段必须声明 `sensitivity`** —— 这条由测试强制,漏标
   会导致校验失败(这是刻意的:1.x 的 184 个字段一个都没标)
3. 跑校验:`make validate-seeds`
4. 导入:`docker compose run --rm seed-load`

```json
{
  "name": "ORDER_SUBMIT",
  "source": [{"app": "nebula", "name": "HTTP_DYNAMIC"}],
  "properties": [
    {"name": "coupon_code", "type": "string",
     "sensitivity": "internal", "visible_name": "优惠券码", "remark": "用于薅羊毛检测"}
  ]
}
```

`sensitivity` 四档:`public` / `internal` / `pii` / `sensitive`,判断标准见
[隐私设计](../security/privacy.md)。**拿不准时选更严的那一档** —— 多脱敏一个字段的代价
是少一个可用特征,少脱敏一个的代价可能是一次通报。

---

## 6. 接入后怎么验证

按顺序确认四件事,每一步都能独立判断成败。

**① 事件进得来**

```bash
docker compose exec redpanda rpk topic describe nebula.events -p
```

`HIGH-WATERMARK` 在涨就说明有数据。

**② 脱敏生效了**

```bash
docker compose exec redpanda rpk topic consume nebula.events --num 20 -f '%v\n' \
  | grep -c REDACTED
```

同时确认原文没漏出去 —— 把你环境里真实的敏感值当关键词搜一遍,结果必须是 0。

**③ 引擎在算**

Flink 界面 http://localhost:8081,看作业是不是 `RUNNING`、`Records Received` 有没有涨。
提交作业时打印的那行 `元数据来源: 控制面 ... v6(事件 17 / 变量 253 / 策略 170)` 能确认
它拿到了哪一版策略。

**④ 告警出得来**

```bash
curl -u admin:<口令> -G localhost:8080/api/v2/alerts \
  --data-urlencode "from=$(date -u -v-1H '+%Y-%m-%dT%H:%M:%SZ')" \
  --data-urlencode "to=$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
  --data-urlencode 'include_test=true'
```

**接入初期告警为 0 是正常的**,别急着判断没接通。内置策略以 `test` 状态分发,阈值来自
1.x 当年某个站点的经验值,你的流量特征不一样。先确认 ①②③ 都通了,再按自己的量级校准
阈值。倒推顺序很重要 —— 不要一上来就怀疑策略。

---

## 7. 尚未支持的接入方式

以下都在计划中,**当前没有实现**,不要按它们做架构设计:

| 方式 | 说明 |
|---|---|
| Zeek 旁路镜像流量 | 1.x 的 `sniffer` 承担这个角色,2.0 尚未重写 |
| Nginx / OpenResty 直采 | 1.x 有 Lua 模块,2.0 尚未重写 |
| Kafka 数据源 | 消费业务方已有的日志流 |
| syslog 数据源 | 传统日志接入 |
| 采集器输出到 Kafka | 见 §1.4 的临时办法 |

---

## 相关文档

| | |
|---|---|
| [采集器](../../apps/collector/) | 脱敏引擎的完整说明 |
| [隐私设计与合规](../security/privacy.md) | 敏感级别的判定标准 |
| [控制面 API](../../apps/console-api/) | 认证、告警查询、策略编辑 |
| [Lite 部署](../../deploy/compose/README.md) | 起完整系统 |
| [风控数据模型](../concepts/data-model.md) | 事件、变量、策略、名单四层 |
