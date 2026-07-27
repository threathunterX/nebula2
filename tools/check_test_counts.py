#!/usr/bin/env python3
"""校验文档里写的测试数量与实际一致。

# 为什么要有这个

v0.4.0 与 v0.5.0 **连续两次发布**都栽在同一处:`docs/guide/quickstart.md` 里
「整个项目的测试分布」是手写的,加了测试之后没人回来改。两次都靠发布前人工核对
才发现 —— 而人工核对是会漏的,事实上第一次改完之后第二次又错了。

派生数字应当由检查器盯着,不是由纪律盯着。

# 怎么用

    python3 tools/check_test_counts.py            # 校验
    python3 tools/check_test_counts.py --update   # 按实际数字改文档

**它不会自己跑测试** —— 跑一遍全部测试要几分钟,放进 `make validate` 会让每次校验
都变慢。它读的是各语言测试框架已经产出的报告;报告不存在时**跳过并说明**,而不是
假装通过。
"""

import argparse
import glob
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOC = ROOT / "docs" / "guide" / "quickstart.md"

# 文档里那句话的形态。捕获五个数字,顺序与下面的 SOURCES 一致。
PATTERN = re.compile(
    r"(整个项目的测试分布:参考引擎 )(\d+)(、计算引擎 )(\d+)"
    r"(、控制面 )(\d+)(、管理界面 )(\d+)(、采集器 )(\d+)( 个包)"
)


def surefire_total(rel):
    """Maven surefire 报告里的用例总数。没有报告返回 None。"""
    files = glob.glob(str(ROOT / rel / "target" / "surefire-reports" / "*.txt"))
    if not files:
        return None
    total = 0
    for f in files:
        m = re.search(r"Tests run: (\d+)", Path(f).read_text(encoding="utf-8", errors="replace"))
        if m:
            total += int(m.group(1))
    return total or None


def count_js_tests():
    """参考引擎:实跑一遍读 TAP 输出的 `# pass N`。

    **不能靠数源码里的 `test(` 调用点** —— 初版就是那么写的,数出 78,而实跑是 177。
    差在向量测试:它们从 JSON 文件循环生成用例,源码里只有一处 `test(`。
    这个偏差恰好说明了「派生数字要从事实来,不能从形态推断」。

    这是本检查器唯一会真跑测试的地方,几秒钟。
    """
    import subprocess
    try:
        r = subprocess.run(
            # 与 Makefile 的 test-reference 用同一条命令 —— 不一致的话这里数出来的
            # 是另一个东西(实测:写 "test/" 时匹配不到文件,返回 0)
            ["node", "--test", "test/*.test.js"],
            cwd=ROOT / "packages" / "reference-engine",
            capture_output=True, text=True, timeout=300)
    except (OSError, subprocess.TimeoutExpired):
        return None
    m = re.search(r"^# pass (\d+)", r.stdout, re.M)
    return int(m.group(1)) if m else None


def count_vitest():
    total = 0
    for f in glob.glob(str(ROOT / "apps" / "console-web" / "src" / "**" / "*.test.ts"), recursive=True):
        src = Path(f).read_text(encoding="utf-8")
        total += len(re.findall(r"^\s*it\(", src, re.M))
    return total or None


def count_go_packages():
    """有测试文件的 Go 包数。"""
    pkgs = set()
    for f in glob.glob(str(ROOT / "apps" / "collector" / "**" / "*_test.go"), recursive=True):
        pkgs.add(str(Path(f).parent))
    return len(pkgs) or None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--update", action="store_true", help="按实际数字改文档")
    args = ap.parse_args()

    actual = [
        ("参考引擎", count_js_tests()),
        ("计算引擎", surefire_total("apps/engine")),
        ("控制面", surefire_total("apps/console-api")),
        ("管理界面", count_vitest()),
        ("采集器包数", count_go_packages()),
    ]

    # 拿不到的那几项跳过,但**必须说出来** —— CI 里每个 job 只跑自己那部分测试,
    # 凑不齐五个数字是常态。静默跳过会让这个检查看起来在把关,实际什么也没管。
    missing = [name for name, v in actual if v is None]
    if missing:
        print(f"跳过 {'、'.join(missing)}:拿不到数量"
              "(Java 侧需要先跑过测试,本工具读 surefire 报告而不重跑)。")
    if len(missing) == len(actual):
        print("一项都拿不到,本次没有校验任何东西。")
        return 0

    text = DOC.read_text(encoding="utf-8")
    m = PATTERN.search(text)
    if not m:
        print(f"错误:{DOC.relative_to(ROOT)} 里找不到「整个项目的测试分布」那句话。")
        print("句子改过之后本检查器也要跟着改 —— 否则它会静默失效。")
        return 1

    documented = [int(m.group(i)) for i in (2, 4, 6, 8, 10)]
    diffs = [(name, doc, got) for (name, got), doc in zip(actual, documented)
             if got is not None and doc != got]

    if not diffs:
        checked = [(n, v) for n, v in actual if v is not None]
        print(f"测试数量与文档一致:{'、'.join(f'{n} {v}' for n, v in checked)}")
        return 0

    if args.update:
        if missing:
            print("拒绝更新:有拿不到的项,改了会把它们写成旧值。先跑一遍完整测试。")
            return 1
        groups = list(m.groups())
        for idx, (_, got) in enumerate(actual):
            groups[idx * 2 + 1] = str(got)
        DOC.write_text(text.replace(m.group(0), "".join(groups)), encoding="utf-8")
        print("已更新:" + "、".join(f"{n} {d} → {g}" for n, d, g in diffs))
        return 0

    print("文档里的测试数量与实际不符:")
    for name, doc, got in diffs:
        print(f"  {name}:文档 {doc},实际 {got}")
    print("\n跑 `python3 tools/check_test_counts.py --update` 更新。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
