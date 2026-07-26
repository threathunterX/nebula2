#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""应用 PostgreSQL schema。

不依赖 psycopg —— 通过容器内的 psql 执行,避免为一个建表脚本引入 Python 驱动。
"""
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent


def run_sql(path: pathlib.Path):
    sql = path.read_text(encoding="utf-8")
    proc = subprocess.run(
        ["docker", "compose", "-f", str(ROOT / "compose" / "docker-compose.yml"),
         "exec", "-T", "-e", "PGPASSWORD=" + os.environ["POSTGRES_PASSWORD"],
         "postgres", "psql", "-v", "ON_ERROR_STOP=1",
         "-U", os.environ["POSTGRES_USER"], "-d", "nebula"],
        input=sql, text=True, capture_output=True,
    )
    if proc.returncode != 0:
        err = (proc.stderr or proc.stdout).strip().splitlines()
        raise SystemExit(f"  失败 {path.name}:\n    " + "\n    ".join(err[:5]))


def main():
    for f in sorted((ROOT / "schema" / "postgres").glob("*.sql")):
        print(f"  应用 {f.name}")
        run_sql(f)
    return 0


if __name__ == "__main__":
    sys.exit(main())
