# 贡献指南

> 项目处于早期开发阶段,架构与接口仍可能有较大调整。此阶段最有价值的贡献是**对设计的意见**——尤其是有实际风控运营经验的反馈。

## 参与方式

**讨论设计**:对 [ADR](docs/adr/) 中的决策有不同看法,欢迎开 issue 讨论。这些决策的代价都写在文档里,如果你认为某个代价被低估了,请指出。

**贡献风控知识**:如果你在实际对抗中总结出有效的策略模式,欢迎提交策略模板。这类贡献的价值高于代码。

**代码贡献**:请先在 issue 中确认方向,避免重复劳动。

## 提交要求

### 绝对禁止提交的内容

- 任何形式的凭据:口令、API key、token、私钥、连接串
- 任何真实的客户信息:企业名称、域名、内部地址、员工姓名或邮箱
- 任何真实的个人信息:手机号、证件号、银行卡号、真实用户 ID、真实终端用户 IP
- 任何真实的生产流量样例

示例数据请使用:域名 `example.com` 系,IP 使用 RFC 5737 文档专用段(`198.51.100.0/24`、`203.0.113.0/24`),手机号 `13800138000`,邮箱 `user@example.com`。

CI 会运行 secret scanning 拦截明显的凭据,但**它无法识别所有形式的个人信息**,这一条需要贡献者自觉。这条规则的分量在于**不可撤销**:数据一旦进入公开仓库,即便随后删除、重写历史,已被克隆和 fork 的副本仍然存在,搜索引擎与镜像站也可能已经收录。事后补救的成本远高于事前多看一眼。

### 领域模型的改动

领域模型只在 `packages/domain-schema/` 中定义。**不要手工修改生成的类型文件**。

> 🚧 代码生成尚未实现(`make gen-java` / `make gen-ts` 目前是占位),因此暂无生成产物,也暂无对应的一致性检查。当前已生效的是资产层校验:`make validate-seeds` 会校验 seeds 符合 schema、引用无悬空、隐私标注齐全。

新增一个算子需要同时提供:schema 声明、实现、单元测试、以及文档。这是为了防止重现 1.x 中"声明了但没实现"的问题。

> 🚧 「缺少任何一项即构建失败」的覆盖率门禁尚未实现。当前靠评审把关,算子的规格符合性测试见 [`packages/reference-engine/test/`](packages/reference-engine/test/)。

### 提交信息

使用 [Conventional Commits](https://www.conventionalcommits.org/):

```
feat(engine): 支持 distinct_count 的精确模式
fix(collector): 修正 syslog 驱动的时间戳解析
docs(migration): 补充标准差语义差异说明
```

## 本地检查

前置依赖:

| 用途 | 要求 |
|---|---|
| 资产 schema 校验 | Python 3.10+,`pip install -r tools/requirements.txt` |
| 参考引擎测试 | Node.js 18+ |
| 采集器 | Go 1.22+ |

其余脚本只用标准库,无需额外安装。

```bash
make lint          # 代码风格
make test          # 单元测试
make validate      # schema 与 seeds 校验
make secrets-scan  # 本地跑一遍 gitleaks
```

建议安装 pre-commit hook,在提交前自动执行 secret scanning:

```bash
make install-hooks
```

## 行为准则

本项目采用 [Contributor Covenant 2.1](CODE_OF_CONDUCT.md) 作为行为准则,参与即表示同意遵守。

一句话概括:请保持专业与尊重,技术讨论对事不对人。需要报告不当行为时,
发邮件至 opensource@threathunter.cn(**软件安全漏洞走另一条通道**,见 [SECURITY.md](SECURITY.md))。

## 更新日志

引入了使用方或评估者会察觉到的变化时,请在 [`CHANGELOG.md`](CHANGELOG.md) 的
`[Unreleased]` 段落追加一条 —— **在 PR 里写,不要留到发布时补**。
纯内部重构、typo 修正、CI 微调不必记录。写法与分类见[发布流程](docs/development/release-process.md)。
