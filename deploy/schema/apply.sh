#!/usr/bin/env bash
# 应用数据库 schema。幂等 —— 全部语句都是 IF NOT EXISTS,可重复执行。
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f compose/.env ]; then
  echo "未找到 compose/.env,请先运行 compose/gen-env.sh" >&2
  exit 1
fi
set -a; . compose/.env; set +a

echo "== ClickHouse =="
python3 schema/apply_clickhouse.py
echo "  完成"
