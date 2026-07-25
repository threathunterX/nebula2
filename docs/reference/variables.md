# 变量参考

> **本文件由 `tools/gen_variable_reference.py` 自动生成,请勿手工编辑。**
>
> 数据来源:`seeds/variables/*.json`(253 个变量,不含 `index.json`)、`seeds/events/*.json`(17 个事件)。
>
> 要修改内容,请改 seeds 资产后重新运行 `python3 tools/gen_variable_reference.py`;CI 通过 `python3 tools/gen_variable_reference.py --check` 校验文档与资产是否一致。
>
> 敏感级别(`sensitivity`)与保护方式(`value_masking`)随资产一同维护:**新增变量时必须评估其值的敏感级别**,未显式标注即按 schema 缺省值 `internal` 处理;标注为 `pii`/`sensitive` 的变量必须声明 `hash` 或 `partial`。详见[隐私设计](../security/privacy.md)。

变量是在事件流上计算出的统计特征,是策略的输入。本文档给出全部内置变量的机读事实;概念与设计动机见[风控数据模型](../concepts/data-model.md)。

---

## 一、概览

- 变量总数:**253**(全部 `status = enable`)
- 事件总数:**17**
- 依赖链最长:**5** 层

**按模块(计算层)分布**

| 取值 | 含义 | 数量 | 占比 |
|---|---|---:|---:|
| `base` | base —— 事件层与过滤层(无窗口) | 19 | 7.5% |
| `realtime` | realtime —— 5 分钟滑动窗口 | 97 | 38.3% |
| `slot` | slot —— 1 小时滚动窗口 | 98 | 38.7% |
| `profile` | profile —— 长期画像 | 39 | 15.4% |
| **合计** | | **253** | 100.0% |

**按维度分布**

| 取值 | 含义 | 数量 | 占比 |
|---|---|---:|---:|
| `uid` | 账号 | 89 | 35.2% |
| `ip` | IP | 58 | 22.9% |
| `did` | 设备 | 58 | 22.9% |
| `page` | 页面 | 11 | 4.3% |
| `global` | 全局 | 18 | 7.1% |
| (空) | 无维度 | 19 | 7.5% |
| **合计** | | **253** | 100.0% |

**按变量类型分布**

| 取值 | 含义 | 数量 | 占比 |
|---|---|---:|---:|
| `event` | 事件解包 | 17 | 6.7% |
| `filter` | 过滤派生 | 2 | 0.8% |
| `aggregate` | 窗口聚合 | 172 | 68.0% |
| `dual` | 二元运算 | 25 | 9.9% |
| `sequence` | 相邻求差 | 3 | 1.2% |
| `top` | TopN | 34 | 13.4% |
| **合计** | | **253** | 100.0% |

**按值类型分布**

| 取值 | 含义 | 数量 | 占比 |
|---|---|---:|---:|
| `long` | 整数 | 80 | 31.6% |
| `double` | 浮点 | 34 | 13.4% |
| `string` | 字符串 | 7 | 2.8% |
| `map` | 映射(key → 值) | 57 | 22.5% |
| `mmap` | 分槽映射(按时间槽保存的映射) | 52 | 20.6% |
| `list` | 列表 | 3 | 1.2% |
| `mlist` | 分槽列表(按时间槽保存的列表) | 1 | 0.4% |
| (空) | 无(事件/过滤层不产出值) | 19 | 7.5% |
| **合计** | | **253** | 100.0% |

**按敏感级别分布**

| 取值 | 含义 | 数量 | 占比 |
|---|---|---:|---:|
| `internal` | 内部 —— 仅系统内部使用(资产中显式标注 7 个) | 233 | 92.1% |
| `pii` | 个人信息 —— 值可直接关联到自然人(资产中显式标注 20 个) | 20 | 7.9% |
| **合计** | | **253** | 100.0% |

**保护方式(`value_masking`)分布**:`none` 明文存储 233 个、`hash` HMAC 存储 20 个。schema 约束:`sensitivity` 为 `pii` 或 `sensitive` 时,`value_masking` 不允许为 `none`。

> **为什么 profile 模块的标注尤其重要**:承载个人信息的 20 个变量全部位于 `profile` 模块 —— 而 `profile` 是保留期最长的一层(默认 180 天,其余层为小时/分钟级窗口)。长期画像的价值恰恰来自保留可识别的历史,隐私风险也因此最集中。新增 `profile` 变量时必须评估其值的敏感级别,详见[隐私设计](../security/privacy.md)。

> 注意:敏感级别描述的是**变量值本身**,与来源事件字段的敏感级别是两件事 —— 非敏感字段可以聚合出敏感的值(如「账号最近 10 个登录 IP」),敏感字段也可以聚合出非敏感的值(如「手机号修改次数」)。

---

## 二、命名规范

变量名由**双下划线**分段,复合维度在段内用单下划线连接(如 `did_ip`、`uid_geo_city`):

```
{维度key}__{业务语义}__{窗口}__{模块}
```

例:`ip__account_login_count__1h__slot`

| 段 | 值 | 含义 |
|---|---|---|
| 维度 key | `ip` | 按该主体分组统计 |
| 业务语义 | `account_login_count` | 统计什么 |
| 窗口 | `1h` | 统计窗口 |
| 模块 | `slot` | 计算层 |

实际资产中的分段情况:

| 分段数 | 数量 | 说明 |
|---:|---:|---|
| 1 | 19 | base 层事件/过滤变量,直接用事件名(全大写),无分段 |
| 3 | 20 | 省略窗口段(多为 profile 长期变量,窗口即「长期」) |
| 4 | 210 | 标准四段式 |
| 5 | 4 | 业务语义本身跨两段(少数注册类画像变量) |

**维度前缀**(四段式变量):`uid` 55 个、`did` 48 个、`ip` 48 个、`global` 18 个、`page` 5 个、`did_geo_city` 2 个、`did_ip` 2 个、`did_page` 2 个、`did_uid` 2 个、`did_useragent` 2 个、`ip_did` 2 个、`ip_geo_city` 2 个、`ip_page` 2 个、`ip_uid` 2 个、`ip_useragent` 2 个、`page_did` 2 个、`page_ip` 2 个、`page_uid` 2 个、`uid_did` 2 个、`uid_geo_city` 2 个、`uid_ip` 2 个、`uid_page` 2 个、`uid_useragent` 2 个

**窗口段**(四段式变量):`1h` 106 个、`5m` 97 个、`1d` 7 个

**模块后缀**:`slot` 98 个、`rt` 97 个、`profile` 39 个(`rt` 即 realtime)

> 命名段是**约定**而非强校验,窗口段与 `period` 字段可能不完全一一对应;以资产中的 `period`、`module` 字段为准,本文档所有窗口列均由 `period` 渲染。

---

## 三、变量全表(按模块分组)

列说明:**窗口**由 `period` 字段渲染;**来源事件**是沿 `source` 依赖链向上追溯到的根事件(即 `seeds/events/` 中定义的事件),不是直接上游变量;**敏感级别**由 `sensitivity` 渲染,未显式标注的按 schema 缺省值 `internal`(内部)呈现,括号内是 `value_masking` 指定的存储保护方式。

### base —— 事件/过滤层(19 个)

窗口分布:无窗口(19 个)。

| 变量名 | 说明 | 维度 | 类型 | 值类型 | 窗口 | 来源事件 | 敏感级别 |
|---|---|---|---|---|---|---|---|
| `ACCOUNT_CERTIFICATION` | 账号-实名验证 | 无维度 | event(事件解包) | — | 无窗口 | `ACCOUNT_CERTIFICATION` | 内部 |
| `ACCOUNT_LOGIN` | 账号-登录 | 无维度 | event(事件解包) | — | 无窗口 | `ACCOUNT_LOGIN` | 内部 |
| `ACCOUNT_PW_CHANGE` | 账号-密码修改 | 无维度 | event(事件解包) | — | 无窗口 | `ACCOUNT_PW_CHANGE` | 内部 |
| `ACCOUNT_REFERRALCODE_CREATE` | 账号-生成推荐码 | 无维度 | event(事件解包) | — | 无窗口 | `ACCOUNT_REFERRALCODE_CREATE` | 内部 |
| `ACCOUNT_REGISTRATION` | 账号-注册 | 无维度 | event(事件解包) | — | 无窗口 | `ACCOUNT_REGISTRATION` | 内部 |
| `ACCOUNT_TOKEN_CHANGE` | 账号-安全凭证修改 | 无维度 | event(事件解包) | — | 无窗口 | `ACCOUNT_TOKEN_CHANGE` | 内部 |
| `ACTIVITY_DO` | 营销-参加活动 | 无维度 | event(事件解包) | — | 无窗口 | `ACTIVITY_DO` | 内部 |
| `HTTP_CLICK` | 点击 | 无维度 | filter(过滤派生) | — | 无窗口 | `HTTP_DYNAMIC` | 内部 |
| `HTTP_DYNAMIC` | 动态资源请求 | 无维度 | event(事件解包) | — | 无窗口 | `HTTP_DYNAMIC` | 内部 |
| `HTTP_DYNAMIC_DELAY` | http动态资源访问 | 无维度 | event(事件解包) | — | 无窗口 | `HTTP_DYNAMIC_DELAY` | 内部 |
| `HTTP_INCIDENT` | 风险事件 | 无维度 | filter(过滤派生) | — | 无窗口 | `HTTP_DYNAMIC` | 内部 |
| `HTTP_STATIC` | 静态资源请求 | 无维度 | event(事件解包) | — | 无窗口 | `HTTP_STATIC` | 内部 |
| `ORDER_CANCEL` | 订单-取消 | 无维度 | event(事件解包) | — | 无窗口 | `ORDER_CANCEL` | 内部 |
| `ORDER_SUBMIT` | 订单-提交 | 无维度 | event(事件解包) | — | 无窗口 | `ORDER_SUBMIT` | 内部 |
| `TRANSACTION_BANKCRD_BIND` | 支付-绑卡 | 无维度 | event(事件解包) | — | 无窗口 | `TRANSACTION_BANKCRD_BIND` | 内部 |
| `TRANSACTION_BANKCRD_UNBIND` | 支付-解绑 | 无维度 | event(事件解包) | — | 无窗口 | `TRANSACTION_BANKCRD_UNBIND` | 内部 |
| `TRANSACTION_DEPOSIT` | 支付-充值 | 无维度 | event(事件解包) | — | 无窗口 | `TRANSACTION_DEPOSIT` | 内部 |
| `TRANSACTION_ESCROW` | 支付-第三方支付 | 无维度 | event(事件解包) | — | 无窗口 | `TRANSACTION_ESCROW` | 内部 |
| `TRANSACTION_WITHDRAW` | 支付-取现 | 无维度 | event(事件解包) | — | 无窗口 | `TRANSACTION_WITHDRAW` | 内部 |

