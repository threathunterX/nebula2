-- 元数据:事件模型、变量、策略。
--
-- 用 JSONB 而非拆成多张关系表。理由:这些对象的权威结构在
-- packages/domain-schema/ 的 JSON Schema 中,拆表等于把同一套结构维护两遍 ——
-- 那正是 1.x 领域模型漂移的根源(Python 与 Java 各写一份)。JSONB + GIN 索引
-- 既能按内容检索,又保持单一真相源。
--
-- 1.x 把策略 JSON 存进 MySQL 的 blob 字段,既无法索引也无法在库层校验。

CREATE TABLE IF NOT EXISTS event_models (
    name            TEXT PRIMARY KEY,
    visible_name    TEXT        NOT NULL,
    definition      JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS variables (
    name            TEXT PRIMARY KEY,
    module          TEXT        NOT NULL,
    dimension       TEXT        NOT NULL DEFAULT '',
    status          TEXT        NOT NULL DEFAULT 'enable',
    -- 变量值本身的敏感级别。与事件字段的级别分开评估 —— 非敏感字段可以聚合出
    -- 敏感的值(如把分散的访问记录汇聚成关联图谱)。见 docs/security/privacy.md。
    sensitivity     TEXT        NOT NULL DEFAULT 'internal',
    definition      JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT variables_module_check
        CHECK (module IN ('base', 'realtime', 'slot', 'profile')),
    CONSTRAINT variables_status_check
        CHECK (status IN ('enable', 'disable')),
    CONSTRAINT variables_sensitivity_check
        CHECK (sensitivity IN ('public', 'internal', 'pii', 'sensitive'))
);

CREATE INDEX IF NOT EXISTS idx_variables_module ON variables (module);
CREATE INDEX IF NOT EXISTS idx_variables_sensitivity ON variables (sensitivity)
    WHERE sensitivity IN ('pii', 'sensitive');
CREATE INDEX IF NOT EXISTS idx_variables_definition ON variables USING GIN (definition);

CREATE TABLE IF NOT EXISTS strategies (
    name            TEXT PRIMARY KEY,
    visible_name    TEXT        NOT NULL,
    category        TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'inedit',
    score           SMALLINT    NOT NULL DEFAULT 0,
    tags            TEXT[]      NOT NULL DEFAULT '{}',
    -- 含占位符、需要接入方配置后才能生效。不配置会恒真或永不命中,
    -- 见 seeds/PLACEHOLDERS.md。
    requires_config BOOLEAN     NOT NULL DEFAULT false,
    definition      JSONB       NOT NULL,
    version         INTEGER     NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT strategies_category_check
        CHECK (category IN ('VISITOR', 'ACCOUNT', 'TRANSACTION', 'ORDER', 'MARKETING', 'OTHER')),
    CONSTRAINT strategies_status_check
        CHECK (status IN ('inedit', 'test', 'online', 'outline')),
    CONSTRAINT strategies_score_check
        CHECK (score BETWEEN 0 AND 100)
);

CREATE INDEX IF NOT EXISTS idx_strategies_status ON strategies (status);
CREATE INDEX IF NOT EXISTS idx_strategies_category ON strategies (category);
CREATE INDEX IF NOT EXISTS idx_strategies_tags ON strategies USING GIN (tags);
CREATE INDEX IF NOT EXISTS idx_strategies_definition ON strategies USING GIN (definition);

CREATE TABLE IF NOT EXISTS risk_tags (
    name            TEXT PRIMARY KEY,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 元数据版本:每次变更递增,引擎据此判断是否需要重新加载。
-- 告警中记录产生它的元数据版本,便于回溯「这条告警是在哪版策略下产生的」。
CREATE TABLE IF NOT EXISTS metadata_version (
    id              SMALLINT PRIMARY KEY DEFAULT 1,
    version         BIGINT      NOT NULL DEFAULT 1,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT metadata_version_single_row CHECK (id = 1)
);
INSERT INTO metadata_version (id, version) VALUES (1, 1) ON CONFLICT (id) DO NOTHING;
