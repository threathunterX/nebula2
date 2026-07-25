<!--
感谢贡献。请填写下面的内容并逐项确认检查清单。
不适用的项请勾选并在后面标注「不适用」,不要直接删掉 —— 审阅者需要知道你考虑过。
-->

## 这个 PR 做了什么

<!-- 一两句话说明改动内容。如果关联了 issue,写 Closes #123。 -->

## 为什么要这么改

<!--
说明动机与取舍。本项目的惯例是把代价明确写出来:
这个改动引入了什么复杂度、放弃了什么、有没有更简单但被排除的方案。
-->

## 影响范围

<!-- 勾选全部涉及的部分 -->

- [ ] 参考引擎 `packages/reference-engine/`
- [ ] 领域模型 schema `packages/domain-schema/`
- [ ] 内置资产 `seeds/`(事件 / 变量 / 策略 / 标签)
- [ ] 校验与生成工具 `tools/`
- [ ] 规范性文档(算子语义规格、类型推导规则)
- [ ] 其他文档
- [ ] CI / Makefile / 工程配置

---

## 检查清单

### 1. 本地校验

- [ ] **`make validate` 已在本地跑过并通过**
      (等同 CI 的检查项:schema 合法性、seeds 符合 schema、生成文档一致性、文档链接、参考引擎测试)
- [ ] `make test` 通过
- [ ] 如果 `make validate` 有失败项而我认为应当接受,已在下面「审阅者需要注意的地方」说明原因

<!--
提示:`make lint` 目前是占位实现(各 app 尚无代码),跑通它不代表通过了代码风格检查。
-->

### 2. 数据合规 —— 是否引入真实数据

**这一节不是形式要求。** 1.x 正是因为在公开仓库中残留了真实客户数据和终端用户凭据,
造成了实际的信息泄露。CI 的 gitleaks 与 `check_no_pii.py` 只能拦住明显的形态,
识别不了所有的个人信息。

- [ ] 本 PR **不含**任何真实的个人信息(手机号、证件号、银行卡号、真实用户 ID、
      真实终端用户 IP、设备指纹、Cookie / Token)
- [ ] 本 PR **不含**任何真实的客户或企业标识(企业名称、内部域名、内网地址、
      员工姓名或邮箱)
- [ ] 本 PR **不含**任何凭据(口令、API key、token、私钥、连接串),包括测试用的
- [ ] 本 PR **不含**未经脱敏的生产流量样例或访问日志
- [ ] 新增的示例数据使用了约定的占位值:`example.com` 系域名、RFC 5737 文档专用
      IP 段(`198.51.100.0/24`、`203.0.113.0/24`)、手机号 `13800138000`、
      邮箱 `user@example.com`(约定见 [`seeds/PLACEHOLDERS.md`](../seeds/PLACEHOLDERS.md))
- [ ] `make privacy-check` 通过;如已安装 gitleaks,`make secrets-scan` 也通过

### 3. 领域模型改动(改了 `packages/domain-schema/` 才需要填)

领域模型是整个项目的单一真相源。schema 与生成产物脱节正是 1.x 最严重的顽疾之一
(元数据层声明的能力远多于引擎实际实现的),2.0 要求**声明即实现**。

- [ ] schema 改动已同步更新 `packages/domain-schema/README.md` 中的说明
- [ ] 已跑 `make validate-schema`,schema 自身合法
- [ ] 已跑 `make validate-seeds`,现有 seeds 资产仍然符合改动后的 schema
      (若不符合,已一并更新受影响的 seeds,或在下面说明为什么保留)
- [ ] **已跑 `make docs` 重新生成参考文档**,并把生成结果一并提交
      (`docs/reference/variables.md`、`docs/reference/strategies.md`、
      `seeds/strategies/index.json`)
- [ ] `make docs-check` 通过 —— 生成结果与提交内容一致(这是 CI 门禁)
- [ ] 新增或修改了枚举值时,已同步 `packages/domain-schema/enums.json`
- [ ] 涉及可能承载个人信息的字段时,已声明 `sensitivity` 与 `value_masking`
- [ ] 没有手工编辑任何自动生成的文件(生成文件的开头都有标注)

### 4. 算子 / 语义改动(改了算子行为才需要填)

新增或修改算子必须**同时**具备下面四项,缺一不可 —— 这条规则的存在是为了防止
重现 1.x 中「声明了但没实现」的问题。

- [ ] schema 声明(`packages/domain-schema/`)
- [ ] 参考引擎实现(`packages/reference-engine/src/`)
- [ ] 规格符合性测试,测试名标注了对应的规格条款出处
- [ ] [算子语义规格](../docs/reference/operators.md)已更新:输入输出类型、
      **空窗口行为**、**null 处理**、边界与排序稳定性都已明确定义
- [ ] 如与 1.x 行为不同,已在[迁移指南](../docs/migration/from-1x.md)记录该差异
- [ ] 已考虑该改动对现有 170 条策略判定结果的影响,并说明结论

### 5. 文档

- [ ] 文档中新增的仓库内链接均可达(`make links`)
- [ ] 没有引入外部图床;新增图片放在 `docs/assets/`
- [ ] 没有把尚未实现的能力写成已具备的能力。设计意图请显式标注 🚧,
      仓库当前的实现状态以 [README 的项目状态表](../README.md#-项目状态)为准

### 6. 提交规范

- [ ] 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/)
      (`feat(engine): ...`、`fix(collector): ...`、`docs(migration): ...`)
- [ ] **PR 标题**同样遵循 Conventional Commits —— CI 会检查
- [ ] 已在 [`CHANGELOG.md`](../CHANGELOG.md) 的 `[Unreleased]` 段落追加条目,
      或确认本次改动不需要记录(纯内部重构、typo 修正等)

---

## 审阅者需要注意的地方

<!--
你希望审阅者重点看什么?你自己不确定的地方是什么?
已知的遗留问题、后续计划、故意没做的事,都写在这里。
诚实标注不完善之处比掩盖它们更容易通过审阅。
-->

## 授权确认

- [ ] 我确认拥有本次贡献内容的处置权,并同意以 [Apache-2.0](../LICENSE) 授权
- [ ] 我已阅读并同意遵守[行为准则](../CODE_OF_CONDUCT.md)与[贡献指南](../CONTRIBUTING.md)
