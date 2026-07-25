# collector —— 数据采集器

把各种来源的业务流量与日志还原成标准化事件,并**在数据离开客户网络边界之前完成敏感字段脱敏**,再交给下游。

Go 编写,零外部依赖,单二进制部署 —— 采集器要装到客户环境里,依赖越少越好。

## 状态

| 能力 | 状态 |
|---|---|
| 事件模型与单继承链 | ✅ |
| 脱敏引擎(drop / hash / partial / regex) | ✅ |
| 数据源:stdin、file、http | ✅ |
| 输出:stdout、file(JSON Lines) | ✅ |
| 运行指标与脱敏统计 | ✅ |
| 数据源:Kafka、syslog、Zeek 旁路 | 🚧 |
| 输出:Kafka | 🚧 |
| 配置热加载 | 🚧 |

## 快速试用

```bash
cd apps/collector
go build -o nebula-collector ./cmd/nebula-collector

echo '{"name":"ACCOUNT_LOGIN","timestamp":1784944800000,"c_ip":"198.51.100.1","uid":"alice","password":"hunter2","cookie":"sid=abc","result":"F"}' \
  | ./nebula-collector -events ../../seeds/events
```

输出:

```json
{"c_ip":"198.51.100.1","cookie":"<REDACTED>","name":"ACCOUNT_LOGIN","password":"<REDACTED>","result":"F","timestamp":1784944800000,"uid":"alice"}
```

口令和 Cookie 被脱敏,IP 和账号保留 —— 这不是疏漏,原因见下节。

## 脱敏:两条界限

这是采集器最重要的职责,也是最容易做错的地方。

### 界限一:`sensitive` 与 `pii` 的处理位置不同

| | `sensitive` | `pii` |
|---|---|---|
| 典型字段 | 口令、证件号、银行卡号、Cookie、请求体 | IP、账号、设备号、会话 ID |
| 采集端 | **就地脱敏,原文不出边界** | **保持原值** |
| 保护手段 | 丢弃 / 正则替换 / 部分掩码 | 存储层 HMAC 或加密列 |

**为什么 `pii` 不在采集端哈希**:风控引擎需要原值才能工作 —— IP 要做地理定位与信誉查询,账号与设备号要做跨维度关联(「这个设备上出现过几个账号」)。在采集端哈希会直接打断风控能力,而收益接近于零:数据仍在你自己的系统内流转,真正的风险在于**落库之后**的长期留存与访问。

> 这个判断是在实现过程中修正的。最初的默认规则把 `pii` 也设成了采集端 HMAC,跑通端到端后才意识到那样会让地理定位、IP 信誉、跨维度关联全部失效。

### 界限二:结构化字段用正则脱敏,不整体丢弃

`c_body` 与 `uri_query` 默认用正则替换敏感参数,而不是整条丢掉:

```
user=alice&password=hunter2&next=/home
        ↓
user=alice&password=<REDACTED>&next=/home
```

排查问题时 `user=alice`、`next=/home` 仍然可用。这是可用性与隐私之间刻意的折中,规则可按业务覆盖。

### 规则优先级

1. 按字段名的显式配置(`masking.fields`)
2. 事件模型中该字段声明的 `masking`
3. 按字段的 `sensitivity` 推导(见 `DefaultBySensitivity`)
4. 出厂高危字段规则(`cookie`、`c_body`、`s_body`、`uri_query`、`password`)—— 即便事件模型没标注也默认脱敏,宁可误脱不可漏脱

### 启动即失败,而非静默降级

- 脱敏正则无法编译 → 启动失败
- 配置了 `hash` 但没有 `NEBULA_HMAC_KEY` → 启动失败

**绝不静默降级为明文。**

## 用法

```bash
nebula-collector [选项]

  -config <path>        配置文件(JSON)
  -events <dir>         事件模型目录,用于按敏感级别脱敏
  -source stdin|file|http
  -source-path <path>   source=file 时的文件路径
  -source-addr <addr>   source=http 时的监听地址
  -out <path>           输出文件,缺省写 stdout
  -strict               事件类型不在模型中时丢弃
  -quiet                不输出运行摘要
  -version
```

HTTP 模式接受单条 JSON 或 JSON 数组:

```bash
nebula-collector -source-addr :8088 -events ../../seeds/events &
curl -XPOST localhost:8088/v2/events -d '{"name":"HTTP_DYNAMIC","c_ip":"198.51.100.1","page":"/"}'
```

健康检查在 `/healthz`。

## 配置

```json
{
  "source": { "type": "file", "path": "/var/log/app/access.jsonl" },
  "sink":   { "type": "file", "path": "/var/lib/nebula/events.jsonl" },
  "events_dir": "/etc/nebula/events",
  "default_event_name": "HTTP_DYNAMIC",
  "masking": {
    "fields": {
      "custom_token": { "action": "drop" },
      "member_phone": { "action": "partial", "keep": { "prefix": 3, "suffix": 4 } }
    }
  }
}
```

**凭据只从环境变量注入,配置文件里不允许出现任何可用凭据**:

| 变量 | 用途 |
|---|---|
| `NEBULA_HMAC_KEY` | HMAC 脱敏密钥 |
| `NEBULA_EVENTS_DIR` | 事件模型目录 |
| `NEBULA_SOURCE_TYPE` / `NEBULA_SOURCE_PATH` | 数据源 |
| `NEBULA_SINK_PATH` | 输出路径 |

## 开发

```bash
go build ./...
go test ./...
go test -cover ./...
go vet ./...
```

当前覆盖率:脱敏 90%、事件模型 82%、流水线 73%。

测试里有两条**隐私不变式**,改动脱敏逻辑时它们会兜底:

- `TestPipelineMasksBeforeWriting` —— 敏感原文不得出现在输出中
- `TestSeedFieldsAreClassified` —— 全部事件字段必须标注敏感级别,不允许依赖缺省

## 与 1.x sniffer 的差异

1.x 的采集器是 Python 2 写的,内嵌 Bro 2.6.1,支持 14 种驱动。2.0 重写的取舍:

- **改用 Go**:单二进制、无运行时依赖、内存可控,客户运维只需拷一个文件
- **脱敏系统化**:1.x 只在字段注释里写了「脱敏」,实际是否脱敏取决于各个 parser 的实现;2.0 由事件模型的敏感级别驱动,并有 CI 强制校验
- **不再提供 tshark HTTPS 解密**:密钥管理风险高,现代 TLS 配置下基本不可行
- **不内嵌 Zeek**:改为对接外部 Zeek 进程,避免把一个 C++ 项目的构建复杂度引入本仓库

1.x 的 `customparsers/` 下有 11 个以客户名命名的定制解析器,已在归档时移除。2.0 用配置驱动的字段映射取代按客户写代码的做法。
