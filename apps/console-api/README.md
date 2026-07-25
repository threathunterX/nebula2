# console-api —— 控制面 API

🚧 实现中。

Java 21 + Spring Boot 3。合并了 1.x 的 `apiserver`(自研 Netty 框架)、`nebula_web`(Tornado)与 `nebula_query_web`(Flask)三个服务。

职责:策略与变量管理、名单查询、报表、RBAC 与审计、`/checkRisk` 对外接口。

与计算引擎同语言,共享由 `packages/domain-schema` 生成的领域模型类型——这是为了根除 1.x 中 Python 与 Java 两套模型漂移的问题。
