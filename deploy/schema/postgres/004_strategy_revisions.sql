-- 策略修订历史。
--
-- 没有历史时,「昨天这条策略为什么突然报了十倍」是查不出来的:定义表里只有
-- 当前值,阈值被谁在什么时候从 100 改到 10,没有任何痕迹。审计日志记了「发生
-- 过一次修改」,但记不下改前改后的完整定义 —— 那属于业务数据,不该塞进审计表。
--
-- 每次写入存一条**改动之后**的完整快照。回滚就是把某个旧版本重新提交一次,
-- 因此回滚本身也会产生新版本,历史永远只增不改。

CREATE TABLE IF NOT EXISTS strategy_revisions (
    strategy_name   TEXT        NOT NULL,
    version         INTEGER     NOT NULL,
    definition      JSONB       NOT NULL,
    status          TEXT        NOT NULL,
    changed_by      TEXT        NOT NULL,
    change_note     TEXT        NOT NULL DEFAULT '',
    changed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (strategy_name, version)
);

CREATE INDEX IF NOT EXISTS idx_strategy_revisions_time
    ON strategy_revisions (changed_at DESC);

COMMENT ON TABLE strategy_revisions IS
    '策略每次变更后的完整快照。只增不改,回滚表现为一个新版本。';
