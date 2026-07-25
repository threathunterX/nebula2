#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从策略文件派生 seeds/strategies/index.json。

index.json 是**派生数据**,不应手工维护。策略转换为 2.0 结构后,原 index
仍带着 1.x 的 status(online)与 term_count 字段,与实际文件脱节而无人发现
—— 因为 validate_seeds.py 只校验策略文件本身。本脚本 + `--check` 模式
把这个缺口补上。

用法:
    python3 tools/gen_seeds_index.py           # 重新生成
    python3 tools/gen_seeds_index.py --check   # 校验一致性(CI 用)
"""
import argparse
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SDIR = ROOT / "seeds" / "strategies"
INDEX = SDIR / "index.json"

PLACEHOLDER = re.compile(r"<YOUR_[A-Z_]+>")


def walk(node):
    """遍历条件树的全部节点"""
    if isinstance(node, dict):
        yield node
        for v in node.values():
            yield from walk(v)
    elif isinstance(node, list):
        for v in node:
            yield from walk(v)


def count_conditions(doc):
    """条件叶子节点数 —— 取代 1.x 的 term_count"""
    n = 0
    for part in (doc.get("condition"), (doc.get("delay") or {}).get("condition")):
        for node in walk(part):
            if not isinstance(node, dict):
                continue
            if "cel" in node or ("left" in node and "op" in node):
                n += 1
    return n


def needs_configuration(doc):
    """含占位符,或把页面路径写成字面量 A/B 的骨架策略"""
    for node in walk(doc):
        if not isinstance(node, dict):
            continue
        v = node.get("value")
        if isinstance(v, str):
            if PLACEHOLDER.search(v):
                return True
            # 1.x 遗留:页面路径写成字面量 A / B,不配置则永不命中
            if node.get("object") == "page" and v in ("A", "B"):
                return True
    return False


def build():
    rows = []
    need = []
    for p in sorted(SDIR.glob("*.json")):
        if p.name == "index.json":
            continue
        d = json.loads(p.read_text(encoding="utf-8"))
        rel = f"strategies/{p.name}"
        needs = needs_configuration(d)
        rows.append({
            "requires_configuration": needs,
            "category": d.get("category"),
            "condition_count": count_conditions(d),
            "decision": (d.get("action") or {}).get("decision"),
            "check_type": (d.get("action") or {}).get("check_type"),
            "delay": bool(d.get("delay")),
            "file": rel,
            "name": d.get("name"),
            "remark": d.get("remark", ""),
            "score": d.get("score", 0),
            "status": d.get("status"),
            "tags": d.get("tags") or [],
        })
        if needs:
            need.append(rel)

    rows.sort(key=lambda r: r["file"])
    need.sort()
    return {
        "count": len(rows),
        "kind": "strategy_templates",
        "placeholder_reference": "../PLACEHOLDERS.md",
        "source_table": "nebula.strategy_cust",
        "strategies": rows,
        "strategies_requiring_configuration": {"count": len(need), "files": need},
    }


def dump(obj):
    return json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    built = dump(build())
    if args.check:
        if not INDEX.exists():
            print("✗ index.json 不存在"); return 1
        if INDEX.read_text(encoding="utf-8") != built:
            print("✗ seeds/strategies/index.json 与策略文件不一致,"
                  "请运行 python3 tools/gen_seeds_index.py 并提交结果。")
            return 1
        n = json.loads(built)["count"]
        print(f"✓ index.json 与策略文件一致({n} 条)")
        return 0

    INDEX.write_text(built, encoding="utf-8")
    obj = json.loads(built)
    print(f"已生成 index.json:{obj['count']} 条策略,"
          f"其中 {obj['strategies_requiring_configuration']['count']} 条需要配置后才能生效")
    return 0


if __name__ == "__main__":
    sys.exit(main())
