# 星云 Nebula 2.0

> **业务风控系统 · 开源版**
> 实时识别撞库、盗号、恶意注册、刷单、薅羊毛、爬虫等业务风险。

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/status-开发中%20WIP-orange.svg)](#项目状态)

---

## ⚠️ 项目状态

**本项目处于早期开发阶段,尚未发布可用版本。** 当前仓库包含:架构设计、领域模型定义(JSON Schema)、从 1.x 继承的风控资产(事件/变量/策略模板),以及文档体系。核心引擎正在实现中。

如果你现在就需要一个能跑的版本,请使用 [Nebula 1.x](https://github.com/threathunterX/nebula) —— 但请注意它已于 2019 年停止维护,依赖的技术栈(Python 2、OpenResty 1.11、Esper 6)均已 EOL,**不建议用于生产环境**。

---

## 这是什么

星云是一套**业务风控**系统 —— 注意不是 WAF,也不是主机安全。它关心的不是"这个请求有没有 SQL 注入",而是:

- 同一个 IP 在 10 分钟内登录失败了 50 次 → 撞库
- 一个账号同时在 5 个城市登录 → 盗号
- 500 个新注册账号用了同一个设备指纹 → 批量注册
- 一批订单下单后从不支付 → 恶意占库存
- 活动开始 3 秒内被领走 80% 的券 → 薅羊毛

它通过**旁路采集**业务流量或日志,还原成标准化的业务事件,在事件流上做实时统计与策略判定,产出风险名单和处置决策,再由业务系统消费。

### 核心特点

| 特点 | 说明 |
|---|---|
| **无埋点接入** | 旁路镜像流量或消费已有日志即可,不要求业务系统改代码 |
| **开箱即用的风控知识** | 内置 17 类业务事件、253 个统计变量、170 条策略模板,覆盖访客/账号/支付/订单/营销五大场景 |
| **策略热生效** | 可视化编辑,保存即生效,无需重启或重新编译 |
| **两种部署形态** | Lite 单机模式一条命令起;Cluster 模式支撑大流量,同一份代码 |
| **隐私优先** | 敏感字段在采集端就地脱敏,标识符加密存储,保留期自动执行 |

---

## 架构

```
                    ┌──────────────── 控制面 ─────────────────┐
                    │  console-web    策略/变量/名单/报表 UI   │
                    │  console-api    元数据管理 · RBAC · 审计 │
                    └───────┬────────────────────┬────────────┘
                            │ 元数据下发          │ 查询
                            ▼                    ▼
业务流量/日志           ┌──────────┐        ┌──────────────┐
   │                   │PostgreSQL│        │  ClickHouse  │
   ▼                   │  元数据   │        │ 事件+聚合结果 │
┌───────────┐  Kafka   └──────────┘        └──────▲───────┘
│ collector │────┐                                 │
│   (Go)    │    │    ┌─────────────────────────┐  │
└───────────┘    ├───▶│      Flink 计算作业      │──┘
 SDK / HTTP ─────┤    │  ① 变量引擎(DAG 算子)  │
 Kafka / syslog ─┤    │  ② 规则引擎(CEL + CEP) │──▶ Kafka: notice
 Zeek(可选)─────┘    │  ③ 画像更新              │        │
                      └─────────────────────────┘        ▼
                                                  Redis 名单 + 处置动作
                                                         │
                                            /checkRisk ◀─┘  业务系统同步查询
```

### 技术选型

| 层 | 选型 | 说明 |
|---|---|---|
| 采集 | **Go** 单二进制 | 支持 Kafka / syslog / 文件 / HTTP / OpenResty Lua / Zeek 旁路 |
| 计算 | **Apache Flink** | 事件时间语义、精确一次、状态可恢复;统一了 1.x 的实时与离线两套引擎 |
| 规则表达式 | **CEL** | 类型安全、沙箱执行、跨语言实现一致 |
| 消息 | **Kafka / Redpanda** | 兼作事件持久化与重放 |
| 分析存储 | **ClickHouse** | 事件明细 + 小时聚合 + TTL 自动过期 |
| 元数据 | **PostgreSQL 16** | 策略/变量以 JSONB 存储 |
| 名单/热状态 | **Redis / Valkey** | 黑白名单 TTL |
| 控制面 | **Java 21 + Spring Boot 3** | 与计算层共享领域模型 |
| 前端 | **React 19 + TypeScript + ECharts** | |
| 可观测 | **OpenTelemetry + Prometheus + Grafana** | 使用官方组件,不做魔改 |

设计取舍的完整推理见 [架构决策记录(ADR)](docs/adr/)。

---

## 仓库结构

```
nebula2/
├── apps/
│   ├── collector/       # Go 采集器
│   ├── engine/          # Flink 计算作业(变量引擎 + 规则引擎)
│   ├── console-api/     # 控制面 API(Spring Boot 3)
│   └── console-web/     # 管理界面(React 19 + TS)
├── packages/
│   ├── domain-schema/   # 领域模型 JSON Schema —— 单一真相源
│   └── cel-functions/   # CEL 业务扩展函数
├── seeds/               # 内置风控资产:事件/变量/策略/标签
├── deploy/              # compose(Lite) / helm(Cluster)
├── docs/                # 文档站
├── tests/golden/        # 新旧引擎语义回归测试
└── tools/               # 迁移与开发工具
```

**`packages/domain-schema` 是整个项目的地基。** 事件、变量、策略的结构由 JSON Schema 定义,Java 与 TypeScript 类型均由它生成,文档中的字段参考也由它生成。这样做是为了根除 1.x 的一个顽疾:元数据层声明的能力远多于引擎实际实现的,用户在界面上配得出来的变量,引擎跑不了。2.0 要求**声明即实现**,CI 强制校验。

---

## 内置风控资产

从 1.x 完整继承并经过审计的风控知识沉淀,位于 [`seeds/`](seeds/):

| 资产 | 数量 | 说明 |
|---|---|---|
| 事件模型 | 17 | 以 `HTTP_DYNAMIC`(30 个基础字段)为根,继承出登录/注册/改密/下单/支付/活动等业务事件 |
| 统计变量 | 253 | 四层窗口:5 分钟实时、1 小时槽、当天、长期画像 |
| 策略模板 | 170 | 订单 70 / 账号 60 / 访客 40,按 IP、设备、账号三维度镜像设计 |
| 风险标签 | 15 | 撞库、盗号、刷单、羊毛党、恶意扫描、爬虫等 |

> **数据来源声明**:这些资产源自 1.x 的出厂配置。迁移过程中已剔除全部真实客户与个人信息,所有示例数据均为合成数据。详见 [`seeds/INVENTORY.md`](seeds/INVENTORY.md)。

---

## 快速开始

> 🚧 引擎实现中,以下命令暂不可用。文档先行,便于评审接口设计。

```bash
git clone https://github.com/threathunterX/nebula2.git
cd nebula2/deploy/compose
docker compose up -d
```

访问 `http://localhost:8080`,用初始化时打印的随机管理员口令登录。系统会自动导入内置资产并生成合成演示流量,约 1 分钟后即可在告警页看到第一条风险事件。

完整步骤见 [快速开始](docs/guide/quickstart.md)。

---

## 文档

| | |
|---|---|
| [风控数据模型](docs/concepts/data-model.md) | **建议第一篇**。事件 → 变量 → 策略 → 名单 |
| [系统架构](docs/concepts/architecture.md) | 组件职责、数据流、两种部署形态 |
| [算子语义规格](docs/reference/operators.md) | **规范性文档**。每个算子的精确定义与 1.x 差异对照 |
| [类型推导规则](docs/reference/type-inference.md) | 窗口 × 类型 × 算子的合法组合 |
| [变量参考](docs/reference/variables.md) | 253 个内置变量 + 画像变量详解(自动生成) |
| [策略模板参考](docs/reference/strategies.md) | 170 条内置策略逐条说明(自动生成) |
| [隐私设计与合规](docs/security/privacy.md) | 数据分级、脱敏、保留期、主体权利、法规对齐 |
| [1.x → 2.0 迁移](docs/migration/from-1x.md) | 资产迁移与 **6 处语义差异** |
| [架构决策记录](docs/adr/) | 每个关键选型背后的推理与代价 |
| [Golden 回归测试](tests/golden/) | 如何证明语义继承是正确的 |
| [全部文档](docs/) | 文档索引(含尚未编写文档的提纲) |

---

## 与 Nebula 1.x 的关系

本项目是 [Nebula 1.x](https://github.com/threathunterX/nebula) 的**重写版本**,不是升级版。

1.x 于 2019 年开源(1000+ star),2019 年后停止维护。它验证了产品形态并沉淀了大量风控知识,但技术栈已整体 EOL,且存在架构层面的历史包袱(自研 RPC/序列化/存储格式、实时与离线两套语义不一致的引擎、Python 2 与 Java 双份领域模型漂移)。

2.0 采用全新代码库重写,**只继承领域资产,不继承代码**:

- ✅ 继承:事件模型、变量定义、策略模板、风险标签、`/checkRisk` 对外契约、变量命名规范
- ❌ 不继承:全部实现代码、自研基础设施、存储格式

从 1.x 迁移时有 **6 处语义差异**必须了解(去重计数精度、标准差定义、离线重算取消等),详见 [迁移指南](docs/migration/from-1x.md)。

---

## 参与贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)。安全问题请按 [SECURITY.md](SECURITY.md) 私下报告,不要提交公开 issue。

## 许可证

[Apache License 2.0](LICENSE)
