# domain-schema —— 领域模型单一真相源

本目录用 JSON Schema 定义星云的全部领域模型。**它是整个项目的地基**:Java 与 TypeScript 的类型定义、数据库 DDL、文档中的字段参考、以及 CI 的一致性校验,全部由这些 schema 派生。

## 为什么需要它

Nebula 1.x 有一个贯穿始终的缺陷:同一套领域模型在 Python(`nebula_meta`)与 Java(`com.threathunter.variable`)各实现了一遍,两边逐渐漂移,最终**元数据层声明的能力远多于引擎实际实现的能力**。

具体表现:

- 配置层声明支持 `regex`、`in`、`startwith`、`<=`、`!=` 等条件算子,离线引擎实际只实现了 `contains`、`equals`、`notEquals`,其余直接抛 `NotSupportException`
- 声明支持 `max`、`range`、`amplitude` 等聚合算子,运行期同样未实现
- 结果是:用户在界面上配得出来的变量,引擎跑不了,而且失败发生在运行时而非配置时

2.0 用三条机制根除它:

1. **单一定义**:模型只在此处定义一次,各语言类型由代码生成产出,不允许手写
2. **声明即实现**:每个在 schema 中声明的算子,必须有对应实现与测试;CI 校验覆盖率必须为 100%,否则构建失败
3. **配置期校验**:策略与变量在保存时即完成类型推导与算子合法性校验,不把错误留到运行时

## 文件

| 文件 | 内容 |
|---|---|
| `enums.json` | 全部领域枚举 —— 决策、场景、名单类型、维度、窗口类型、值类型、敏感级别 |
| `event-model.schema.json` | 业务事件模型(含继承机制与字段敏感级别标注) |
| `variable-model.schema.json` | 统计变量定义(过滤、窗口、聚合函数) |
| `strategy.schema.json` | 风控策略(条件树、处置动作) |
| `notice.schema.json` | 风险告警输出结构 |

## 兼容性约定

以下内容与 1.x 保持**契约级兼容**,变更会破坏客户既有对接,必须走版本升级流程:

- `decision`、`scene`、`check_type` 三组枚举的取值
- 17 个内置事件的名称与 `HTTP_DYNAMIC` 的 30 个基础字段
- 变量命名规范 `{维度}__{语义}__{窗口}__{模块}`
- `/checkRisk` 的请求与响应结构

以下内容是 2.0 的**有意变更**,详见 [迁移指南](../../docs/migration/from-1x.md):

- 策略条件从"单层 AND"扩展为嵌套布尔树
- `distinct_count` 精度模式可选(1.x 为固定的混合近似方案)
- 新增字段敏感级别与脱敏声明
- 新增处置动作(handlers)与告警可解释性(explain)

## 代码生成

```bash
# 生成 Java 类型 → apps/engine, apps/console-api
make gen-java

# 生成 TypeScript 类型 → apps/console-web
make gen-ts

# 校验 seeds/ 下的全部资产是否符合 schema
make validate-seeds
```

> 🚧 生成脚本实现中。
