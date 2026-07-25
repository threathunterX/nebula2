# ADR-0003:用 CEL 取代自研 SPL 表达式语言

- **状态**:已接受
- **日期**:2026-07

## 背景

1.x 在策略条款中提供了一种叫 SPL 的表达式语言,基于开源的 IK Expression 2.0(手写词法 + 逆波兰求值)。

实际状态是半成品:

- 只有一个真正可用的业务函数 `$CHECKNOTICE(keyType, keyValue, strategyName, intervalSec)`,语义是"过去 N 秒内该主体是否命中过某策略",用于策略级联
- 其余内置函数只有 `$CONTAINS` / `$STARTSWITH` / `$ENDSWITH` / `$CALCDATE` / `$SYSDATE` / `$DAYEQUALS` 等通用工具函数
- 独立执行器 `SplExecutor.run()` 方法体为空,从未实现
- Python 侧完全不做校验(`_check_spl_expression_and_return_type` 直接返回空串),错误只能在运行时暴露
- 全部 5 章官方文档中,没有一个字提到 SPL

也就是说,维护一门自研语言的成本已经付出,但收益几乎为零。

## 决策

**采用 CEL(Common Expression Language)作为策略表达式语言。**

## 理由

| 维度 | 自研 SPL | CEL |
|---|---|---|
| 许可 | 依赖 IK Expression | Apache-2.0 |
| 类型安全 | 无静态类型检查 | 编译期类型检查 |
| 求值安全 | 无执行上限,坏表达式可打挂引擎 | 沙箱执行,保证终止,有确定的求值代价上限 |
| 跨语言 | 仅 Java 实现 | Java / Go / C++ / TypeScript 实现语义一致 |
| 文档 | 无 | 完整规范与社区文档 |
| 维护成本 | 自行承担 | 由上游承担 |

跨语言这一点对本项目尤其有价值:控制面用 Java 校验、前端用 TypeScript 实时做语法检查与自动补全、采集端如需轻量过滤可用 Go——三处行为一致,不需要各写一套。

`$CHECKNOTICE` 这类业务函数注册为 CEL 自定义函数即可,能力不丢失。

## 后果

- 1.x 中使用 SPL 的策略需要转写。考虑到 SPL 实际使用率极低(内置 170 条策略中无一使用),影响面很小
- 策略条件的表达能力显著增强:CEL 支持列表推导、map 操作、时间运算等
- 表达式的合法性在**保存策略时**即完成校验,而非等到运行时