### realtime —— 实时层(97 个)

窗口分布:5 分钟滑动(97 个)。

| 变量名 | 说明 | 维度 | 类型 | 值类型 | 窗口 | 来源事件 | 敏感级别 |
|---|---|---|---|---|---|---|---|
| `did__account_dynamic_distinct_count_geo_city__5m__rt` | DID关联不同城市数[5m] | did(设备) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `did__account_dynamic_distinct_count_ip__5m__rt` | DID关联IP数[5m] | did(设备) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `did__account_dynamic_distinct_count_uid__5m__rt` | DID关联UID数[5m] | did(设备) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `did__account_login_count__5m__rt` | DID登录请求总数[5m] | did(设备) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ACCOUNT_LOGIN` | 内部 |
| `did__account_login_count_fail__5m__rt` | 单个DID5分钟内登陆失败总和 | did(设备) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ACCOUNT_LOGIN` | 内部 |
| `did__account_login_distinct_count_password__5m__rt` | DID不同登录密码数[5m] | did(设备) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ACCOUNT_LOGIN` | 内部 |
| `did__account_login_distinct_count_uid__5m__rt` | DID登录不同UID数[5m] | did(设备) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ACCOUNT_LOGIN` | 内部 |
| `did__account_login_fail_ratio__5m__rt` | DID登录失败比例[5m] | did(设备) | dual(二元运算) | double | 5 分钟滑动 | `ACCOUNT_LOGIN` | 内部 |
| `did__account_regist_count__5m__rt` | DID注册请求总数[5m] | did(设备) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ACCOUNT_REGISTRATION` | 内部 |
| `did__account_regist_count_fail__5m__rt` | 单个DID5分钟内注册失败总和 | did(设备) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ACCOUNT_REGISTRATION` | 内部 |
| `did__account_regist_distinct_count_password__5m__rt` | DID不同注册密码数[5m] | did(设备) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ACCOUNT_REGISTRATION` | 内部 |
| `did__account_regist_distinct_count_uid__5m__rt` | DID注册不同UID数[5m] | did(设备) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ACCOUNT_REGISTRATION` | 内部 |
| `did__account_regist_fail_ratio__5m__rt` | DID注册失败比例[5m] | did(设备) | dual(二元运算) | double | 5 分钟滑动 | `ACCOUNT_REGISTRATION` | 内部 |
| `did__visit_clicks_avg_timediff__5m__rt` | DID页面点击间隔平均值[5m] | did(设备) | aggregate(窗口聚合) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_clicks_count_refererhit__5m__rt` | DID引用页面被请求数[5m] | did(设备) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_clicks_cv_timediff__5m__rt` | DID页面点击间隔变异系数[5m] | did(设备) | dual(二元运算) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_clicks_timediff__5m__rt` | 单个DID5分钟内最近两次点击时间差 | did(设备) | sequence(相邻求差) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_clicks_var_timediff__5m__rt` | 单个DID5分钟内点击时间差方差值 | did(设备) | aggregate(窗口聚合) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_count__5m__rt` | DID请求量[5m] | did(设备) | dual(二元运算) | long | 5 分钟滑动 | `HTTP_DYNAMIC`、`HTTP_STATIC` | 内部 |
| `did__visit_count_static__5m__rt` | DID静态资源请求量[5m] | did(设备) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_STATIC` | 内部 |
| `did__visit_dynamic_avg_cbytes__5m__rt` | 单个DID5分钟内请求大小平均值 | did(设备) | aggregate(窗口聚合) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_dynamic_count__5m__rt` | DID动态资源请求量[5m] | did(设备) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_dynamic_count_get__5m__rt` | 单个DID5分钟动态资源GET请求总量 | did(设备) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_dynamic_count_post__5m__rt` | 单个DID5分钟动态资源POST请求总量 | did(设备) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_dynamic_cv_cbytes__5m__rt` | DID动态请求大小变异系数[5m] | did(设备) | dual(二元运算) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_dynamic_distinct_count_page__5m__rt` | DID动态请求不同页面数[5m] | did(设备) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_dynamic_distinct_count_referer__5m__rt` | DID动态请求不同引用页面数[5m] | did(设备) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_dynamic_get_ratio__5m__rt` | DID动态请求GET占比[5m] | did(设备) | dual(二元运算) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_dynamic_post_ratio__5m__rt` | DID动态请求POST占比[5m] | did(设备) | dual(二元运算) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_dynamic_var_cbytes__5m__rt` | 单个DID5分钟内请求大小方差值 | did(设备) | aggregate(窗口聚合) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_static_ratio__5m__rt` | DID静态资源请求比[5m] | did(设备) | dual(二元运算) | double | 5 分钟滑动 | `HTTP_DYNAMIC`、`HTTP_STATIC` | 内部 |
| `global__visit_count__5m__rt` | 5分钟访问量总和 | global(全局) | dual(二元运算) | long | 5 分钟滑动 | `HTTP_DYNAMIC`、`HTTP_STATIC` | 内部 |
| `global__visit_dynamic_count__5m__rt` | 5分钟动态资源访问量总和 | global(全局) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `global__visit_static_count__5m__rt` | 5分钟静态资源访问量总和 | global(全局) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_STATIC` | 内部 |
| `ip__account_login_count__5m__rt` | IP登录请求总数[5m] | ip(IP) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ACCOUNT_LOGIN` | 内部 |
| `ip__account_login_count_fail__5m__rt` | 单个IP5分钟内登陆失败总和 | ip(IP) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ACCOUNT_LOGIN` | 内部 |
| `ip__account_login_distinct_count_password__5m__rt` | IP不同登录密码数[5m] | ip(IP) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ACCOUNT_LOGIN` | 内部 |
| `ip__account_login_distinct_count_uid__5m__rt` | IP登录不同UID数[5m] | ip(IP) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ACCOUNT_LOGIN` | 内部 |
| `ip__account_login_fail_ratio__5m__rt` | IP登录失败比例[5m] | ip(IP) | dual(二元运算) | double | 5 分钟滑动 | `ACCOUNT_LOGIN` | 内部 |
| `ip__account_regist_count__5m__rt` | IP注册请求总数[5m] | ip(IP) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ACCOUNT_REGISTRATION` | 内部 |
| `ip__account_regist_distinct_count_password__5m__rt` | IP不同注册密码数[5m] | ip(IP) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ACCOUNT_REGISTRATION` | 内部 |
| `ip__account_regist_distinct_count_uid__5m__rt` | IP注册不同UID数[5m] | ip(IP) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ACCOUNT_REGISTRATION` | 内部 |
| `ip__account_regist_fail_count__5m__rt` | 单个IP5分钟内注册失败总和 | ip(IP) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ACCOUNT_REGISTRATION` | 内部 |
| `ip__account_regist_fail_ratio__5m__rt` | IP注册失败比例[5m] | ip(IP) | dual(二元运算) | double | 5 分钟滑动 | `ACCOUNT_REGISTRATION` | 内部 |
| `ip__visit_clicks_avg_timediff__5m__rt` | IP页面点击间隔平均值[5m] | ip(IP) | aggregate(窗口聚合) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_clicks_count_refererhit__5m__rt` | IP引用页面被请求数[5m] | ip(IP) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_clicks_cv_timediff__5m__rt` | IP页面点击间隔变异系数[5m] | ip(IP) | dual(二元运算) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_clicks_timediff__5m__rt` | 单个IP5分钟内最近两次点击时间差 | ip(IP) | sequence(相邻求差) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_clicks_var_timediff__5m__rt` | 单个IP5分钟内点击时间差方差值 | ip(IP) | aggregate(窗口聚合) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_count__5m__rt` | IP请求量[5m] | ip(IP) | dual(二元运算) | long | 5 分钟滑动 | `HTTP_DYNAMIC`、`HTTP_STATIC` | 内部 |
| `ip__visit_dynamic_avg_cbytes__5m__rt` | 单个IP5分钟内请求大小平均值 | ip(IP) | aggregate(窗口聚合) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_dynamic_count__5m__rt` | IP动态资源请求量[5m] | ip(IP) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_dynamic_count_get__5m__rt` | 单个IP5分钟动态资源GET请求总量 | ip(IP) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_dynamic_count_post__5m__rt` | 单个IP5分钟动态资源POST请求总量 | ip(IP) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_dynamic_cv_cbytes__5m__rt` | IP动态请求大小变异系数[5m] | ip(IP) | dual(二元运算) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_dynamic_distinct_count_did__5m__rt` | IP关联DID数[5m] | ip(IP) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_dynamic_distinct_count_page__5m__rt` | IP动态请求不同页面数[5m] | ip(IP) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_dynamic_distinct_count_referer__5m__rt` | IP动态请求不同引用页面数[5m] | ip(IP) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_dynamic_distinct_count_uid__5m__rt` | IP关联UID数[5m] | ip(IP) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_dynamic_get_ratio__5m__rt` | IP动态请求GET占比[5m] | ip(IP) | dual(二元运算) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_dynamic_post_ratio__5m__rt` | IP动态请求POST占比[5m] | ip(IP) | dual(二元运算) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_dynamic_var_cbytes__5m__rt` | 单个IP5分钟内请求大小方差值 | ip(IP) | aggregate(窗口聚合) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_static_count__5m__rt` | IP静态资源请求量[5m] | ip(IP) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_STATIC` | 内部 |
| `ip__visit_static_ratio__5m__rt` | 单个IP5分钟静态资源访问量占比 | ip(IP) | dual(二元运算) | double | 5 分钟滑动 | `HTTP_DYNAMIC`、`HTTP_STATIC` | 内部 |
| `uid__account_dynamic_distinct_count_did__5m__rt` | UID关联DID数[5m] | uid(账号) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `uid__account_dynamic_distinct_count_geo_city__5m__rt` | UID关联不同城市数[5m] | uid(账号) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `uid__account_dynamic_distinct_count_ip__5m__rt` | UID关联IP数[5m] | uid(账号) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `uid__account_login_count__5m__rt` | UID登录请求总数[5m] | uid(账号) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ACCOUNT_LOGIN` | 内部 |
| `uid__account_login_count_fail__5m__rt` | 单个UID5分钟内登陆失败总和 | uid(账号) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ACCOUNT_LOGIN` | 内部 |
| `uid__account_login_distinct_count_password__5m__rt` | UID不同登录密码数[5m] | uid(账号) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ACCOUNT_LOGIN` | 内部 |
| `uid__account_login_fail_ratio__5m__rt` | UID登录失败比例[5m] | uid(账号) | dual(二元运算) | double | 5 分钟滑动 | `ACCOUNT_LOGIN` | 内部 |
| `uid__order_cancel_count__5m__rt` | UID取消订单请求数[5m] | uid(账号) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ORDER_CANCEL` | 内部 |
| `uid__order_distinct_count_receiver_geo_city__5m__rt` | UID下单不同收货城市数[5m] | uid(账号) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ORDER_SUBMIT` | 内部 |
| `uid__order_submit_avg_order_money_amount__5m__rt` | UID平均下单成功金额[5m] | uid(账号) | aggregate(窗口聚合) | double | 5 分钟滑动 | `ORDER_SUBMIT` | 内部 |
| `uid__order_submit_count__5m__rt` | UID下单数[5m] | uid(账号) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ORDER_SUBMIT` | 内部 |
| `uid__order_submit_count_fail__5m__rt` | UID5分钟内下单失败总次数 | uid(账号) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ORDER_SUBMIT` | 内部 |
| `uid__order_submit_count_succ__5m__rt` | 用户下单成功数[5m] | uid(账号) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ORDER_SUBMIT` | 内部 |
| `uid__order_submit_distinct_count_merchant__5m__rt` | UID下单不同商户数[5m] | uid(账号) | aggregate(窗口聚合) | long | 5 分钟滑动 | `ORDER_SUBMIT` | 内部 |
| `uid__order_submit_fail_ratio__5m__rt` | UID下单失败比例[5m] | uid(账号) | dual(二元运算) | double | 5 分钟滑动 | `ORDER_SUBMIT` | 内部 |
| `uid__visit_clicks_avg_timediff__5m__rt` | UID页面点击间隔平均值[5m] | uid(账号) | aggregate(窗口聚合) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_clicks_count_refererhit__5m__rt` | UID引用页面被请求数[5m] | uid(账号) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_clicks_cv_timediff__5m__rt` | UID页面点击间隔变异系数[5m] | uid(账号) | dual(二元运算) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_clicks_timediff__5m__rt` | 单个UID5分钟内最近两次点击时间差 | uid(账号) | sequence(相邻求差) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_clicks_var_timediff__5m__rt` | 单个UID5分钟内点击时间差方差值 | uid(账号) | aggregate(窗口聚合) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_count__5m__rt` | UID请求量[5m] | uid(账号) | dual(二元运算) | long | 5 分钟滑动 | `HTTP_DYNAMIC`、`HTTP_STATIC` | 内部 |
| `uid__visit_count_static__5m__rt` | UID静态资源请求量[5m] | uid(账号) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_STATIC` | 内部 |
| `uid__visit_dynamic_avg_cbytes__5m__rt` | 单个UID5分钟内请求大小平均值 | uid(账号) | aggregate(窗口聚合) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_dynamic_count__5m__rt` | UID动态资源请求量[5m] | uid(账号) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_dynamic_count_get__5m__rt` | 单个UID5分钟动态资源GET请求总量 | uid(账号) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_dynamic_count_post__5m__rt` | 单个UID5分钟动态资源POST请求总量 | uid(账号) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_dynamic_cv_cbytes__5m__rt` | UID动态请求大小变异系数[5m] | uid(账号) | dual(二元运算) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_dynamic_distinct_count_page__5m__rt` | UID动态请求不同页面数[5m] | uid(账号) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_dynamic_distinct_count_referer__5m__rt` | UID动态请求不同引用页面数[5m] | uid(账号) | aggregate(窗口聚合) | long | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_dynamic_get_ratio__5m__rt` | UID动态请求GET占比[5m] | uid(账号) | dual(二元运算) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_dynamic_post_ratio__5m__rt` | UID动态请求POST占比[5m] | uid(账号) | dual(二元运算) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_dynamic_var_cbytes__5m__rt` | 单个UID5分钟内请求大小方差值 | uid(账号) | aggregate(窗口聚合) | double | 5 分钟滑动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_static_ratio__5m__rt` | UID静态资源请求比[5m] | uid(账号) | dual(二元运算) | double | 5 分钟滑动 | `HTTP_DYNAMIC`、`HTTP_STATIC` | 内部 |

### slot —— 小时层(98 个)

窗口分布:1 小时滚动(98 个)。

| 变量名 | 说明 | 维度 | 类型 | 值类型 | 窗口 | 来源事件 | 敏感级别 |
|---|---|---|---|---|---|---|---|
| `did__account_login_count__1h__slot` | 单个设备每小时登陆总量 | did(设备) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `ACCOUNT_LOGIN` | 内部 |
| `did__account_registration_count__1h__slot` | 单个设备每小时注册总量 | did(设备) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `ACCOUNT_REGISTRATION` | 内部 |
| `did__visit_dynamic_count__1h__slot` | 单个设备每小时访问量 | did(设备) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_dynamic_count_top100__1h__slot` | 每小时访问量前100的did | did(设备) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_dynamic_distinct_count_ip__1h__slot` | 单个设备每小时关联ip数量 | did(设备) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_dynamic_distinct_count_ip_top100__1h__slot` | 每小时关联ip前100的did | did(设备) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_dynamic_distinct_count_page__1h__slot` | 单个设备每小时动态资源关联page数量 | did(设备) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_dynamic_distinct_count_page_top100__1h__slot` | 每小时关联page前100的did | did(设备) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_dynamic_distinct_count_uid__1h__slot` | 单个设备每小时动态资源关联用户数量 | did(设备) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_dynamic_distinct_count_uid_top100__1h__slot` | 每小时关联uid前100的did | did(设备) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_dynamic_distinct_count_useragent__1h__slot` | 单个设备每小时动态资源关联useragent数量 | did(设备) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_incident_count__1h__slot` | 单个设备每小时风险访问量 | did(设备) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `did__visit_incident_count_top100__1h__slot` | 每小时风险访问量前100的did | did(设备) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `did_geo_city__visit_dynamic_count_top20__1h__slot` | 单个did访问的前20的城市 | did(设备) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `did_geo_city__visit_dynamic_group_count__1h__slot` | 单个did每小时每个城市的动态资源访问数量 | did(设备) | aggregate(窗口聚合) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `did_ip__visit_dynamic_count_top20__1h__slot` | 单个设备访问的前20的ip | did(设备) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `did_ip__visit_dynamic_group_count__1h__slot` | did_ip__visit_dynamic_group_count__1h__slot | did(设备) | aggregate(窗口聚合) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `did_page__visit_dynamic_count_top20__1h__slot` | 单个设备访问的前20的页面 | did(设备) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `did_page__visit_dynamic_group_count__1h__slot` | 单个设备上每个页面当前小时动态资源访问数 | did(设备) | aggregate(窗口聚合) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `did_uid__visit_dynamic_count_top20__1h__slot` | 单个设备访问量前20的用户 | did(设备) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `did_uid__visit_dynamic_group_count__1h__slot` | 单个设备上每个用户当前小时动态资源访问数 | did(设备) | aggregate(窗口聚合) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `did_useragent__visit_dynamic_count_top20__1h__slot` | 单个设备访问的前20的agent | did(设备) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `did_useragent__visit_dynamic_group_count__1h__slot` | 单个设备上每个agent当前小时动态资源访问数 | did(设备) | aggregate(窗口聚合) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `global__marketing_count__1h__slot` | 每小时营销活动次数 | global(全局) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `ACTIVITY_DO` | 内部 |
| `global__marketing_incident_count__1h__slot` | 每小时风险营销活动次数 | global(全局) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `ACTIVITY_DO` | 内部 |
| `global__order_submit_count__1h__slot` | 本小时订单提交总量 | global(全局) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `ORDER_SUBMIT` | 内部 |
| `global__order_submit_incident_count__1h__slot` | 本小时订单风险提交总量 | global(全局) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `ORDER_SUBMIT` | 内部 |
| `global__transaction_withdraw_count__1h__slot` | 每小时交易总数 | global(全局) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `TRANSACTION_WITHDRAW` | 内部 |
| `global__transaction_withdraw_incident_count__1h__slot` | 每小时交易风险总数 | global(全局) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `TRANSACTION_WITHDRAW` | 内部 |
| `global__transaction_withdraw_incident_sum_withdraw_amount__1h__slot` | 每小时风险交易总额 | global(全局) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `TRANSACTION_WITHDRAW` | 内部 |
| `global__transaction_withdraw_sum_withdraw_amount__1h__slot` | 每小时交易总额 | global(全局) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `TRANSACTION_WITHDRAW` | 内部 |
| `global__visit_dynamic_count__1h__slot` | 本小时动态资源访问总量 | global(全局) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `global__visit_dynamic_distinct_count_did__1h__slot` | 本小时动态资源访问设备数量 | global(全局) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `global__visit_dynamic_distinct_count_ip__1h__slot` | 本小时动态资源访问IP数量 | global(全局) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `global__visit_dynamic_distinct_count_uid__1h__slot` | 本小时动态资源访问用户数量 | global(全局) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `global__visit_incident_count__1h__slot` | 本小时风险事件数量 | global(全局) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `global__visit_incident_distinct_count_ip__1h__slot` | 本小时动态资源访问风险IP数量 | global(全局) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `global__visit_incident_distinct_count_uid__1h__slot` | 本小时动态资源访问风险UID数量 | global(全局) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip__account_login_count__1h__slot` | 单个ip每小时账号登陆次数 | ip(IP) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `ACCOUNT_LOGIN` | 内部 |
| `ip__account_registration_count__1h__slot` | 单个ip账号注册次数 | ip(IP) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `ACCOUNT_REGISTRATION` | 内部 |
| `ip__visit_dynamic_count__1h__slot` | 单个ip本小时动态资源访问数量 | ip(IP) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_dynamic_count_top100__1h__slot` | 每小时访问量前100的ip | ip(IP) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_dynamic_distinct_count_did__1h__slot` | 单个ip本小时动态资源访问关联DID数量 | ip(IP) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_dynamic_distinct_count_did_top100__1h__slot` | 每小时关联did前100的ip | ip(IP) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_dynamic_distinct_count_page__1h__slot` | 单个ip本小时动态资源访问关联页面数量 | ip(IP) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_dynamic_distinct_count_page_top100__1h__slot` | 每小时关联page前100的ip | ip(IP) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_dynamic_distinct_count_uid__1h__slot` | 单个ip本小时动态资源访问关联用户数量 | ip(IP) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_dynamic_distinct_count_uid_top100__1h__slot` | 每小时关联uid前100的ip | ip(IP) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_dynamic_distinct_count_useragent__1h__slot` | 单个ip每小时每个ua的动态资源请求数量 | ip(IP) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_incident_count__1h__slot` | 单个IP每小时风险访问量 | ip(IP) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_incident_count_top100__1h__slot` | 每小时风险访问量前100的ip | ip(IP) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_incident_first_timestamp__1h__slot` | 单个IP风险事件初始时间 | ip(IP) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip_did__visit_dynamic_count_top20__1h__slot` | 单个IP访问的前20的设备 | ip(IP) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip_did__visit_dynamic_group_count__1h__slot` | 单个ip上每个设备当前小时动态资源访问数 | ip(IP) | aggregate(窗口聚合) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip_geo_city__visit_dynamic_count_top20__1h__slot` | 单个ip访问的前20的城市 | ip(IP) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip_geo_city__visit_dynamic_group_count__1h__slot` | 单个ip每小时每个城市的动态资源访问数量 | ip(IP) | aggregate(窗口聚合) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip_page__visit_dynamic_count_top20__1h__slot` | 单个IP访问的前20的页面 | ip(IP) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip_page__visit_dynamic_group_count__1h__slot` | 单个ip本小时内每个页面的动态资源访问数量 | ip(IP) | aggregate(窗口聚合) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip_uid__visit_dynamic_count_top20__1h__slot` | 单个IP访问的前20的uid | ip(IP) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip_uid__visit_dynamic_group_count__1h__slot` | 单个ip本小时内每个用户的动态资源访问数量 | ip(IP) | aggregate(窗口聚合) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip_useragent__visit_dynamic_count_top20__1h__slot` | 单个IP访问的前20的useragent | ip(IP) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `ip_useragent__visit_dynamic_group_count__1h__slot` | 单个ip本小时内每个ua的动态资源访问数量 | ip(IP) | aggregate(窗口聚合) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `page__visit_dynamic_count__1h__slot` | 单个页面每小时动态访问量 | page(页面) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `page__visit_dynamic_distinct_count_did__1h__slot` | 单个页面每小时动态资源访问关联设备数 | page(页面) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `page__visit_dynamic_distinct_count_ip__1h__slot` | 单个页面每小时动态资源访问关联ip数 | page(页面) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `page__visit_dynamic_distinct_count_uid__1h__slot` | 单个页面每小时动态资源访问关联用户数 | page(页面) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `page__visit_incident_count__1h__slot` | 单个页面每小时风险访问量 | page(页面) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `page_did__visit_dynamic_count_top100__1h__slot` | 单个页面上访问量前100的did | page(页面) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `page_did__visit_dynamic_group_count__1h__slot` | 单个页面上每个设备当前小时访问数 | page(页面) | aggregate(窗口聚合) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `page_ip__visit_dynamic_count_top100__1h__slot` | 单个页面上访问量前100的ip | page(页面) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `page_ip__visit_dynamic_group_count__1h__slot` | 单个页面上每个ip当前小时访问数 | page(页面) | aggregate(窗口聚合) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `page_uid__visit_dynamic_count_top100__1h__slot` | 单个页面上访问量前100的uid | page(页面) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `page_uid__visit_dynamic_group_count__1h__slot` | 单个页面上每个用户当前小时访问数 | page(页面) | aggregate(窗口聚合) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid__account_login_count__1h__slot` | 单个用户本小时登陆量 | uid(账号) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `ACCOUNT_LOGIN` | 内部 |
| `uid__account_registration_count__1h__slot` | 单个用户本小时注册量 | uid(账号) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `ACCOUNT_REGISTRATION` | 内部 |
| `uid__account_registration_count_top100__1h__slot` | 注册量前100的user | uid(账号) | top(TopN) | mmap<long> | 1 小时滚动 | `ACCOUNT_REGISTRATION` | 内部 |
| `uid__visit_dynamic_count__1h__slot` | 单个用户本小时内动态资源访问总和 | uid(账号) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_dynamic_count_top100__1h__slot` | 每小时访问量前100的uid | uid(账号) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_dynamic_distinct_count_did__1h__slot` | 单个用户本小时内动态资源访问关联设备数量 | uid(账号) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_dynamic_distinct_count_did_top100__1h__slot` | 每小时关联did前100的uid | uid(账号) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_dynamic_distinct_count_ip__1h__slot` | 单个用户本小时内动态资源访问关联ip数量 | uid(账号) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_dynamic_distinct_count_ip_top100__1h__slot` | 每小时关联ip前100的uid | uid(账号) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_dynamic_distinct_count_page__1h__slot` | 单个用户本小时内动态资源访问关联page数量 | uid(账号) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_dynamic_distinct_count_page_top100__1h__slot` | 每小时关联page前100的uid | uid(账号) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_dynamic_distinct_count_useragent__1h__slot` | 单个用户本小时内动态资源访问关联useragent数量 | uid(账号) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_dynamic_last_timestamp__1h__slot` | 单个用户每小时最后的访问时间 | uid(账号) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_incident_count__1h__slot` | 单个UID每小时风险访问量 | uid(账号) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_incident_count_top100__1h__slot` | 每小时风险访问量前100的uid | uid(账号) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid_did__visit_dynamic_count_top20__1h__slot` | 每个user每个小时访问量前20的agent | uid(账号) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid_did__visit_dynamic_group_count__1h__slot` | 单个用户每小时每个设备的动态资源访问数量 | uid(账号) | aggregate(窗口聚合) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid_geo_city__visit_dynamic_count_top20__1h__slot` | 单个用户访问的前20的城市 | uid(账号) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid_geo_city__visit_dynamic_group_count__1h__slot` | 单个用户每小时每个城市的动态资源访问数量 | uid(账号) | aggregate(窗口聚合) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid_ip__visit_dynamic_count_top20__1h__slot` | 单个UID访问的前20的ip | uid(账号) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid_ip__visit_dynamic_group_count__1h__slot` | 单个用户每小时每个ip的动态资源访问数量 | uid(账号) | aggregate(窗口聚合) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid_page__visit_dynamic_count_top20__1h__slot` | 单个UID访问的前20的页面 | uid(账号) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid_page__visit_dynamic_group_count__1h__slot` | 单个用户每小时每个页面的动态资源访问数量 | uid(账号) | aggregate(窗口聚合) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid_useragent__visit_dynamic_count_top20__1h__slot` | 每个user每个小时访问量前20的agent | uid(账号) | top(TopN) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |
| `uid_useragent__visit_dynamic_group_count__1h__slot` | 单个用户每小时每个useragent的动态资源访问数量 | uid(账号) | aggregate(窗口聚合) | mmap<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 内部 |

