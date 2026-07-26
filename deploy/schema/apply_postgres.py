#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""应用 PostgreSQL schema。

直连数据库,不经过 `docker exec psql`。早先那个版本只在宿主机上、且容器叫特定
名字时才成立 —— 放进 schema-init 容器里跑就找不到 docker 命令了。建表脚本要能
在「宿主机对着 compose 跑」和「作为容器里的初始化任务跑」两种场景下都成立,
直连是唯一同时满足的方式。

全部脚本都是 CREATE ... IF NOT EXISTS,重复执行安全。
"""
import os
import pathlib
import sys

try:
    import psycopg
except ImportError:  # pragma: no cover
    raise SystemExit(
        "缺少 psycopg。宿主机上执行:pip install 'psycopg[binary]'\n"
        "(容器方式无需手动安装:docker compose run --rm schema-init)")

ROOT = pathlib.Path(__file__).resolve().parent


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


def main() -> int:
    files = sorted((ROOT / "postgres").glob("*.sql"))
    if not files:
        raise SystemExit(f"没有找到任何 SQL 文件:{ROOT / 'postgres'}")
    with psycopg.connect(dsn()) as conn:
        for f in files:
            print(f"  应用 {f.name}", flush=True)
            try:
                # 每个文件一个事务:某个文件失败时,它之前的改动已提交且是幂等的,
                # 修好后重跑不会因为「一半状态」而卡住
                with conn.transaction():
                    conn.execute(f.read_text(encoding="utf-8"))
            except psycopg.Error as e:
                raise SystemExit(f"  失败 {f.name}: {str(e).strip().splitlines()[0]}")
    print("PostgreSQL schema 已应用")
    return 0


if __name__ == "__main__":
    sys.exit(main())
