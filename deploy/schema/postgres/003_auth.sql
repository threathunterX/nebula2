-- 认证与授权。
--
-- 1.x 这一块问题极严重,2.0 整体重写:
--   * 口令哈希:不含盐的单次哈希对弱口令没有实质保护 -> 2.0 用 Argon2id
--   * 服务间调用:令牌若来自静态配置,泄露后既无从察觉也无法轮换;若鉴权逻辑是
--     「来源 IP 在白名单 **或** token 匹配」,两道防线各自都能被单独绕过从任意来源冒充
--     内部身份 -> 2.0 改为哈希存储的服务令牌,白名单与凭据是 AND 关系
--   * 会话:签名密钥若来自静态配置,配置外泄即可离线重放签名
--
-- 零默认口令是硬性要求:本文件不插入任何账号,首次启动时由应用生成随机口令
-- 并只打印一次。

CREATE TABLE IF NOT EXISTS users (
    username        TEXT PRIMARY KEY,
    -- Argon2id 哈希。格式自带算法参数与盐,便于将来平滑升级参数。
    password_hash   TEXT        NOT NULL,
    display_name    TEXT        NOT NULL DEFAULT '',
    roles           TEXT[]      NOT NULL DEFAULT '{}',
    enabled         BOOLEAN     NOT NULL DEFAULT true,
    -- 口令最后修改时间,供「强制定期更换」这类策略使用
    password_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT users_roles_check CHECK (
        roles <@ ARRAY['ADMIN', 'OPERATOR', 'VIEWER']::text[]
    )
);

-- 服务令牌:业务系统调用 /checkRisk 用,与人类账号分开。
--
-- 只存哈希,不存原文 —— 令牌一旦生成就无法再次读取,丢失只能重新签发。
-- 这与「令牌明文写在配置文件里」的做法形成对比:那样的令牌,
-- 泄露后无从察觉也无法轮换。
CREATE TABLE IF NOT EXISTS service_tokens (
    token_id        TEXT PRIMARY KEY,
    token_hash      TEXT        NOT NULL,
    description     TEXT        NOT NULL DEFAULT '',
    scopes          TEXT[]      NOT NULL DEFAULT '{}',
    -- 来源 IP 段限制。与令牌是 **AND** 关系,不是 1.x 的 OR。
    allowed_cidrs   TEXT[]      NOT NULL DEFAULT '{}',
    enabled         BOOLEAN     NOT NULL DEFAULT true,
    expires_at      TIMESTAMPTZ,
    last_used_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT service_tokens_scopes_check CHECK (
        scopes <@ ARRAY['checkRisk', 'metadata:read']::text[]
    )
);

CREATE INDEX IF NOT EXISTS idx_service_tokens_enabled ON service_tokens (enabled)
    WHERE enabled;
