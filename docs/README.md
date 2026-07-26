# 星云 Nebula 2.0 文档

> 🚧 项目处于早期开发阶段。标记为 🚧 的文档尚未完成。

## 从这里开始

| | |
|---|---|
| [核心概念:风控数据模型](concepts/data-model.md) | **建议第一篇阅读**。事件 → 变量 → 策略 → 名单四层模型 |
| [快速开始](guide/quickstart.md) | **2 分钟看到风控实际工作**,零依赖可实跑 |
| [系统架构](concepts/architecture.md) | 组件构成与数据流 |

## 使用

| | |
|---|---|
| [接入指南](guide/integration.md) | 数据源、字段映射、脱敏配置、`/checkRisk`、接入后验证 |
| [策略开发](guide/strategy.md) | 条件三形式、三维度镜像、阈值校准、生命周期 |
| [CEL 表达式参考](guide/cel-reference.md) | 已实现的函数与求值语义 |

## 参考

| | |
|---|---|
| [变量全表](reference/variables.md) | 253 个内置变量与 39 个画像变量详解(自动生成) |
| [算子语义规格](reference/operators.md) | **规范性文档**。每个算子的精确定义与 1.x 差异对照 |
| [类型推导规则](reference/type-inference.md) | 窗口 × 类型 × 算子的合法组合与输出类型 |
| [策略模板参考](reference/strategies.md) | 170 条内置策略(自动生成) |
| [API 参考](reference/api.md) | 全部接口、权限、错误码(手工维护,OpenAPI 生成 🚧) |
| [配置项](reference/configuration.md) | 三个组件的全部配置项与默认值 |

## 运维

| | |
|---|---|
| [部署](operations/deployment.md) | Lite 模式、凭据注入、暴露面、升级(Cluster 🚧) |
| [容量规划](operations/capacity.md) 🚧 | 按流量估算资源 |
| [监控告警](operations/monitoring.md) | 指标清单、建议告警项、排查顺序 |

## 安全与合规

| | |
|---|---|
| [隐私设计与合规](security/privacy.md) | 数据分级、脱敏、保留期、主体权利、法规对齐 |
| [威胁模型](security/threat-model.md) | 信任边界、攻击面、风控特有威胁、部署方检查清单 |

## 迁移与设计

| | |
|---|---|
| [从 1.x 迁移](migration/from-1x.md) | 资产迁移与 **6 处语义差异** |
| [架构决策记录](adr/) | 每个关键选型的推理与代价 |

## 开发与发布

| | |
|---|---|
| [贡献指南](../CONTRIBUTING.md) | 参与方式、提交要求、本地检查 |
| [行为准则](../CODE_OF_CONDUCT.md) | Contributor Covenant 2.1 |
| [更新日志](../CHANGELOG.md) | 按 Keep a Changelog 维护 |
| [路线图](development/roadmap.md) | 下一个版本做什么、为什么、代价与风险 |
| [发布流程](development/release-process.md) | 版本号规则、发布前检查清单、打 tag |
| [发布就绪度审查](development/release-readiness.md) | **诚实列出当前的不足**,含待处理项清单 |

---

## 文档约定

**图片一律存放在仓库内** `docs/assets/`,不使用任何外部图床。1.x 的文档把 30 多张架构图挂在微博图床上,防盗链启用后全部失效,文档因此基本不可读——这个错误不重复。

**参考类文档由代码或 schema 生成**,不手工维护。变量全表来自 `seeds/`,API 参考来自 OpenAPI,配置项参考来自配置定义。CI 校验生成结果与提交内容一致,防止文档与实现脱节。
