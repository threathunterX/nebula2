#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 seeds/ 下的风控资产导入 PostgreSQL。

幂等:重复执行会覆盖同名记录并递增元数据版本。

不依赖 psycopg —— 生成 SQL 后交给容器内的 psql 执行,避免为一个导入脚本引入
Python 驱动。生产环境的导入应由控制面 API 完成,本脚本用于初始化与本地开发。
"""
import argparse
import json
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SEEDS = ROOT / "seeds"
COMPOSE = ROOT / "deploy" / "compose" / "docker-compose.yml"


def q(s: str) -> str:
    """PostgreSQL 字符串字面量转义。"""
    return "'" + str(s).replace("'", "''") + "'"


def jq(obj) -> str:
    return q(json.dumps(obj, ensure_ascii=False, sort_keys=True))


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


def build_sql():
    lines = ["BEGIN;"]

    events = load_dir("events")
    for e in events:
        lines.append(
            "INSERT INTO event_models (name, visible_name, definition) VALUES "
            f"({q(e['name'])}, {q(e.get('visible_name', e['name']))}, {jq(e)}) "
            "ON CONFLICT (name) DO UPDATE SET "
            "visible_name = EXCLUDED.visible_name, definition = EXCLUDED.definition, "
            "updated_at = now();")

    variables = load_dir("variables")
    for v in variables:
        lines.append(
            "INSERT INTO variables (name, module, dimension, status, sensitivity, definition) VALUES "
            f"({q(v['name'])}, {q(v.get('module', 'base'))}, {q(v.get('dimension', ''))}, "
            f"{q(v.get('status') or 'enable')}, {q(v.get('sensitivity', 'internal'))}, {jq(v)}) "
            "ON CONFLICT (name) DO UPDATE SET "
            "module = EXCLUDED.module, dimension = EXCLUDED.dimension, status = EXCLUDED.status, "
            "sensitivity = EXCLUDED.sensitivity, definition = EXCLUDED.definition, updated_at = now();")

    # 需要配置才能生效的策略,由 index.json 标记
    idx_path = SEEDS / "strategies" / "index.json"
    needs = set()
    if idx_path.exists():
        idx = json.loads(idx_path.read_text(encoding="utf-8"))
        for row in idx.get("strategies", []):
            if row.get("requires_configuration"):
                needs.add(row.get("name"))

    strategies = load_dir("strategies")
    for s in strategies:
        tags = s.get("tags") or []
        arr = "ARRAY[" + ",".join(q(t) for t in tags) + "]::text[]" if tags else "'{}'::text[]"
        lines.append(
            "INSERT INTO strategies "
            "(name, visible_name, category, status, score, tags, requires_config, definition) VALUES "
            f"({q(s['name'])}, {q(s.get('visible_name', s['name']))}, {q(s['category'])}, "
            f"{q(s.get('status', 'inedit'))}, {int(s.get('score') or 0)}, {arr}, "
            f"{'true' if s['name'] in needs else 'false'}, {jq(s)}) "
            "ON CONFLICT (name) DO UPDATE SET "
            "visible_name = EXCLUDED.visible_name, category = EXCLUDED.category, "
            "status = EXCLUDED.status, score = EXCLUDED.score, tags = EXCLUDED.tags, "
            "requires_config = EXCLUDED.requires_config, definition = EXCLUDED.definition, "
            "version = strategies.version + 1, updated_at = now();")

    tags_path = SEEDS / "tags.json"
    tag_count = 0
    if tags_path.exists():
        raw = json.loads(tags_path.read_text(encoding="utf-8"))
        items = raw.get("tags", raw) if isinstance(raw, dict) else raw
        for t in items:
            name = t.get("name") if isinstance(t, dict) else t
            if not name:
                continue
            tag_count += 1
            lines.append(f"INSERT INTO risk_tags (name) VALUES ({q(name)}) "
                         "ON CONFLICT (name) DO NOTHING;")

    lines.append("UPDATE metadata_version SET version = version + 1, updated_at = now() WHERE id = 1;")
    lines.append("COMMIT;")
    return "\n".join(lines), len(events), len(variables), len(strategies), tag_count


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="只输出 SQL,不执行")
    args = ap.parse_args()

    sql, ne, nv, ns, nt = build_sql()
    if args.dry_run:
        print(sql)
        return 0

    for var in ("POSTGRES_USER", "POSTGRES_PASSWORD"):
        if not os.environ.get(var):
            raise SystemExit(f"缺少环境变量 {var}(见 deploy/compose/gen-env.sh)")

    proc = subprocess.run(
        ["docker", "compose", "-f", str(COMPOSE), "exec", "-T",
         "-e", "PGPASSWORD=" + os.environ["POSTGRES_PASSWORD"],
         "postgres", "psql", "-v", "ON_ERROR_STOP=1",
         "-U", os.environ["POSTGRES_USER"], "-d", "nebula"],
        input=sql, text=True, capture_output=True)
    if proc.returncode != 0:
        err = (proc.stderr or proc.stdout).strip().splitlines()
        raise SystemExit("导入失败:\n  " + "\n  ".join(err[:6]))

    print(f"已导入:事件 {ne} 个、变量 {nv} 个、策略 {ns} 条、标签 {nt} 个")
    return 0


if __name__ == "__main__":
    sys.exit(main())
