# 内置资产中的占位符

这些值**必须替换成你自己的**,否则相关策略不会正常工作。

这里没有任何来自原系统的秘密 —— 原值要么本来就是通用占位符,要么在脱敏时已被移除。

> **为什么这份清单重要**:含未配置占位符的策略不会报错。它们照常加载、照常参与计算,
> 然后要么**永不命中**、要么**恒真命中所有主体**。运营看到的只是「这条策略没量」或者
> 「这条策略把所有人都报了」,查不出原因。这类静默失效比直接报错难查得多。
>
> 全部 **10 条**需要配置的策略在 `strategies/index.json` 中标有 `requires_configuration`,
> 并在[策略模板参考](../docs/reference/strategies.md)里标为 🔧。

---

## 1. `<YOUR_PAYMENT_PAGE_PATH>` —— 支付页路径

| | |
|---|---|
| 1.x 中的原值 | `HOLDER`(占位符,不是真实路径) |
| 出现次数 | 12 处,分布在 7 个策略文件中 |
| 出现位置 | 条件中的 `page contains <...>` |
| 不配置的后果 | **策略恒真,命中所有主体**,包括全部正常用户 |

**含义**:能唯一标识你的下单 / 支付页面的 URL 路径片段,例如 `/order/pay` 或
`/checkout/confirm`。策略据此统计 `page` 字段含该片段的 `HTTP_DYNAMIC` 请求,
以判断一笔已提交的订单是否真的付过款。

**受影响的策略(7 条)**:

- `strategies/IP下单不支付.json`
- `strategies/用户下单不支付.json`
- `strategies/设备下单不支付.json`
- `strategies/IP请求登录前未访问必要资源.json`
- `strategies/设备请求登录前未访问必要资源.json`
- `strategies/IP请求注册前未访问必要资源.json`
- `strategies/设备请求注册前未访问必要资源.json`

**怎么填**:把上述文件里每个 `"<YOUR_PAYMENT_PAGE_PATH>"` 换成你自己的路径片段,
例如 `"/order/pay"`。比较运算符是 `contains`,给子串即可。

> 快速开始里那两条各命中 42 次、把 40 个正常用户全打中的策略,就是这一组中的两条 ——
> 它是「内置模板不能直接上生产」最直观的例证。

---

## 2. 字面量 `A` / `B` —— 事件序列模板

| | |
|---|---|
| 出现位置 | 条件中的 `page == "A"`,以及 `delay.condition` 中的 `page == "B"` |
| 不配置的后果 | **永不命中**(没有任何页面路径等于字面量 `"A"`) |
| 引擎支持情况 | ✅ 已实现(v0.3.x)。替换 A / B 后即可使用 |

这三条不是可直接使用的策略,而是**延迟求值策略的模板骨架**:「主体请求了页面 A,
但在随后 300 秒内没有请求页面 B」。`A` 与 `B` 是要你替换的两个页面路径。

**受影响的策略(3 条)**:

| 文件 | 风险主体 |
|---|---|
| `strategies/IP请求A一段时间内没有请求B.json` | IP |
| `strategies/用户请求A一段时间内没有请求B.json` | 账号 |
| `strategies/设备请求A一段时间内没有请求B.json` | 设备 |

**怎么填**:每个文件里把 `"A"` 换成触发页的路径、把 `"B"` 换成期望的后续页路径。
`delay.duration_seconds` 是等待时长,默认 300 秒。

典型用法:A = 加入购物车,B = 提交订单;或 A = 领券,B = 使用券。

> **注意**:这一类的失效方向与第 1 类相反 —— 它是永不命中,不会产生任何噪音,
> 因此更容易被误以为「这条策略没问题,只是没有风险发生」。

---

## 3. `config-defaults.json` 中需要审阅的配置

这些是从 1.x 出厂配置迁移过来的默认值。标注 *已脱敏* 的项原本含真实数据,已替换为
`example.*` 占位;标注 *留空* 的项在原始数据中就是空的,但功能要用就得填。

> **这份文件是 1.x 的配置形态,2.0 的运行时配置不读它。** 保留它是为了迁移时对照,
> 2.0 的实际配置见[配置项参考](../docs/reference/configuration.md)。

| 键 | 种子值 | 状态 | 含义 |
|---|---|---|---|
| `alerting.mail.base_url` | `http://nebula.example.com` | 已脱敏 | 控制台的对外地址,用于拼装告警邮件里的链接 |
| `alerting.mail.sender` | `alerts@example.com` | 已脱敏 | 告警邮件的发件地址 |
| `alerting.to_emails` | `alerts-primary@example.com,...` | 已脱敏 | 告警收件人,逗号分隔 |
| `alerting.smtp_server` / `_port` / `_account` | (空) | 留空 | SMTP 连接信息 |
| `alerting.smtp_password` | (空) | 留空 | **切勿提交真实值**,部署时注入 |
| `alerting.nebula_address` | (空) | 留空 | 告警服务访问 Nebula 的地址 |
| `alerting.email_topic` | (空) | 留空 | 告警邮件的主题前缀 |
| `filter.encryption.salt` | (空) | 留空 | 敏感字段哈希用的盐。**必须自己生成**,绝不复用其他部署的盐 |
| `filter.encryption.names` | (空) | 留空 | 需要加密 / 哈希的字段名,逗号分隔 |
| `filter.log.*` / `filter.traffic.*` | (空) | 留空 | 日志与镜像流量的采集过滤条件 |
| `sniffer.uid.keyset` | `user_name` | 需审阅 | 采集器从哪个请求字段取账号标识,按你的应用改 |
| `sniffer.did.keyset` | `did` | 需审阅 | 采集器从哪个请求字段取设备标识,按你的应用改 |

---

## 4. 看着像占位符,但不是

- 每个资产上的 `app: "nebula"` —— 这是内置的应用命名空间,不是客户名
- 条件里的字段标识符(`c_ip`、`did`、`uid`、`page`、`order_id` 等)—— 这些是星云
  自己的事件模型字段名,与 `events/` 中的定义对应
- 形如 `^\\s*$` 的正则 —— 这是货真价实的「是否为空」判断

---

## 相关文档

| | |
|---|---|
| [资产清单与审计结论](INVENTORY.md) | 从 1.x 继承的已知缺陷 |
| [策略模板参考](../docs/reference/strategies.md) | 170 条逐条说明,🔧 标出需配置的 |
| [策略开发指南](../docs/guide/strategy.md) | 怎么改、怎么校准阈值 |
