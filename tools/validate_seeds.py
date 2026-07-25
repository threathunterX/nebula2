#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 seeds/ 下的全部资产是否符合 packages/domain-schema/ 中的 JSON Schema。

CI 门禁。任何一条资产不合规即返回非零退出码。
"""
import json
import pathlib
import sys

try:
    from jsonschema import Draft202012Validator
except ImportError:
    sys.exit("需要 jsonschema:pip install jsonschema")

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCHEMA_DIR = ROOT / "packages" / "domain-schema"
SEEDS = ROOT / "seeds"

# (资产目录, schema 文件, 期望数量)
TARGETS = [
    ("events", "event-model.schema.json", 17),
    ("variables", "variable-model.schema.json", 253),
]


def load_schema(name):
    return json.loads((SCHEMA_DIR / name).read_text(encoding="utf-8"))


def main():
    if not SEEDS.exists():
        sys.exit(f"seeds 目录不存在:{SEEDS}")

    failures = []
    for subdir, schema_name, expected in TARGETS:
        d = SEEDS / subdir
        if not d.exists():
            failures.append(f"{subdir}/ 目录缺失")
            continue

        validator = Draft202012Validator(load_schema(schema_name))
        files = sorted(p for p in d.glob("*.json") if p.name != "index.json")

        if len(files) != expected:
            failures.append(f"{subdir}/ 数量不符:期望 {expected},实际 {len(files)}")

        bad = 0
        for p in files:
            try:
                doc = json.loads(p.read_text(encoding="utf-8"))
            except json.JSONDecodeError as e:
                failures.append(f"{p.relative_to(ROOT)} JSON 解析失败:{e}")
                bad += 1
                continue
            errors = sorted(validator.iter_errors(doc), key=lambda e: list(e.path))
            for e in errors[:3]:
                loc = "/".join(str(x) for x in e.path) or "<root>"
                failures.append(f"{p.relative_to(ROOT)} [{loc}] {e.message}")
            if errors:
                bad += 1

        print(f"  {subdir:<12} {len(files):>4} 个,{len(files) - bad} 合规")

    # 引用完整性:变量的 source 必须指向已存在的事件或变量
    names = set()
    for sub in ("events", "variables"):
        for p in (SEEDS / sub).glob("*.json"):
            if p.name == "index.json":
                continue
            try:
                names.add(json.loads(p.read_text(encoding="utf-8")).get("name"))
            except Exception:
                pass

    dangling = 0
    for p in (SEEDS / "variables").glob("*.json"):
        if p.name == "index.json":
            continue
        try:
            doc = json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            continue
        for src in doc.get("source") or []:
            if src.get("name") and src["name"] not in names:
                failures.append(f"{p.name} 引用了不存在的 source:{src['name']}")
                dangling += 1
    print(f"  引用完整性    {dangling} 处悬空引用")

    # 隐私标注强制:长期保留(profile)且值为可读类型的变量,必须显式声明敏感级别。
    # 这类变量的值最可能承载个人信息,不允许依赖默认值蒙混过关。
    READABLE = {"string", "list", "mlist", "map", "mmap"}
    unannotated = []
    for p in sorted((SEEDS / "variables").glob("*.json")):
        if p.name == "index.json":
            continue
        try:
            doc = json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            continue
        if doc.get("module") == "profile" and doc.get("value_type") in READABLE:
            if "sensitivity" not in doc:
                unannotated.append(doc.get("name", p.name))
    if unannotated:
        for n in unannotated:
            failures.append(
                f"{n}:profile 变量且值为可读类型,必须显式声明 sensitivity"
                f"(见 docs/security/privacy.md)")
    print(f"  隐私标注      {len(unannotated)} 个 profile 变量缺少敏感级别声明")

    if failures:
        print("\n校验失败:")
        for f in failures[:50]:
            print(f"  - {f}")
        if len(failures) > 50:
            print(f"  ... 另有 {len(failures) - 50} 条")
        return 1

    print("\n全部资产校验通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
