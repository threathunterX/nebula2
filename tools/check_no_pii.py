#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""隐私合规检查 —— 拦截混入仓库的真实个人信息与客户标识。

CI 门禁。这是对 gitleaks 的补充:gitleaks 查凭据,本脚本查个人信息。
两者都不能保证穷尽,贡献者的自觉仍是第一道防线(见 CONTRIBUTING.md)。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

# 注意:不要把 docs/assets 加进来。那里是规定存放图片的地方,也是最可能混入
# 真实控制台截图与配套说明文件的地方 —— 恰恰最需要扫描。二进制图片会因
# 解码失败被自然跳过,文本文件必须扫。
SKIP_DIRS = {".git", "node_modules", "target", "build", "dist", ".venv", "venv", "__pycache__"}
SKIP_SUFFIX = {".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".pdf", ".zip", ".gz", ".jar", ".woff", ".woff2"}

# 允许的示例值 —— 出现这些不算违规
ALLOWED_DOMAINS = re.compile(r"(example\.(com|net|org)|localhost|threathunter\.cn|github\.com|"
                             r"nginx\.org|openresty\.org|apache\.org|json-schema\.org|"
                             r"conventionalcommits\.org|shields\.io)")
ALLOWED_PHONE = {"13800138000"}

CHECKS = [
    ("真实手机号", re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)"),
     lambda m, line: m.group(0) not in ALLOWED_PHONE),

    ("身份证号", re.compile(r"(?<!\d)[1-9]\d{5}(19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx](?!\d)"),
     lambda m, line: True),

    ("银行卡号", re.compile(r"(?<!\d)(62|4\d|5[1-5])\d{14,17}(?!\d)"),
     lambda m, line: True),

    ("公网 IP 字面量", re.compile(r"(?<![\d.])((?!10\.|127\.|0\.|169\.254\.|192\.168\.|198\.51\.100\.|203\.0\.113\.|224\.|255\.)"
                                 r"(?:\d{1,3}\.){3}\d{1,3})(?![\d.])"),
     lambda m, line: _is_public_ip(m.group(0)) and not _looks_like_version(m, line)),

    ("非示例域名邮箱", re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"),
     lambda m, line: not ALLOWED_DOMAINS.search(m.group(0))),
]



def _looks_like_version(m, line):
    """排除版本号误报。

    User-Agent 里的 Chrome/120.0.0.0、Safari/537.36 这类版本号形态上与 IP
    无法区分,但它们前面必然紧跟斜杠;另外四段版本号常以 .0.0 结尾。
    """
    start = m.start()
    if start > 0 and line[start - 1] in "/-":
        return True
    if m.group(0).endswith(".0.0"):
        return True
    return False


def _is_public_ip(s):
    try:
        parts = [int(x) for x in s.split(".")]
    except ValueError:
        return False
    if len(parts) != 4 or any(p > 255 for p in parts):
        return False           # 版本号之类,非 IP
    if parts[0] == 172 and 16 <= parts[1] <= 31:
        return False           # 私有段
    if parts[0] in (0, 10, 127):
        return False
    return True


def main():
    hits = []
    for path in ROOT.rglob("*"):
        if not path.is_file():
            continue
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        if path.suffix.lower() in SKIP_SUFFIX:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue

        for lineno, line in enumerate(text.splitlines(), 1):
            for label, pattern, keep in CHECKS:
                for m in pattern.finditer(line):
                    if keep(m, line):
                        hits.append((path.relative_to(ROOT), lineno, label, m.group(0)))

    if hits:
        print("发现疑似真实个人信息或客户标识:\n")
        for p, ln, label, val in hits[:80]:
            print(f"  {p}:{ln}  [{label}]  {val}")
        if len(hits) > 80:
            print(f"  ... 另有 {len(hits) - 80} 处")
        print("\n请改用示例值:域名 example.com,IP 198.51.100.x / 203.0.113.x,手机号 13800138000。")
        print("如为误报,请在 tools/check_no_pii.py 的允许列表中说明原因后添加。")
        return 1

    print("隐私合规检查通过:未发现真实个人信息或客户标识")
    return 0


if __name__ == "__main__":
    sys.exit(main())