### profile —— 画像层(39 个)

窗口分布:长期(24 个)、1 小时滚动(8 个)、1 天(自然日)(7 个)。

| 变量名 | 说明 | 维度 | 类型 | 值类型 | 窗口 | 来源事件 | 敏感级别 |
|---|---|---|---|---|---|---|---|
| `did__visit_distinct_ip__1d__profile` | did当天最多1小时关联ip数量 | did(设备) | aggregate(窗口聚合) | long | 1 天(自然日) | `HTTP_DYNAMIC` | 内部 |
| `did__visit_distinct_ip__1h__profile` | 用户每小时关联ip数 | did(设备) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 个人信息(HMAC 存储) |
| `did__visit_distinct_uid__1d__profile` | did当天最多1小时关联uid数量 | did(设备) | aggregate(窗口聚合) | long | 1 天(自然日) | `HTTP_DYNAMIC` | 内部 |
| `did__visit_distinct_uid__1h__profile` | did每小时关联uid数 | did(设备) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 个人信息(HMAC 存储) |
| `ip__visit_distinct_did__1d__profile` | IP当天最多1小时关联DID数量[1d] | ip(IP) | aggregate(窗口聚合) | long | 1 天(自然日) | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_distinct_did__1h__profile` | ip每小时关联did数量 | ip(IP) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 个人信息(HMAC 存储) |
| `ip__visit_distinct_uid__1d__profile` | IP当天最多1小时关联UID数量[1d] | ip(IP) | aggregate(窗口聚合) | long | 1 天(自然日) | `HTTP_DYNAMIC` | 内部 |
| `ip__visit_distinct_uid__1h__profile` | ip每小时关联uid数量 | ip(IP) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 个人信息(HMAC 存储) |
| `uid__account_login_count_succ__profile` | 用户登录成功次数[total] | uid(账号) | aggregate(窗口聚合) | long | 长期 | `ACCOUNT_LOGIN` | 内部 |
| `uid__account_login_distinct_count_did_succ__1d__profile` | 用户登录成功不同did个数[1d] | uid(账号) | aggregate(窗口聚合) | long | 1 天(自然日) | `ACCOUNT_LOGIN` | 内部 |
| `uid__account_login_distinct_did__1h__profile` | 账号登陆成功did按小时去重列表 | uid(账号) | aggregate(窗口聚合) | mlist<string> | 1 小时滚动 | `ACCOUNT_LOGIN` | 个人信息(HMAC 存储) |
| `uid__account_login_geocity_last10__profile` | 账号最近10条登录城市 | uid(账号) | aggregate(窗口聚合) | list<string> | 长期 | `ACCOUNT_LOGIN` | 个人信息(HMAC 存储) |
| `uid__account_login_ip_last10__profile` | 账号最近10条登录IP | uid(账号) | aggregate(窗口聚合) | list<string> | 长期 | `ACCOUNT_LOGIN` | 个人信息(HMAC 存储) |
| `uid__account_login_ip_last__profile` | 账号最近登录成功的ip | uid(账号) | aggregate(窗口聚合) | string | 长期 | `ACCOUNT_LOGIN` | 个人信息(HMAC 存储) |
| `uid__account_login_timestamp_last10__profile` | 账号最近10条登录时间 | uid(账号) | aggregate(窗口聚合) | list<long> | 长期 | `ACCOUNT_LOGIN` | 内部 |
| `uid__account_register_timestamp__profile` | 用户注册时间 | uid(账号) | aggregate(窗口聚合) | long | 长期 | `ACCOUNT_REGISTRATION` | 内部 |
| `uid__account_token_change_mail__profile` | 帐号修改的邮箱 | uid(账号) | aggregate(窗口聚合) | string | 长期 | `ACCOUNT_TOKEN_CHANGE` | 个人信息(HMAC 存储) |
| `uid__account_token_change_mail_count__profile` | 账号累计修改邮箱次数 | uid(账号) | aggregate(窗口聚合) | long | 长期 | `ACCOUNT_TOKEN_CHANGE` | 内部 |
| `uid__account_token_change_mail_last_timestamp__profile` | 账号最近修改邮箱时间 | uid(账号) | aggregate(窗口聚合) | long | 长期 | `ACCOUNT_TOKEN_CHANGE` | 内部 |
| `uid__account_token_change_mail_timestamp__profile` | 帐号修改的邮箱的时间 | uid(账号) | aggregate(窗口聚合) | long | 长期 | `ACCOUNT_TOKEN_CHANGE` | 内部 |
| `uid__account_token_change_mobile__profile` | 帐号修改的手机号 | uid(账号) | aggregate(窗口聚合) | string | 长期 | `ACCOUNT_TOKEN_CHANGE` | 个人信息(HMAC 存储) |
| `uid__account_token_change_mobile_count__profile` | 账号修改手机号次数 | uid(账号) | aggregate(窗口聚合) | long | 长期 | `ACCOUNT_TOKEN_CHANGE` | 内部 |
| `uid__account_token_change_mobile_timestamp__profile` | 帐号修改的手机号时间 | uid(账号) | aggregate(窗口聚合) | long | 长期 | `ACCOUNT_TOKEN_CHANGE` | 内部 |
| `uid__alarm_count__profile` | 报警次数 | uid(账号) | aggregate(窗口聚合) | long | 长期 | `HTTP_DYNAMIC` | 内部 |
| `uid__registration__account__ip__profile` | 用户注册时的ip | uid(账号) | aggregate(窗口聚合) | string | 长期 | `ACCOUNT_REGISTRATION` | 个人信息(HMAC 存储) |
| `uid__registration__account__mail__profile` | 用户注册邮箱 | uid(账号) | aggregate(窗口聚合) | string | 长期 | `ACCOUNT_REGISTRATION` | 个人信息(HMAC 存储) |
| `uid__registration__account__mobile__profile` | 用户注册手机 | uid(账号) | aggregate(窗口聚合) | string | 长期 | `ACCOUNT_REGISTRATION` | 个人信息(HMAC 存储) |
| `uid__registration__account__username__profile` | 用户注册用户名 | uid(账号) | aggregate(窗口聚合) | string | 长期 | `ACCOUNT_REGISTRATION` | 个人信息(HMAC 存储) |
| `uid__transaction_withdraw_sum_withdraw_amount__1h__profile` | 账号每小时消费金额 | uid(账号) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `TRANSACTION_WITHDRAW` | 内部 |
| `uid__visit_distinct_did__1d__profile` | UID当天最多1小时关联DID数量[1d] | uid(账号) | aggregate(窗口聚合) | long | 1 天(自然日) | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_distinct_did__1h__profile` | 用户每小时关联did数量 | uid(账号) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 个人信息(HMAC 存储) |
| `uid__visit_distinct_ip__1d__profile` | UID当天最多1小时关联IP数量[1d] | uid(账号) | aggregate(窗口聚合) | long | 1 天(自然日) | `HTTP_DYNAMIC` | 内部 |
| `uid__visit_distinct_ip__1h__profile` | 用户每小时关联ip数量 | uid(账号) | aggregate(窗口聚合) | map<long> | 1 小时滚动 | `HTTP_DYNAMIC` | 个人信息(HMAC 存储) |
| `uid__visit_dynamic_last_timestamp__profile` | 用户最后访问时间 | uid(账号) | aggregate(窗口聚合) | long | 长期 | `HTTP_DYNAMIC` | 内部 |
| `uid_did__account_login_count_succ__profile` | 账号登录成功did去重列表，并按照次数自增 | uid(账号) | aggregate(窗口聚合) | map<long> | 长期 | `ACCOUNT_LOGIN` | 个人信息(HMAC 存储) |
| `uid_did__visit_dynamic_count__profile` | 用户访问设备分布 | uid(账号) | aggregate(窗口聚合) | map<long> | 长期 | `HTTP_DYNAMIC` | 个人信息(HMAC 存储) |
| `uid_geo_city__visit_dynamic_count__profile` | 用户主要访问地区来源 | uid(账号) | aggregate(窗口聚合) | map<long> | 长期 | `HTTP_DYNAMIC` | 个人信息(HMAC 存储) |
| `uid_timestamp__visit_dynamic_count__profile` | 用户访问时间偏好 | uid(账号) | aggregate(窗口聚合) | long | 长期 | `HTTP_DYNAMIC` | 内部 |
| `uid_useragent__visit_dynamic_count__profile` | 用户访问User Agent分布 | uid(账号) | aggregate(窗口聚合) | map<long> | 长期 | `HTTP_DYNAMIC` | 个人信息(HMAC 存储) |

