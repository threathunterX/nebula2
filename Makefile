.DEFAULT_GOAL := help
PYTHON ?= python3

# 🚧 标记的目标依赖尚未实现的组件,当前会提示而非失败。

.PHONY: help
help: ## 显示本帮助
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

# ---------- 校验 ----------

.PHONY: validate
validate: validate-schema validate-seeds docs-check links test-tools privacy-check check-env-untracked secrets-scan test-reference test-engine test-console lint ## 跑全部校验(等同 CI 的检查项)

.PHONY: validate-schema
validate-schema: ## 校验 JSON Schema 自身合法
	@$(PYTHON) -c "import json,pathlib,sys; \
from jsonschema import Draft202012Validator as V; \
ps=sorted(pathlib.Path('packages/domain-schema').glob('*.schema.json')); \
[V.check_schema(json.loads(p.read_text(encoding='utf-8'))) for p in ps]; \
print('schema 合法:%d 个' % len(ps))"

.PHONY: validate-seeds
validate-seeds: ## 校验 seeds 资产符合 schema、引用完整、隐私标注齐全
	@$(PYTHON) tools/validate_seeds.py

.PHONY: links
links: ## 校验文档内部链接可达
	@$(PYTHON) tools/check_doc_links.py

.PHONY: test-tools
test-tools: ## 校验工具自身的测试(隐私检查器等)
	@$(PYTHON) -m unittest discover -s tools/test -q

.PHONY: privacy-check
privacy-check: ## 扫描仓库中是否混入真实个人信息或客户标识
	@$(PYTHON) tools/check_no_pii.py

.PHONY: check-env-untracked
check-env-untracked: ## 断言本机凭据文件没有被纳入版本控制
	@if git ls-files --error-unmatch deploy/compose/.env >/dev/null 2>&1; then \
		echo "deploy/compose/.env 已被纳入版本控制 —— 这个文件含本机真实凭据,绝不能提交。"; \
		echo "请执行:git rm --cached deploy/compose/.env"; \
		exit 1; \
	fi
	@echo "本机凭据文件未进版本库"

.PHONY: secrets-scan
# 两遍:git 历史(与 CI 一致)+ 工作区(--no-git)。只扫历史会漏掉尚未提交的
# 文件 —— 而本地跑这个的目的正是在提交前拦住。
# 未安装 gitleaks 时跳过,但**发现泄露必须失败**。
# 早先这里写成 `gitleaks ... || echo 未安装`,发现泄露也会走进 || 分支变成成功 ——
# 门禁看起来在跑,实际永远是绿的。
secrets-scan: ## 本地跑一遍凭据扫描(需已安装 gitleaks)
	@if command -v gitleaks >/dev/null 2>&1; then \
		gitleaks detect --source . --config .gitleaks.toml --redact --verbose; \
		gitleaks detect --source . --config .gitleaks.toml --redact --verbose --no-git; \
	else \
		echo "未安装 gitleaks,跳过凭据扫描。安装方式见 https://github.com/gitleaks/gitleaks"; \
		echo "  (CI 上不会跳过 —— 推送前建议本地装一个)"; \
	fi

# ---------- 文档 ----------

.PHONY: docs
docs: ## 重新生成自动生成的参考文档
	@$(PYTHON) tools/gen_variable_reference.py
	@$(PYTHON) tools/gen_strategy_reference.py
	@$(PYTHON) tools/gen_seeds_index.py

.PHONY: docs-check
docs-check: ## 校验生成的参考文档与 seeds 一致(CI 门禁)
	@$(PYTHON) tools/gen_variable_reference.py --check
	@$(PYTHON) tools/gen_strategy_reference.py --check
	@$(PYTHON) tools/gen_seeds_index.py --check

# ---------- 代码生成 ----------

.PHONY: gen-java
gen-java: ## 🚧 由 schema 生成 Java 类型
	@echo "🚧 尚未实现。计划:packages/domain-schema/*.schema.json -> apps/engine, apps/console-api"

.PHONY: gen-ts
gen-ts: ## 🚧 由 schema 生成 TypeScript 类型
	@echo "🚧 尚未实现。计划:packages/domain-schema/*.schema.json -> apps/console-web"

# ---------- 构建与测试 ----------

.PHONY: lint
lint: ## 代码风格与静态检查
	@cd apps/collector && out=$$(gofmt -l .); \
		if [ -n "$$out" ]; then echo "以下文件未格式化:"; echo "$$out"; exit 1; fi; \
		go vet ./...
	@echo "collector: 格式与静态检查通过"

.PHONY: test
test: test-reference test-collector test-engine test-console test-tools ## 单元测试

.PHONY: test-console
test-console: ## 控制面测试(认证与授权矩阵)
	@cd apps/console-api && mvn -B -q test

.PHONY: test-engine
test-engine: ## 引擎算子层测试(含与参考引擎的共享向量对照)
	@cd apps/engine && mvn -q -B test

.PHONY: test-collector
test-collector: ## 采集器测试
	@cd apps/collector && go test -cover ./...

.PHONY: build-collector
build-collector: ## 构建采集器二进制
	@cd apps/collector && go build -o nebula-collector ./cmd/nebula-collector \
		&& echo "已构建 apps/collector/nebula-collector"

.PHONY: test-reference
test-reference: ## 参考引擎的规格符合性测试
	@cd packages/reference-engine && node --test 'test/*.test.js'

.PHONY: demo
demo: ## 用参考引擎跑一遍撞库场景
	@cd packages/reference-engine && node run.js

.PHONY: golden-verify
golden-verify: ## 🚧 用 2.0 引擎跑 golden 用例并与 1.x 基线比对
	@echo "🚧 尚未实现。规范见 tests/golden/README.md"

.PHONY: golden-capture
golden-capture: ## 🚧 从容器化的 1.x 引擎捕获基线结果
	@echo "🚧 尚未实现。规范见 tests/golden/README.md"

# ---------- 开发环境 ----------

.PHONY: install-hooks
install-hooks: ## 安装 pre-commit hook(提交前跑凭据与隐私扫描)
	@mkdir -p .git/hooks
	@printf '%s\n' \
		'#!/bin/sh' \
		'set -e' \
		'echo "[pre-commit] 隐私合规检查..."' \
		'$(PYTHON) tools/check_no_pii.py' \
		'if command -v gitleaks >/dev/null 2>&1; then' \
		'  echo "[pre-commit] 凭据扫描..."' \
		'  gitleaks protect --staged --config .gitleaks.toml' \
		'fi' \
		> .git/hooks/pre-commit
	@chmod +x .git/hooks/pre-commit
	@echo "已安装 .git/hooks/pre-commit"

.PHONY: up
up: ## 🚧 启动 Lite 模式(docker compose)
	@echo "🚧 尚未实现。规划见 docs/operations/deployment.md"
