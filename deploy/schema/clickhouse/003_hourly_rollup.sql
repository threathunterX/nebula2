-- 小时聚合 —— 用物化视图自动维护,不需要额外的批任务。
--
-- 这是 2.0 取消 1.x「离线重算层」之后,小时级统计的落地方式:数据写入明细表时
-- 由 ClickHouse 增量维护聚合表,不再需要每小时 cron 重放全部事件日志。
-- 取消离线重算的完整理由见 docs/adr/0002-flink-as-unified-engine.md。

CREATE TABLE IF NOT EXISTS nebula.events_hourly
(
    hour           DateTime,
    event_name     LowCardinality(String),
    c_ip           LowCardinality(String),
    request_count  AggregateFunction(count),
    uid_count      AggregateFunction(uniq, String),
    did_count      AggregateFunction(uniq, String),
    page_count     AggregateFunction(uniq, String)
)
ENGINE = AggregatingMergeTree
PARTITION BY toDate(hour)
ORDER BY (event_name, c_ip, hour)
TTL hour + INTERVAL 90 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS nebula.events_hourly_mv
TO nebula.events_hourly
AS SELECT
    toStartOfHour(event_time) AS hour,
    event_name,
    c_ip,
    countState()        AS request_count,
    uniqState(uid)      AS uid_count,
    uniqState(did)      AS did_count,
    uniqState(page)     AS page_count
FROM nebula.events
GROUP BY hour, event_name, c_ip;

-- 告警的小时聚合,用于报表与趋势
CREATE TABLE IF NOT EXISTS nebula.notices_hourly
(
    hour           DateTime,
    scene_name     LowCardinality(String),
    strategy_name  LowCardinality(String),
    decision       LowCardinality(String),
    is_test        UInt8,
    notice_count   AggregateFunction(count),
    subject_count  AggregateFunction(uniq, String)
)
ENGINE = AggregatingMergeTree
PARTITION BY toDate(hour)
ORDER BY (scene_name, strategy_name, hour)
TTL hour + INTERVAL 365 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS nebula.notices_hourly_mv
TO nebula.notices_hourly
AS SELECT
    toStartOfHour(notice_time) AS hour,
    scene_name,
    strategy_name,
    decision,
    is_test,
    countState()             AS notice_count,
    uniqState(subject_key)   AS subject_count
FROM nebula.notices
GROUP BY hour, scene_name, strategy_name, decision, is_test;
