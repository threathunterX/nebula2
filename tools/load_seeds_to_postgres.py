#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 seeds/ 下的风控资产导入 PostgreSQL。

幂等:重复执行覆盖同名记录并递增元数据版本。全部改动在一个事务里 —— 导到一半
失败时,引擎不会看到「一半新一半旧」的资产组合。

早先的版本手写字符串转义拼 SQL,并交给 `docker compose exec psql` 执行。两处都
改了:

- 改用参数化语句。手写转义在这里恰好是安全的(输入是仓库内经过校验的种子文件),
  但它是一种会被后来者照抄到别处的写法,而下一处的输入未必可控。
- 改用直连数据库。走 docker exec 只在宿主机上、且容器叫特定名字时成立,放进
  初始化容器里就找不到 docker 命令了。

生产环境的策略变更应走控制面 API(有 schema 校验、乐观并发与修订历史);本脚本
只负责首次导入与本地开发。
"""
import argparse
import json
import os
import pathlib
import sys

try:
    import psycopg
except ImportError:  # pragma: no cover
    raise SystemExit(
        "缺少 psycopg。宿主机上执行:pip install 'psycopg[binary]'\n"
        "(容器方式无需手动安装:docker compose run --rm seed-load)")

ROOT = pathlib.Path(__file__).resolve().parent.parent
SEEDS = pathlib.Path(os.environ.get("NEBULA_SEEDS_DIR", ROOT / "seeds"))


def dsn() -> str:
    missing = [k for k in ("POSTGRES_USER", "POSTGRES_PASSWORD") if not os.environ.get(k)]
    if missing:
        raise SystemExit("缺少环境变量:" + ", ".join(missing)
                         + "(见 deploy/compose/gen-env.sh)")
    return (
        f"host={os.environ.get('POSTGRES_HOST', '127.0.0.1')} "
        f"port={os.environ.get('POSTGRES_PORT', '5432')} "
        f"dbname={os.environ.get('POSTGRES_DB', 'nebula')} "
        f"user={os.environ['POSTGRES_USER']} "
        f"password={os.environ['POSTGRES_PASSWORD']}"
    )


def load_dir(sub):
    out = []
    d = SEEDS / sub
    if not d.exists():
        return out
    for p in sorted(d.glob("*.json")):
        if p.name == "index.json":
            continue
        out.append(json.loads(p.read_text(encoding="utf-8")))
    return out


def dumps(obj) -> str:
    return json.dumps(obj, ensure_ascii=False, sort_keys=True)


def requires_config_names() -> set:
    """需要接入方配置才能生效的策略,由 index.json 标记。"""
    idx_path = SEEDS / "strategies" / "index.json"
    if not idx_path.exists():
        return set()
    idx = json.loads(idx_path.read_text(encoding="utf-8"))
    return {row.get("name") for row in idx.get("strategies", [])
            if row.get("requires_configuration")}


def tag_names() -> list:
    path = SEEDS / "tags.json"
    if not path.exists():
        return []
    raw = json.loads(path.read_text(encoding="utf-8"))
    items = raw.get("tags", raw) if isinstance(raw, dict) else raw
    out = []
    for t in items:
        name = t.get("name") if isinstance(t, dict) else t
        if name:
            out.append(name)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="只统计,不写库")
    args = ap.parse_args()

    events = load_dir("events")
    variables = load_dir("variables")
    strategies = load_dir("strategies")
    needs = requires_config_names()
    tags = tag_names()

    print(f"  事件 {len(events)} / 变量 {len(variables)} / "
          f"策略 {len(strategies)} / 标签 {len(tags)}")
    if args.dry_run:
        return 0
    if not (events or variables or strategies):
        raise SystemExit(f"没有找到任何种子数据:{SEEDS}")

    with psycopg.connect(dsn()) as conn:
        # 一个事务:导到一半失败时,引擎不会看到「一半新一半旧」的资产组合
        with conn.transaction():
            conn.cursor().executemany(
                "INSERT INTO event_models (name, visible_name, definition) "
                "VALUES (%s, %s, %s::jsonb) "
                "ON CONFLICT (name) DO UPDATE SET "
                "visible_name = EXCLUDED.visible_name, "
                "definition = EXCLUDED.definition, updated_at = now()",
                [(e["name"], e.get("visible_name", e["name"]), dumps(e)) for e in events])

            conn.cursor().executemany(
                "INSERT INTO variables "
                "(name, module, dimension, status, sensitivity, definition) "
                "VALUES (%s, %s, %s, %s, %s, %s::jsonb) "
                "ON CONFLICT (name) DO UPDATE SET "
                "module = EXCLUDED.module, dimension = EXCLUDED.dimension, "
                "status = EXCLUDED.status, sensitivity = EXCLUDED.sensitivity, "
                "definition = EXCLUDED.definition, updated_at = now()",
                [(v["name"], v.get("module", "base"), v.get("dimension", ""),
                  v.get("status") or "enable", v.get("sensitivity", "internal"),
                  dumps(v)) for v in variables])

            conn.cursor().executemany(
                "INSERT INTO strategies (name, visible_name, category, status, score, "
                "tags, requires_config, definition) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, %s::jsonb) "
                "ON CONFLICT (name) DO UPDATE SET "
                "visible_name = EXCLUDED.visible_name, category = EXCLUDED.category, "
                "status = EXCLUDED.status, score = EXCLUDED.score, tags = EXCLUDED.tags, "
                "requires_config = EXCLUDED.requires_config, "
                "definition = EXCLUDED.definition, "
                "version = strategies.version + 1, updated_at = now()",
                [(s["name"], s.get("visible_name", s["name"]), s["category"],
                  s.get("status", "inedit"), int(s.get("score") or 0),
                  list(s.get("tags") or []), s["name"] in needs, dumps(s))
                 for s in strategies])

            conn.cursor().executemany(
                "INSERT INTO risk_tags (name) VALUES (%s) ON CONFLICT (name) DO NOTHING",
                [(t,) for t in tags])

            conn.execute("UPDATE metadata_version SET version = version + 1, "
                         "updated_at = now() WHERE id = 1")

    print("种子资产已导入")
    return 0


if __name__ == "__main__":
    sys.exit(main())
