# engine —— 计算引擎

🚧 实现中。

基于 Apache Flink 的统一计算作业,包含三部分:

1. **变量引擎** —— 按变量定义构建计算图,在事件流上做窗口聚合
2. **规则引擎** —— CEL 表达式求值 + Flink CEP 序列检测,产出风险告警
3. **画像更新** —— 维护长期行为基线

合并了 1.x 的 `online`、`offline`、`greyhound` 三个组件。为什么统一、以及为什么取消离线重算,见 [ADR-0002](../../docs/adr/0002-flink-as-unified-engine.md)。