---

## 四、长期画像变量详述(39 个)

`profile` 变量刻画主体的长期行为基线,是复用价值最高的一批资产——策略里「这次行为和这个账号平时不一样」的判断几乎都建立在它们之上。以下逐个展开聚合函数与过滤条件。

### 维度:uid(账号)(31 个)

#### `uid__account_login_count_succ__profile`

- **说明**:用户登录成功次数[total]
- **聚合**:`count()` 计数
- **过滤条件**:`ACCOUNT_LOGIN.result` 等于 `T`
- **窗口**:长期
- **取值**:long
- **分组键**:`uid`
- **直接输入**:`ACCOUNT_LOGIN` → **根事件**:`ACCOUNT_LOGIN`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:内部(未显式标注,按 schema 缺省值)

#### `uid__account_login_distinct_count_did_succ__1d__profile`

- **说明**:用户登录成功不同did个数[1d]
- **聚合**:`distinct_count(value)` 去重计数
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:1 天(自然日)
- **取值**:long
- **分组键**:`uid`
- **直接输入**:`uid__account_login_distinct_did__1h__profile` → **根事件**:`ACCOUNT_LOGIN`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:内部(未显式标注,按 schema 缺省值)

#### `uid__account_login_distinct_did__1h__profile`

- **说明**:账号登陆成功did按小时去重列表
- **聚合**:`distinct(did)` 去重取值集合
- **过滤条件**:`ACCOUNT_LOGIN.result` 等于 `T`
- **窗口**:1 小时滚动
- **取值**:mlist<string>,语义类别 `did`
- **分组键**:`uid`
- **直接输入**:`ACCOUNT_LOGIN` → **根事件**:`ACCOUNT_LOGIN`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:个人信息(HMAC 存储)

