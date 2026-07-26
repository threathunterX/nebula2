#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""逐条应用 ClickHouse schema。

ClickHouse 的 HTTP 接口一次只接受一条语句,因此需要先按分号拆分。拆分前必须
先剥离注释 —— 否则注释里的分号会切错语句,注释里的中文也会被并进 SQL。
"""
import os
import pathlib
import sys
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent

# 地址可配置:宿主机上是 127.0.0.1,schema-init 容器里是 clickhouse。
# 写死 127.0.0.1 会让这个脚本只在其中一种场景成立。
_missing = [k for k in ("CLICKHOUSE_USER", "CLICKHOUSE_PASSWORD") if not os.environ.get(k)]
if _missing:
    raise SystemExit("缺少环境变量:" + ", ".join(_missing)
                     + "(见 deploy/compose/gen-env.sh)")
BASE = os.environ.get("CLICKHOUSE_URL", "http://127.0.0.1:8123").rstrip("/")
URL = BASE + "/?" + urllib.parse.urlencode({
    "user": os.environ["CLICKHOUSE_USER"],
    "password": os.environ["CLICKHOUSE_PASSWORD"],
})


def statements(sql: str):
    """剥离整行注释后按分号拆分。"""
    cleaned = []
    for line in sql.splitlines():
        stripped = line.strip()
        if stripped.startswith("--") or not stripped:
            continue
        cleaned.append(line)
    for part in "\n".join(cleaned).split(";"):
        s = part.strip()
        if s:
            yield s


def execute(stmt: str):
    req = urllib.request.Request(URL, data=stmt.encode("utf-8"), method="POST")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        raise SystemExit(f"  失败: {stmt[:70]}...\n  {body.splitlines()[0][:200]}")


def main():
    for f in sorted((ROOT / "clickhouse").glob("*.sql")):
        print(f"  应用 {f.name}")
        for stmt in statements(f.read_text(encoding="utf-8")):
            execute(stmt)
    return 0


if __name__ == "__main__":
    sys.exit(main())
