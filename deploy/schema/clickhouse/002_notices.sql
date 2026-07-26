-- 风险告警表。
--
-- variable_values 是 2.0 落地的告警可解释性 —— 1.x 该字段被写死为空字符串,
-- 运营看到告警却看不到判定依据。见 docs/migration/from-1x.md。

CREATE TABLE IF NOT EXISTS nebula.notices
(
    notice_time     DateTime64(3),
    subject_key     String,              -- 风险主体的值
    check_type      LowCardinality(String),  -- IP / USER / DeviceID / OrderID
    strategy_name   LowCardinality(String),
    scene_name      LowCardinality(String),  -- VISITOR / ACCOUNT / TRANSACTION / ORDER / MARKETING / OTHER
    decision        LowCardinality(String),  -- accept / review / reject
    risk_score      UInt8,
    expire_at       DateTime64(3),
    tags            Array(LowCardinality(String)),
    is_test         UInt8,                -- test 状态策略产出的告警,不参与线上决策
    remark          String,
    geo_province    LowCardinality(String),
    geo_city        LowCardinality(String),
    uri_stem        String,

    -- 判定依据:哪个指标、当前值、比较符、阈值
    variable_values Map(String, String),

    ingested_at     DateTime DEFAULT now()
)
ENGINE = MergeTree
PARTITION BY toDate(notice_time)
ORDER BY (scene_name, strategy_name, notice_time)
TTL toDateTime(notice_time) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

ALTER TABLE nebula.notices ADD INDEX IF NOT EXISTS idx_subject subject_key TYPE bloom_filter GRANULARITY 4;
