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
# 允许的邮箱域名。**按域名精确比对,不做子串匹配。**
#
# 早先这里是一条正则,拿整个邮箱地址去 search。于是域名写成 "not" + 允许域名、或者
# 把允许域名放在自己域名中间(允许域名 + ".攻击者域名"),都会因为含有允许域名的
# 子串而被放行 —— 想混一个真实邮箱进来,只要把允许的域名当作自己域名的一部分即可。
# 这个绕过是发布前复审时用探针实测出来的。
ALLOWED_DOMAINS = {
    "example.com", "example.net", "example.org", "localhost",
    "threathunter.cn", "github.com", "nginx.org", "openresty.org",
    "apache.org", "json-schema.org", "conventionalcommits.org", "shields.io",
}
ALLOWED_PHONE = {"13800138000"}

CHECKS = [
    ("真实手机号", re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)"),
     lambda m, line: m.group(0) not in ALLOWED_PHONE),

    # 加校验位判断:真实身份证号必然通过 GB 11643 的 ISO 7064 mod 11-2 校验,
    # 而形态相同的随机数字串(哈希片段、拼接的时间戳)基本不会。这是提高精确率
    # 而不降低召回率的改动 —— 误报多了,人就会往允许列表里加例外,那才是真的危险。
    ("身份证号", re.compile(r"(?<!\d)[1-9]\d{5}(19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx](?!\d)"),
     lambda m, line: _id_checksum_ok(m.group(0))),

    ("银行卡号", re.compile(r"(?<!\d)(62|4\d|5[1-5])\d{14,17}(?!\d)"),
     lambda m, line: _luhn_ok(m.group(0))),

    ("公网 IP 字面量", re.compile(r"(?<![\d.])((?!10\.|127\.|0\.|169\.254\.|192\.168\.|192\.0\.2\.|198\.51\.100\.|203\.0\.113\.|255\.)"
                                 r"(?:\d{1,3}\.){3}\d{1,3})(?![\d.])"),
     lambda m, line: _is_public_ip(m.group(0)) and not _looks_like_version(m, line)),

    ("非示例域名邮箱", re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"),
     lambda m, line: not _domain_allowed(m.group(0))),
]



def _luhn_ok(number):
    """Luhn 校验(ISO/IEC 7812)。全部实际发行的银行卡号都满足。"""
    total, alt = 0, False
    for ch in reversed(number):
        d = int(ch)
        if alt:
            d *= 2
            if d > 9:
                d -= 9
        total += d
        alt = not alt
    return total % 10 == 0


_ID_WEIGHTS = (7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
_ID_CHECK = "10X98765432"


def _id_checksum_ok(number):
    """中国大陆身份证的校验位(GB 11643,ISO 7064 mod 11-2)。"""
    if len(number) != 18:
        return False
    try:
        total = sum(int(number[i]) * _ID_WEIGHTS[i] for i in range(17))
    except ValueError:
        return False
    return number[17].upper() == _ID_CHECK[total % 11]


def _domain_allowed(email):
    """域名必须完全等于允许列表中的某一项,或是它的子域。

    子域也放行(允许域名前面加一级),但「not + 允许域名」不行(它不以「.允许域名」
    结尾),「允许域名 + 别的后缀」也不行(它的结尾不是允许域名)。
    """
    domain = email.rsplit("@", 1)[-1].lower().rstrip(".")
    for allowed in ALLOWED_DOMAINS:
        if domain == allowed or domain.endswith("." + allowed):
            return True
    return False


# 「产品名 + 空格 + 四段数字」形态的版本号,如 `OpenResty 1.31.1.1`。
# 只认软件名后面紧跟的那个 —— 不放宽成「任何空格之后」,否则
# 「客户端 IP 是 203.0.113.5」这类句子里的真 IP 会被一起放过。
_VERSION_AFTER_NAME = re.compile(
    r"(?i)\b(openresty|nginx|redis|clickhouse|flink|kafka|redpanda|postgres(?:ql)?"
    r"|node|python|java|go|docker|compose|lua(?:jit)?)[\s/v(（\[]+$")


def _looks_like_version(m, line):
    """排除版本号误报。

    User-Agent 里的 Chrome/120.0.0.0、Safari/537.36 这类版本号形态上与 IP
    无法区分,但它们前面必然紧跟斜杠;另外四段版本号常以 .0.0 结尾。

    还有一类是文档里写的「OpenResty 1.31.1.1」或「OpenResty(1.31.1.1)」——
    前面是软件名,中间隔的是空格、斜杠或括号(含全角)。这条是写 OpenResty 埋点
    文档时撞出来的:版本号被判成公网 IP。
    """
    start = m.start()
    if start > 0 and line[start - 1] in "/-":
        return True
    if m.group(0).endswith(".0.0"):
        return True
    if _VERSION_AFTER_NAME.search(line[:start]):
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
    if 224 <= parts[0] <= 239:
        return False           # 组播 224.0.0.0/4 —— 早先只排除了字面量 "224."
    if parts[0] >= 240:
        return False           # 保留段 240.0.0.0/4
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
        print("\n请改用示例值:域名 example.com,手机号 13800138000。")
        print("IP 用 RFC 5737 保留的文档段:192.0.2.x / 198.51.100.x / 203.0.113.x。")
        print("如为误报,请在 tools/check_no_pii.py 的允许列表中说明原因后添加。")
        return 1

    print("隐私合规检查通过:未发现真实个人信息或客户标识")
    return 0


if __name__ == "__main__":
    sys.exit(main())
