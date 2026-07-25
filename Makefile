.DEFAULT_GOAL := help
PYTHON ?= python3

# 🚧 标记的目标依赖尚未实现的组件,当前会提示而非失败。

.PHONY: help
help: ## 显示本帮助
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

# ---------- 校验 ----------

.PHONY: validate
validate: validate-schema validate-seeds docs-check links test-reference ## 跑全部校验(等同 CI 的检查项)

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

.PHONY: privacy-check
privacy-check: ## 扫描仓库中是否混入真实个人信息或客户标识
	@$(PYTHON) tools/check_no_pii.py

.PHONY: secrets-scan
secrets-scan: ## 本地跑一遍凭据扫描(需已安装 gitleaks)
	@command -v gitleaks >/dev/null 2>&1 \
		&& gitleaks detect --source . --config .gitleaks.toml --verbose \
		|| echo "未安装 gitleaks,跳过。安装方式见 https://github.com/gitleaks/gitleaks"

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
	@cd apps/collector && gofmt -l . | tee /dev/stderr | (! read) && go vet ./...
	@echo "collector: 格式与静态检查通过"

.PHONY: test
test: test-reference test-collector ## 单元测试

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