#### `uid__account_login_geocity_last10__profile`

- **说明**:账号最近10条登录城市
- **聚合**:`lastn(geo_city, N=10)` 取最近 N 次的值
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:长期
- **取值**:list<string>
- **分组键**:`uid`
- **直接输入**:`ACCOUNT_LOGIN` → **根事件**:`ACCOUNT_LOGIN`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:个人信息(HMAC 存储)

#### `uid__account_login_ip_last10__profile`

- **说明**:账号最近10条登录IP
- **聚合**:`lastn(c_ip, N=10)` 取最近 N 次的值
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:长期
- **取值**:list<string>,语义类别 `ip`
- **分组键**:`uid`
- **直接输入**:`ACCOUNT_LOGIN` → **根事件**:`ACCOUNT_LOGIN`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:个人信息(HMAC 存储)

#### `uid__account_login_ip_last__profile`

- **说明**:账号最近登录成功的ip
- **聚合**:`last(c_ip)` 取最近一次的值
- **过滤条件**:`ACCOUNT_LOGIN.result` 等于 `T`
- **窗口**:长期
- **取值**:string,语义类别 `ip`
- **分组键**:`uid`
- **直接输入**:`ACCOUNT_LOGIN` → **根事件**:`ACCOUNT_LOGIN`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:个人信息(HMAC 存储)

