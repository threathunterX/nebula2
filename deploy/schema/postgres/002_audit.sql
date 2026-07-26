-- 审计日志。
--
-- 全部管理操作与个人信息查询都要留痕。1.x 完全没有审计能力,而个保法要求
-- 对个人信息的处理活动可追溯。见 docs/security/privacy.md。
--
-- 审计表刻意不提供 UPDATE/DELETE 接口,普通管理员无权删除。

CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor           TEXT        NOT NULL,
    action          TEXT        NOT NULL,
    resource_type   TEXT        NOT NULL,
    resource_id     TEXT        NOT NULL DEFAULT '',
    -- 查询个人信息时记录查询条件与命中量,而不是记录返回的数据本身 ——
    -- 把个人信息复制进审计日志会让问题更严重
    detail          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    client_ip       INET,
    outcome         TEXT        NOT NULL DEFAULT 'success'
) PARTITION BY RANGE (occurred_at);

CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit_log (actor, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_resource ON audit_log (resource_type, resource_id, occurred_at DESC);

-- 按月分区。审计日志保留 730 天(个保法要求可追溯,期限从严),
-- 用分区裁剪而非 DELETE 清理 —— 后者在大表上会长时间持锁。
CREATE OR REPLACE FUNCTION ensure_audit_partition(target DATE)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    start_ts DATE := date_trunc('month', target)::date;
    end_ts   DATE := (date_trunc('month', target) + INTERVAL '1 month')::date;
    part     TEXT := 'audit_log_' || to_char(start_ts, 'YYYYMM');
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = part) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF audit_log FOR VALUES FROM (%L) TO (%L)',
            part, start_ts, end_ts);
    END IF;
END $$;

-- 建当月与下月分区,避免月初写入落空
SELECT ensure_audit_partition(CURRENT_DATE);
SELECT ensure_audit_partition((CURRENT_DATE + INTERVAL '1 month')::date);
