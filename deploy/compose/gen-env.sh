#!/usr/bin/env bash
# 生成 .env,凭据为随机值 —— 零默认口令是硬性要求。
set -euo pipefail
cd "$(dirname "$0")"

if [ -f .env ]; then
  echo ".env 已存在。如需重新生成,请先删除它(注意:这会使现有数据卷中的凭据失效)。" >&2
  exit 1
fi

rand() { LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 32; }

cat > .env <<INNER
# 本文件由 gen-env.sh 生成,含真实凭据,已被 .gitignore 忽略 —— 不要提交。
POSTGRES_USER=nebula
POSTGRES_PASSWORD=$(rand)

CLICKHOUSE_USER=nebula
CLICKHOUSE_PASSWORD=$(rand)

REDIS_PASSWORD=$(rand)

NEBULA_HMAC_KEY=$(rand)
INNER

chmod 600 .env
echo "已生成 .env(权限 600)。凭据为随机值,未在任何地方留副本。"