#### `uid__account_login_timestamp_last10__profile`

- **说明**:账号最近10条登录时间
- **聚合**:`lastn(timestamp, N=10)` 取最近 N 次的值
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:长期
- **取值**:list<long>
- **分组键**:`uid`
- **直接输入**:`ACCOUNT_LOGIN` → **根事件**:`ACCOUNT_LOGIN`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:内部

#### `uid__account_register_timestamp__profile`

- **说明**:用户注册时间
- **聚合**:`last(timestamp)` 取最近一次的值
- **过滤条件**:`ACCOUNT_REGISTRATION.result` 等于 `T`
- **窗口**:长期
- **取值**:long
- **分组键**:`uid`
- **直接输入**:`ACCOUNT_REGISTRATION` → **根事件**:`ACCOUNT_REGISTRATION`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:内部(未显式标注,按 schema 缺省值)

#### `uid__account_token_change_mail__profile`

- **说明**:帐号修改的邮箱
- **聚合**:`last(new_token)` 取最近一次的值
- **过滤条件**:`ACCOUNT_TOKEN_CHANGE.result` 等于 `T` 且 `ACCOUNT_TOKEN_CHANGE.token_type` 等于 `email`
- **窗口**:长期
- **取值**:string
- **分组键**:`uid`
- **直接输入**:`ACCOUNT_TOKEN_CHANGE` → **根事件**:`ACCOUNT_TOKEN_CHANGE`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:个人信息(HMAC 存储)

#### `uid__account_token_change_mail_count__profile`

- **说明**:账号累计修改邮箱次数
- **聚合**:`count()` 计数
- **过滤条件**:`ACCOUNT_TOKEN_CHANGE.result` 等于 `T` 且 `ACCOUNT_TOKEN_CHANGE.token_type` 等于 `email`
- **窗口**:长期
- **取值**:long
- **分组键**:`uid`
- **直接输入**:`ACCOUNT_TOKEN_CHANGE` → **根事件**:`ACCOUNT_TOKEN_CHANGE`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:内部

#### `uid__account_token_change_mail_last_timestamp__profile`

- **说明**:账号最近修改邮箱时间
- **聚合**:`last(timestamp)` 取最近一次的值
- **过滤条件**:`ACCOUNT_TOKEN_CHANGE.result` 等于 `T` 且 `ACCOUNT_TOKEN_CHANGE.token_type` 等于 `email`
- **窗口**:长期
- **取值**:long
- **分组键**:`uid`
- **直接输入**:`ACCOUNT_TOKEN_CHANGE` → **根事件**:`ACCOUNT_TOKEN_CHANGE`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:内部

#### `uid__account_token_change_mail_timestamp__profile`

- **说明**:帐号修改的邮箱的时间
- **聚合**:`last(timestamp)` 取最近一次的值
- **过滤条件**:`ACCOUNT_TOKEN_CHANGE.result` 等于 `T` 且 `ACCOUNT_TOKEN_CHANGE.token_type` 等于 `email`
- **窗口**:长期
- **取值**:long
- **分组键**:`uid`
- **直接输入**:`ACCOUNT_TOKEN_CHANGE` → **根事件**:`ACCOUNT_TOKEN_CHANGE`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:内部

#### `uid__account_token_change_mobile__profile`

- **说明**:帐号修改的手机号
- **聚合**:`last(new_token)` 取最近一次的值
- **过滤条件**:`ACCOUNT_TOKEN_CHANGE.result` 等于 `T` 且 `ACCOUNT_TOKEN_CHANGE.token_type` 等于 `mobile`
- **窗口**:长期
- **取值**:string
- **分组键**:`uid`
- **直接输入**:`ACCOUNT_TOKEN_CHANGE` → **根事件**:`ACCOUNT_TOKEN_CHANGE`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:个人信息(HMAC 存储)

#### `uid__account_token_change_mobile_count__profile`

- **说明**:账号修改手机号次数
- **聚合**:`count()` 计数
- **过滤条件**:`ACCOUNT_TOKEN_CHANGE.result` 等于 `T` 且 `ACCOUNT_TOKEN_CHANGE.token_type` 等于 `mobile`
- **窗口**:长期
- **取值**:long
- **分组键**:`uid`
- **直接输入**:`ACCOUNT_TOKEN_CHANGE` → **根事件**:`ACCOUNT_TOKEN_CHANGE`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:内部

#### `uid__account_token_change_mobile_timestamp__profile`

- **说明**:帐号修改的手机号时间
- **聚合**:`last(timestamp)` 取最近一次的值
- **过滤条件**:`ACCOUNT_TOKEN_CHANGE.result` 等于 `T` 且 `ACCOUNT_TOKEN_CHANGE.token_type` 等于 `mobile`
- **窗口**:长期
- **取值**:long
- **分组键**:`uid`
- **直接输入**:`ACCOUNT_TOKEN_CHANGE` → **根事件**:`ACCOUNT_TOKEN_CHANGE`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:内部

#### `uid__alarm_count__profile`

- **说明**:报警次数
- **聚合**:`merge_value(value)` 合并并累加值
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:长期
- **取值**:long
- **分组键**:`uid`
- **直接输入**:`uid__visit_incident_count__1h__slot` → **根事件**:`HTTP_DYNAMIC`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:内部(未显式标注,按 schema 缺省值)

#### `uid__registration__account__ip__profile`

- **说明**:用户注册时的ip
- **聚合**:`last(c_ip)` 取最近一次的值
- **过滤条件**:`ACCOUNT_REGISTRATION.result` 等于 `T`
- **窗口**:长期
- **取值**:string,语义类别 `ip`
- **分组键**:`uid`
- **直接输入**:`ACCOUNT_REGISTRATION` → **根事件**:`ACCOUNT_REGISTRATION`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:个人信息(HMAC 存储)

#### `uid__registration__account__mail__profile`

- **说明**:用户注册邮箱
- **聚合**:`last(register_verification_token)` 取最近一次的值
- **过滤条件**:`ACCOUNT_REGISTRATION.result` 等于 `T` 且 `ACCOUNT_REGISTRATION.register_verification_token_type` 等于 `email`
- **窗口**:长期
- **取值**:string
- **分组键**:`uid`
- **直接输入**:`ACCOUNT_REGISTRATION` → **根事件**:`ACCOUNT_REGISTRATION`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:个人信息(HMAC 存储)

#### `uid__registration__account__mobile__profile`

- **说明**:用户注册手机
- **聚合**:`last(register_verification_token)` 取最近一次的值
- **过滤条件**:`ACCOUNT_REGISTRATION.result` 等于 `T` 且 `ACCOUNT_REGISTRATION.register_verification_token_type` 等于 `mobile`
- **窗口**:长期
- **取值**:string
- **分组键**:`uid`
- **直接输入**:`ACCOUNT_REGISTRATION` → **根事件**:`ACCOUNT_REGISTRATION`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:个人信息(HMAC 存储)

#### `uid__registration__account__username__profile`

- **说明**:用户注册用户名
- **聚合**:`last(user_name)` 取最近一次的值
- **过滤条件**:`ACCOUNT_REGISTRATION.result` 等于 `T`
- **窗口**:长期
- **取值**:string
- **分组键**:`uid`
- **直接输入**:`ACCOUNT_REGISTRATION` → **根事件**:`ACCOUNT_REGISTRATION`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:个人信息(HMAC 存储)

#### `uid__transaction_withdraw_sum_withdraw_amount__1h__profile`

- **说明**:账号每小时消费金额
- **聚合**:`sum(withdraw_amount)` 求和
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:1 小时滚动
- **取值**:map<long>
- **分组键**:`uid`
- **直接输入**:`TRANSACTION_WITHDRAW` → **根事件**:`TRANSACTION_WITHDRAW`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:内部

#### `uid__visit_distinct_did__1d__profile`

- **说明**:UID当天最多1小时关联DID数量[1d]
- **聚合**:`max(value)` 取最大值
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:1 天(自然日)
- **取值**:long
- **分组键**:`uid`
- **直接输入**:`uid__visit_distinct_did__1h__profile` → **根事件**:`HTTP_DYNAMIC`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:内部(未显式标注,按 schema 缺省值)

