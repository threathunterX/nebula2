-- 事件明细表。
--
-- 保留期由 TTL 自动执行,不依赖运维脚本 —— 1.x 的清理逻辑是一段 crontab,
-- 其中「磁盘超 80% 就删最旧的 5 个目录」会静默删除未过期数据,同时在磁盘充足时
-- 又不保证过期数据被删除。两个方向的问题在这里都不存在。
--
-- 字段分级见 docs/security/privacy.md:标注为 sensitive 的字段在采集端就已脱敏,
-- 落到这里的只有脱敏后的值;标注为 pii 的字段以原值流转,由本层的访问控制保护。

CREATE TABLE IF NOT EXISTS nebula.events
(
    -- 主体标识(pii,访问需授权并审计)
    c_ip           LowCardinality(String),
    uid            String,
    did            String,
    sid            String,

    -- 事件本身
    event_name     LowCardinality(String),
    event_time     DateTime64(3),
    event_id       String,

    -- 请求
    host           LowCardinality(String),
    page           String,
    uri_stem       String,
    method         LowCardinality(String),
    status         UInt16,
    referer        String,
    useragent      String,

    -- 地理
    geo_province   LowCardinality(String),
    geo_city       LowCardinality(String),

    -- 业务字段。事件类型众多且字段各异,用 Map 承载增量字段,避免为 17 类事件
    -- 建 17 张表或堆几百列稀疏字段。
    attrs          Map(String, String),

    -- 落库时间,用于排查采集延迟
    ingested_at    DateTime DEFAULT now()
)
ENGINE = MergeTree
PARTITION BY toDate(event_time)
ORDER BY (event_name, c_ip, event_time)
TTL toDateTime(event_time) + INTERVAL 30 DAY
SETTINGS index_granularity = 8192;

-- 按账号与设备查询是风控排查的高频路径,单独建跳数索引
ALTER TABLE nebula.events ADD INDEX IF NOT EXISTS idx_uid uid TYPE bloom_filter GRANULARITY 4;
ALTER TABLE nebula.events ADD INDEX IF NOT EXISTS idx_did did TYPE bloom_filter GRANULARITY 4;
