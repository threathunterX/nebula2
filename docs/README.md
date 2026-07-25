# 星云 Nebula 2.0 文档

> 🚧 项目处于早期开发阶段。标记为 🚧 的文档尚未完成。

## 从这里开始

| | |
|---|---|
| [核心概念:风控数据模型](concepts/data-model.md) | **建议第一篇阅读**。事件 → 变量 → 策略 → 名单四层模型 |
| [快速开始](guide/quickstart.md) 🚧 | 5 分钟跑通第一条告警 |
| [系统架构](concepts/architecture.md) | 组件构成与数据流 |

## 使用

| | |
|---|---|
| [接入指南](guide/integration.md) 🚧 | 旁路流量、Nginx 日志、Kafka、SDK 埋点、同步查询 |
| [策略开发](guide/strategy.md) 🚧 | 内置模板详解、自定义策略 |
| [CEL 表达式参考](guide/cel-reference.md) 🚧 | 语法、内置函数、示例 |

## 参考

| | |
|---|---|
| [变量全表](reference/variables.md) | 253 个内置变量与 39 个画像变量详解(自动生成) |
| [算子语义规格](reference/operators.md) | **规范性文档**。每个算子的精确定义与 1.x 差异对照 |
| [类型推导规则](reference/type-inference.md) | 窗口 × 类型 × 算子的合法组合与输出类型 |
| [策略模板参考](reference/strategies.md) | 170 条内置策略(自动生成) |
| [API 参考](reference/api.md) 🚧 | OpenAPI(自动生成) |
| [配置项](reference/configuration.md) 🚧 | 全部配置项及默认值 |

## 运维

| | |
|---|---|
| [部署](operations/deployment.md) 🚧 | Lite 单机 / Cluster 集群 |
| [容量规划](operations/capacity.md) 🚧 | 按流量估算资源 |
| [监控告警](operations/monitoring.md) 🚧 | 指标、告警项、故障排查 |

## 安全与合规

| | |
|---|---|
| [隐私设计与合规](security/privacy.md) | 数据分级、脱敏、保留期、主体权利、法规对齐 |
| [威胁模型](security/threat-model.md) 🚧 | 攻击面分析与加固清单 |

## 迁移与设计

| | |
|---|---|
| [从 1.x 迁移](migration/from-1x.md) | 资产迁移与 **6 处语义差异** |
| [架构决策记录](adr/) | 每个关键选型的推理与代价 |

---

## 文档约定

**图片一律存放在仓库内** `docs/assets/`,不使用任何外部图床。1.x 的文档把 30 多张架构图挂在微博图床上,防盗链启用后全部失效,文档因此基本不可读——这个错误不重复。

**参考类文档由代码或 schema 生成**,不手工维护。变量全表来自 `seeds/`,API 参考来自 OpenAPI,配置项参考来自配置定义。CI 校验生成结果与提交内容一致,防止文档与实现脱节。