#### `uid__visit_distinct_did__1h__profile`

- **说明**:用户每小时关联did数量
- **聚合**:`merge(value)` 按时间槽合并
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:1 小时滚动
- **取值**:map<long>
- **分组键**:`uid`
- **直接输入**:`uid__visit_dynamic_distinct_count_did__1h__slot` → **根事件**:`HTTP_DYNAMIC`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:个人信息(HMAC 存储)

#### `uid__visit_distinct_ip__1d__profile`

- **说明**:UID当天最多1小时关联IP数量[1d]
- **聚合**:`max(value)` 取最大值
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:1 天(自然日)
- **取值**:long
- **分组键**:`uid`
- **直接输入**:`uid__visit_distinct_ip__1h__profile` → **根事件**:`HTTP_DYNAMIC`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:内部(未显式标注,按 schema 缺省值)

#### `uid__visit_distinct_ip__1h__profile`

- **说明**:用户每小时关联ip数量
- **聚合**:`merge(value)` 按时间槽合并
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:1 小时滚动
- **取值**:map<long>
- **分组键**:`uid`
- **直接输入**:`uid__visit_dynamic_distinct_count_ip__1h__slot` → **根事件**:`HTTP_DYNAMIC`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:个人信息(HMAC 存储)

#### `uid__visit_dynamic_last_timestamp__profile`

- **说明**:用户最后访问时间
- **聚合**:`last_value(value)` 取最近一个时间槽的值
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:长期
- **取值**:long
- **分组键**:`uid`
- **直接输入**:`uid__visit_dynamic_last_timestamp__1h__slot` → **根事件**:`HTTP_DYNAMIC`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:内部(未显式标注,按 schema 缺省值)

#### `uid_did__account_login_count_succ__profile`

- **说明**:账号登录成功did去重列表，并按照次数自增
- **聚合**:`group_count(did)` 按 key 分组计数
- **过滤条件**:`ACCOUNT_LOGIN.result` 等于 `T`
- **窗口**:长期
- **取值**:map<long>
- **分组键**:`uid`
- **直接输入**:`ACCOUNT_LOGIN` → **根事件**:`ACCOUNT_LOGIN`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:个人信息(HMAC 存储)

#### `uid_did__visit_dynamic_count__profile`

- **说明**:用户访问设备分布
- **聚合**:`merge_value(value)` 合并并累加值
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:长期
- **取值**:map<long>
- **分组键**:`uid`
- **直接输入**:`uid_did__visit_dynamic_count_top20__1h__slot` → **根事件**:`HTTP_DYNAMIC`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:个人信息(HMAC 存储)

#### `uid_geo_city__visit_dynamic_count__profile`

- **说明**:用户主要访问地区来源
- **聚合**:`merge_value(value)` 合并并累加值
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:长期
- **取值**:map<long>
- **分组键**:`uid`
- **直接输入**:`uid_geo_city__visit_dynamic_count_top20__1h__slot` → **根事件**:`HTTP_DYNAMIC`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:个人信息(HMAC 存储)

#### `uid_timestamp__visit_dynamic_count__profile`

- **说明**:用户访问时间偏好
- **聚合**:`merge_value(value)` 合并并累加值
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:长期
- **取值**:long
- **分组键**:`uid`
- **直接输入**:`uid__visit_dynamic_count__1h__slot` → **根事件**:`HTTP_DYNAMIC`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:内部(未显式标注,按 schema 缺省值)

#### `uid_useragent__visit_dynamic_count__profile`

- **说明**:用户访问User Agent分布
- **聚合**:`merge_value(value)` 合并并累加值
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:长期
- **取值**:map<long>
- **分组键**:`uid`
- **直接输入**:`uid_useragent__visit_dynamic_count_top20__1h__slot` → **根事件**:`HTTP_DYNAMIC`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:个人信息(HMAC 存储)

### 维度:ip(IP)(4 个)

#### `ip__visit_distinct_did__1d__profile`

- **说明**:IP当天最多1小时关联DID数量[1d]
- **聚合**:`max(value)` 取最大值
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:1 天(自然日)
- **取值**:long
- **分组键**:`c_ip`
- **直接输入**:`ip__visit_distinct_did__1h__profile` → **根事件**:`HTTP_DYNAMIC`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:内部(未显式标注,按 schema 缺省值)

#### `ip__visit_distinct_did__1h__profile`

- **说明**:ip每小时关联did数量
- **聚合**:`merge(value)` 按时间槽合并
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:1 小时滚动
- **取值**:map<long>
- **分组键**:`c_ip`
- **直接输入**:`ip__visit_dynamic_distinct_count_did__1h__slot` → **根事件**:`HTTP_DYNAMIC`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:个人信息(HMAC 存储)

#### `ip__visit_distinct_uid__1d__profile`

- **说明**:IP当天最多1小时关联UID数量[1d]
- **聚合**:`max(value)` 取最大值
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:1 天(自然日)
- **取值**:long
- **分组键**:`c_ip`
- **直接输入**:`ip__visit_distinct_uid__1h__profile` → **根事件**:`HTTP_DYNAMIC`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:内部(未显式标注,按 schema 缺省值)

#### `ip__visit_distinct_uid__1h__profile`

- **说明**:ip每小时关联uid数量
- **聚合**:`merge(value)` 按时间槽合并
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:1 小时滚动
- **取值**:map<long>
- **分组键**:`c_ip`
- **直接输入**:`ip__visit_dynamic_distinct_count_uid__1h__slot` → **根事件**:`HTTP_DYNAMIC`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:个人信息(HMAC 存储)

### 维度:did(设备)(4 个)

#### `did__visit_distinct_ip__1d__profile`

- **说明**:did当天最多1小时关联ip数量
- **聚合**:`max(value)` 取最大值
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:1 天(自然日)
- **取值**:long
- **分组键**:`did`
- **直接输入**:`did__visit_distinct_ip__1h__profile` → **根事件**:`HTTP_DYNAMIC`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:内部(未显式标注,按 schema 缺省值)

#### `did__visit_distinct_ip__1h__profile`

- **说明**:用户每小时关联ip数
- **聚合**:`merge(value)` 按时间槽合并
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:1 小时滚动
- **取值**:map<long>
- **分组键**:`did`
- **直接输入**:`did__visit_dynamic_distinct_count_ip__1h__slot` → **根事件**:`HTTP_DYNAMIC`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:个人信息(HMAC 存储)

#### `did__visit_distinct_uid__1d__profile`

- **说明**:did当天最多1小时关联uid数量
- **聚合**:`max(value)` 取最大值
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:1 天(自然日)
- **取值**:long
- **分组键**:`did`
- **直接输入**:`did__visit_distinct_uid__1h__profile` → **根事件**:`HTTP_DYNAMIC`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:内部(未显式标注,按 schema 缺省值)

#### `did__visit_distinct_uid__1h__profile`

- **说明**:did每小时关联uid数
- **聚合**:`merge(value)` 按时间槽合并
- **过滤条件**:无(全部上游数据参与计算)
- **窗口**:1 小时滚动
- **取值**:map<long>
- **分组键**:`did`
- **直接输入**:`did__visit_dynamic_distinct_count_uid__1h__slot` → **根事件**:`HTTP_DYNAMIC`
- **类型**:aggregate(窗口聚合)
- **敏感级别**:个人信息(HMAC 存储)

---

## 五、承载个人信息的变量(20 个)

下列变量的 `sensitivity` 标注为 `pii` 或 `sensitive`,即**变量值本身**可关联到自然人。它们全部位于 `profile` 模块 —— `profile` 是保留期最长的一层(默认 180 天),因此这批变量是隐私风险最集中的地方。

存储与保留期的具体规定见[隐私设计](../security/privacy.md)。HMAC 存储保留了值的**可比较性**(能判断「这次登录的 IP 与历史是否一致」),但去掉了**可读性**(无法从库中还原原文)——这正是风控场景需要的性质。

| 变量名 | 中文说明 | 值类型 | 敏感级别 | 保护方式 | 为什么是个人信息 |
|---|---|---|---|---|---|
| `did__visit_distinct_ip__1h__profile` | 用户每小时关联ip数 | map<long> | 个人信息 | `hash` HMAC 存储 | 值是按 `ip`(IP地址)分组汇总的上游变量 `did__visit_dynamic_distinct_count_ip__1h__slot`分布,把分散的记录汇聚成可按主体追踪的行为轨迹。 |
| `did__visit_distinct_uid__1h__profile` | did每小时关联uid数 | map<long> | 个人信息 | `hash` HMAC 存储 | 值是按 `uid`(账号标识)分组汇总的上游变量 `did__visit_dynamic_distinct_count_uid__1h__slot`分布,把分散的记录汇聚成可按主体追踪的行为轨迹。 |
| `ip__visit_distinct_did__1h__profile` | ip每小时关联did数量 | map<long> | 个人信息 | `hash` HMAC 存储 | 值是按 `did`(设备号)分组汇总的上游变量 `ip__visit_dynamic_distinct_count_did__1h__slot`分布,把分散的记录汇聚成可按主体追踪的行为轨迹。 |
| `ip__visit_distinct_uid__1h__profile` | ip每小时关联uid数量 | map<long> | 个人信息 | `hash` HMAC 存储 | 值是按 `uid`(账号标识)分组汇总的上游变量 `ip__visit_dynamic_distinct_count_uid__1h__slot`分布,把分散的记录汇聚成可按主体追踪的行为轨迹。 |
| `uid__account_login_distinct_did__1h__profile` | 账号登陆成功did按小时去重列表 | mlist<string> | 个人信息 | `hash` HMAC 存储 | 值是账号-登录事件中出现过的 `did`(设备号)去重集合,把分散的记录汇聚成该主体与设备号的关联关系。 |
| `uid__account_login_geocity_last10__profile` | 账号最近10条登录城市 | list<string> | 个人信息 | `hash` HMAC 存储 | 值是最近 10次账号-登录事件的 `geo_city`(城市)原文序列,按时间排列即构成可追踪的行为轨迹。 |
| `uid__account_login_ip_last10__profile` | 账号最近10条登录IP | list<string> | 个人信息 | `hash` HMAC 存储 | 值是最近 10次账号-登录事件的 `c_ip`(客户端 IP地址)原文序列,按时间排列即构成可追踪的行为轨迹。 |
| `uid__account_login_ip_last__profile` | 账号最近登录成功的ip | string | 个人信息 | `hash` HMAC 存储 | 值是账号-登录事件最近一次写入的 `c_ip`(客户端 IP地址)原文,未经聚合,直接指向具体自然人。 |
| `uid__account_token_change_mail__profile` | 帐号修改的邮箱 | string | 个人信息 | `hash` HMAC 存储 | 值是账号-安全凭证修改事件最近一次写入的 `new_token`(邮箱)原文,未经聚合,直接指向具体自然人。 |
| `uid__account_token_change_mobile__profile` | 帐号修改的手机号 | string | 个人信息 | `hash` HMAC 存储 | 值是账号-安全凭证修改事件最近一次写入的 `new_token`(手机号)原文,未经聚合,直接指向具体自然人。 |
| `uid__registration__account__ip__profile` | 用户注册时的ip | string | 个人信息 | `hash` HMAC 存储 | 值是账号-注册事件最近一次写入的 `c_ip`(客户端 IP地址)原文,未经聚合,直接指向具体自然人。 |
| `uid__registration__account__mail__profile` | 用户注册邮箱 | string | 个人信息 | `hash` HMAC 存储 | 值是账号-注册事件最近一次写入的 `register_verification_token`(邮箱)原文,未经聚合,直接指向具体自然人。 |
| `uid__registration__account__mobile__profile` | 用户注册手机 | string | 个人信息 | `hash` HMAC 存储 | 值是账号-注册事件最近一次写入的 `register_verification_token`(手机号)原文,未经聚合,直接指向具体自然人。 |
| `uid__registration__account__username__profile` | 用户注册用户名 | string | 个人信息 | `hash` HMAC 存储 | 值是账号-注册事件最近一次写入的 `user_name`(用户名)原文,未经聚合,直接指向具体自然人。 |
| `uid__visit_distinct_did__1h__profile` | 用户每小时关联did数量 | map<long> | 个人信息 | `hash` HMAC 存储 | 值是按 `did`(设备号)分组汇总的上游变量 `uid__visit_dynamic_distinct_count_did__1h__slot`分布,把分散的记录汇聚成可按主体追踪的行为轨迹。 |
| `uid__visit_distinct_ip__1h__profile` | 用户每小时关联ip数量 | map<long> | 个人信息 | `hash` HMAC 存储 | 值是按 `ip`(IP地址)分组汇总的上游变量 `uid__visit_dynamic_distinct_count_ip__1h__slot`分布,把分散的记录汇聚成可按主体追踪的行为轨迹。 |
| `uid_did__account_login_count_succ__profile` | 账号登录成功did去重列表，并按照次数自增 | map<long> | 个人信息 | `hash` HMAC 存储 | 值是按 `did`(设备号)分组汇总的账号-登录事件分布,把分散的记录汇聚成可按主体追踪的行为轨迹。 |
| `uid_did__visit_dynamic_count__profile` | 用户访问设备分布 | map<long> | 个人信息 | `hash` HMAC 存储 | 值是按 `did`(设备号)分组汇总的上游变量 `uid_did__visit_dynamic_count_top20__1h__slot`分布,把分散的记录汇聚成可按主体追踪的行为轨迹。 |
| `uid_geo_city__visit_dynamic_count__profile` | 用户主要访问地区来源 | map<long> | 个人信息 | `hash` HMAC 存储 | 值是按 `geo_city`(城市)分组汇总的上游变量 `uid_geo_city__visit_dynamic_count_top20__1h__slot`分布,把分散的记录汇聚成可按主体追踪的行为轨迹。 |
| `uid_useragent__visit_dynamic_count__profile` | 用户访问User Agent分布 | map<long> | 个人信息 | `hash` HMAC 存储 | 值是按 `useragent`(浏览器 UA指纹)分组汇总的上游变量 `uid_useragent__visit_dynamic_count_top20__1h__slot`分布,把分散的记录汇聚成可按主体追踪的行为轨迹。 |

---

## 六、附录

### A. 被变量引用最多的 Top 10 事件

「被引用变量数」按依赖链传递计算:只要变量沿 `source` 向上能追溯到该事件即计入。

| 排名 | 事件 | 中文名 | 被引用变量数 | 其中直接引用 | 自有字段数 |
|---:|---|---|---:|---:|---:|
| 1 | `HTTP_DYNAMIC` | 动态资源请求 | 165 | 75 | 30 |
| 2 | `ACCOUNT_LOGIN` | 账号-登录 | 26 | 22 | 7 |
| 3 | `ACCOUNT_REGISTRATION` | 账号-注册 | 20 | 17 | 9 |
| 4 | `HTTP_STATIC` | 静态资源请求 | 12 | 5 | 30 |
| 5 | `ORDER_SUBMIT` | 订单-提交 | 10 | 9 | 30 |
| 6 | `ACCOUNT_TOKEN_CHANGE` | 账号-安全凭证修改 | 8 | 8 | 6 |
| 7 | `TRANSACTION_WITHDRAW` | 支付-取现 | 6 | 6 | 8 |
| 8 | `ACTIVITY_DO` | 营销-参加活动 | 3 | 3 | 8 |
| 9 | `ORDER_CANCEL` | 订单-取消 | 2 | 2 | 6 |
| 10 | `ACCOUNT_CERTIFICATION` | 账号-实名验证 | 1 | 1 | 4 |

### B. 依赖链最深的 10 个变量

深度 = 从该变量出发,沿 `source` 向上经过的变量层数(事件本身不计入)。链路越长,单条事件进入后要传播的计算节点越多;链末的 base 层变量即事件解包点。

| 深度 | 变量 | 依赖链(自下而上) | 根事件 |
|---:|---|---|---|
| 5 | `did__visit_clicks_cv_timediff__5m__rt` | `did__visit_clicks_cv_timediff__5m__rt` ← `did__visit_clicks_avg_timediff__5m__rt` ← `did__visit_clicks_timediff__5m__rt` ← `HTTP_CLICK` ← `HTTP_DYNAMIC` | `HTTP_DYNAMIC` |
| 5 | `ip__visit_clicks_cv_timediff__5m__rt` | `ip__visit_clicks_cv_timediff__5m__rt` ← `ip__visit_clicks_avg_timediff__5m__rt` ← `ip__visit_clicks_timediff__5m__rt` ← `HTTP_CLICK` ← `HTTP_DYNAMIC` | `HTTP_DYNAMIC` |
| 5 | `uid__visit_clicks_cv_timediff__5m__rt` | `uid__visit_clicks_cv_timediff__5m__rt` ← `uid__visit_clicks_avg_timediff__5m__rt` ← `uid__visit_clicks_timediff__5m__rt` ← `HTTP_CLICK` ← `HTTP_DYNAMIC` | `HTTP_DYNAMIC` |
| 4 | `did__visit_clicks_avg_timediff__5m__rt` | `did__visit_clicks_avg_timediff__5m__rt` ← `did__visit_clicks_timediff__5m__rt` ← `HTTP_CLICK` ← `HTTP_DYNAMIC` | `HTTP_DYNAMIC` |
| 4 | `did__visit_clicks_var_timediff__5m__rt` | `did__visit_clicks_var_timediff__5m__rt` ← `did__visit_clicks_timediff__5m__rt` ← `HTTP_CLICK` ← `HTTP_DYNAMIC` | `HTTP_DYNAMIC` |
| 4 | `did__visit_distinct_ip__1d__profile` | `did__visit_distinct_ip__1d__profile` ← `did__visit_distinct_ip__1h__profile` ← `did__visit_dynamic_distinct_count_ip__1h__slot` ← `HTTP_DYNAMIC` | `HTTP_DYNAMIC` |
| 4 | `did__visit_distinct_uid__1d__profile` | `did__visit_distinct_uid__1d__profile` ← `did__visit_distinct_uid__1h__profile` ← `did__visit_dynamic_distinct_count_uid__1h__slot` ← `HTTP_DYNAMIC` | `HTTP_DYNAMIC` |
| 4 | `did__visit_incident_count_top100__1h__slot` | `did__visit_incident_count_top100__1h__slot` ← `did__visit_incident_count__1h__slot` ← `HTTP_INCIDENT` ← `HTTP_DYNAMIC` | `HTTP_DYNAMIC` |
| 4 | `did__visit_static_ratio__5m__rt` | `did__visit_static_ratio__5m__rt` ← `did__visit_count__5m__rt` ← `did__visit_count_static__5m__rt` ← `HTTP_STATIC` | `HTTP_DYNAMIC`、`HTTP_STATIC` |
| 4 | `ip__visit_clicks_avg_timediff__5m__rt` | `ip__visit_clicks_avg_timediff__5m__rt` ← `ip__visit_clicks_timediff__5m__rt` ← `HTTP_CLICK` ← `HTTP_DYNAMIC` | `HTTP_DYNAMIC` |
