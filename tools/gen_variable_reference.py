#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 seeds 资产生成变量参考文档 docs/reference/variables.md。

参考类文档必须由资产自动生成,不手工维护(见 docs/adr/0005-schema-single-source-of-truth.md)。
本脚本是这条原则的执行者:唯一数据来源是 seeds/variables/*.json 与 seeds/events/*.json。

用法:
    python3 tools/gen_variable_reference.py            # 生成/覆盖文档
    python3 tools/gen_variable_reference.py --check    # 只校验,不一致则退出码 1(CI 门禁)
    python3 tools/gen_variable_reference.py -o /dev/stdout

只依赖标准库。输出 UTF-8,中文不转义。
"""
from __future__ import annotations

import argparse
import collections
import difflib
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SEEDS = ROOT / "seeds"
DEFAULT_OUT = ROOT / "docs" / "reference" / "variables.md"

# ---------------------------------------------------------------- 词表
# 这些映射只负责「机器值 → 人类可读」,不携带任何 seeds 里没有的事实。

MODULE_ORDER = ["base", "realtime", "slot", "profile"]
MODULE_LABEL = {
    "base": "base —— 事件层与过滤层(无窗口)",
    "realtime": "realtime —— 5 分钟滑动窗口",
    "slot": "slot —— 1 小时滚动窗口",
    "profile": "profile —— 长期画像",
}
MODULE_SHORT = {
    "base": "事件/过滤层",
    "realtime": "实时层",
    "slot": "小时层",
    "profile": "画像层",
}

DIMENSION_ORDER = ["uid", "ip", "did", "page", "global", ""]
DIMENSION_LABEL = {
    "uid": "账号",
    "ip": "IP",
    "did": "设备",
    "page": "页面",
    "global": "全局",
    "": "无维度",
}

TYPE_ORDER = ["event", "filter", "aggregate", "dual", "sequence", "top"]
TYPE_LABEL = {
    "event": "事件解包",
    "filter": "过滤派生",
    "aggregate": "窗口聚合",
    "dual": "二元运算",
    "sequence": "相邻求差",
    "top": "TopN",
}

VALUE_TYPE_ORDER = ["long", "double", "string", "map", "mmap", "list", "mlist", ""]
VALUE_TYPE_LABEL = {
    "long": "整数",
    "double": "浮点",
    "string": "字符串",
    "map": "映射(key → 值)",
    "mmap": "分槽映射(按时间槽保存的映射)",
    "list": "列表",
    "mlist": "分槽列表(按时间槽保存的列表)",
    "": "无(事件/过滤层不产出值)",
}

METHOD_LABEL = {
    "count": "计数",
    "distinct_count": "去重计数",
    "group_count": "按 key 分组计数",
    "distinct": "去重取值集合",
    "top": "取前 N",
    "last": "取最近一次的值",
    "lastn": "取最近 N 次的值",
    "last_value": "取最近一个时间槽的值",
    "first": "取首次的值",
    "max": "取最大值",
    "sum": "求和",
    "avg": "求均值",
    "stddev": "求标准差",
    "merge": "按时间槽合并",
    "merge_value": "合并并累加值",
    "/": "相除",
    "+": "相加",
    "-": "相减",
    "": "无",
}

OPERATION_LABEL = {
    "==": "等于",
    "!=": "不等于",
    ">": "大于",
    ">=": "大于等于",
    "<": "小于",
    "<=": "小于等于",
    "contains": "包含",
    "not_contains": "不包含",
    "startwith": "以…开头",
    "endwith": "以…结尾",
    "in": "属于",
    "not_in": "不属于",
}

LOGIC_LABEL = {"and": "且", "or": "或", "not": "非"}

# 敏感级别与保护方式(schema 缺省值:sensitivity=internal,value_masking=none)
DEFAULT_SENSITIVITY = "internal"
DEFAULT_MASKING = "none"
SENSITIVITY_ORDER = ["public", "internal", "pii", "sensitive"]
SENSITIVITY_LABEL = {
    "public": "公开",
    "internal": "内部",
    "pii": "个人信息",
    "sensitive": "敏感",
}
SENSITIVITY_NOTE = {
    "public": "可对外展示",
    "internal": "仅系统内部使用",
    "pii": "值可直接关联到自然人",
    "sensitive": "值属于高敏感信息",
}
MASKING_LABEL = {"none": "明文存储", "hash": "HMAC 存储", "partial": "部分掩码"}
PII_LEVELS = ("pii", "sensitive")

# 字段语义:用于说明「为什么这个值是个人信息」,只翻译字段名,不添加资产以外的事实
FIELD_SEMANTIC = {
    "c_ip": "客户端 IP 地址",
    "ip": "IP 地址",
    "did": "设备号",
    "uid": "账号标识",
    "user_name": "用户名",
    "new_token": "新绑定的联系方式",
    "register_verification_token": "注册验证用的联系方式",
    "geo_city": "城市",
    "useragent": "浏览器 UA 指纹",
    "page": "访问页面",
    "timestamp": "时间戳",
}
# 联系方式类字段的具体所指由过滤条件里的 *_type 决定
TOKEN_FIELDS = ("new_token", "register_verification_token")
TOKEN_TYPE_FIELDS = ("token_type", "register_verification_token_type", "login_verification_type")
TOKEN_TYPE_LABEL = {"email": "邮箱", "mail": "邮箱", "mobile": "手机号", "phone": "手机号"}

PRIVACY_DOC = "../security/privacy.md"
PROFILE_RETENTION = "180 天"

TOP_EVENTS_N = 10
DEEPEST_N = 10


# ---------------------------------------------------------------- 载入
def load_assets():
    """读取 seeds 资产。index.json 是派生产物,不作为数据源。"""
    def read_dir(sub):
        d = SEEDS / sub
        if not d.exists():
            sys.exit(f"资产目录缺失:{d}")
        out = []
        for p in sorted(d.glob("*.json")):
            if p.name == "index.json":
                continue
            try:
                out.append(json.loads(p.read_text(encoding="utf-8")))
            except json.JSONDecodeError as e:
                sys.exit(f"{p} JSON 解析失败:{e}")
        return out

    variables = read_dir("variables")
    events = read_dir("events")
    if not variables:
        sys.exit("seeds/variables 下没有变量资产")
    return variables, events


# ---------------------------------------------------------------- 渲染工具
def cell(text):
    """表格单元格:转义竖线、压平换行,空值统一为破折号。"""
    s = (text or "").strip().replace("\n", " ").replace("|", r"\|")
    return s or "—"


def describe(var):
    """变量的中文说明:visible_name 优先,缺失时回落到 remark。"""
    return (var.get("visible_name") or "").strip() or (var.get("remark") or "").strip()


def humanize_seconds(raw):
    try:
        n = int(str(raw).strip())
    except (TypeError, ValueError):
        return str(raw)
    if n % 3600 == 0 and n >= 3600:
        return f"{n // 3600} 小时"
    if n % 60 == 0 and n >= 60:
        return f"{n // 60} 分钟"
    return f"{n} 秒"


def period_text(var):
    """period → 人类可读窗口。base 层的空 period 表示「无窗口」。"""
    period = var.get("period") or {}
    ptype = period.get("type", "")
    value = period.get("value", "")
    if not ptype:
        return "无窗口"
    if ptype == "last_n_seconds":
        return f"{humanize_seconds(value or 0)}滑动"
    if ptype == "hourly":
        n = str(value or "1").strip() or "1"
        return f"{n} 小时滚动"
    if ptype == "last_n_days":
        n = str(value or "1").strip() or "1"
        return f"{n} 天(自然日)"
    if ptype == "ever":
        return "长期"
    return f"{ptype} {value}".strip()


def dimension_text(dim):
    label = DIMENSION_LABEL.get(dim)
    if dim == "":
        return label
    return f"{dim}({label})" if label else dim


def type_text(t):
    label = TYPE_LABEL.get(t)
    return f"{t}({label})" if label else t


def value_type_text(vt, subtype=""):
    if not vt:
        return "—"
    sub = (subtype or "").strip()
    return f"{vt}<{sub}>" if sub else vt


def function_text(var):
    """聚合函数 → 人类可读,如 `lastn(c_ip, N=10)` 取最近 N 次的值。"""
    fn = var.get("function") or {}
    method = (fn.get("method") or "").strip()
    if not method:
        return "—"
    label = METHOD_LABEL.get(method, method)
    obj = (fn.get("object") or "").strip()
    param = (fn.get("param") or "").strip()
    args = []
    if obj:
        args.append(obj)
    if param:
        args.append(f"N={param}" if param.isdigit() else f"key={param}")
    sig = f"`{method}({', '.join(args)})`" if args else f"`{method}()`"
    return f"{sig} {label}"


def sensitivity_of(var):
    """(级别, 是否为 schema 缺省值)。多数变量未显式声明,按 internal 处理。"""
    raw = (var.get("sensitivity") or "").strip()
    return (raw, False) if raw else (DEFAULT_SENSITIVITY, True)


def masking_of(var):
    return (var.get("value_masking") or "").strip() or DEFAULT_MASKING


def sensitivity_text(var):
    """敏感级别 → 人类可读。明文存储是缺省,不赘述;有保护方式时括号标出。"""
    level, _ = sensitivity_of(var)
    label = SENSITIVITY_LABEL.get(level, level)
    masking = masking_of(var)
    if masking != DEFAULT_MASKING:
        return f"{label}({MASKING_LABEL.get(masking, masking)})"
    return label


def iter_conditions(node):
    """展平过滤条件树,取出全部叶子。"""
    if not node:
        return
    children = node.get("condition") or []
    if children:
        for c in children:
            yield from iter_conditions(c)
    else:
        yield node


def token_qualifier(var):
    """从过滤条件里的 *_type 判定联系方式类字段的具体所指(邮箱/手机号)。"""
    for cond in iter_conditions(var.get("filter") or {}):
        if cond.get("object") in TOKEN_TYPE_FIELDS and cond.get("operation") == "==":
            label = TOKEN_TYPE_LABEL.get(str(cond.get("value", "")).strip().lower())
            if label:
                return label
    return ""


def tidy(text):
    """去掉中文字符前多余的空格 —— 代码片段与中文拼接时容易产生。"""
    out = []
    for i, ch in enumerate(text):
        if ch == " " and i + 1 < len(text) and "\u4e00" <= text[i + 1] <= "\u9fff":
            continue
        out.append(ch)
    return "".join(out)


def subject_field(var):
    """变量值里承载个人标识的那个字段。

    优先取 function.object;当它是 `value`(即在上游变量的值上继续聚合)时,
    退回到变量名:复合维度前缀 `{主体}_{被统计字段}` 的后半段,再退回到业务语义段里的已知字段名。
    """
    fn = var.get("function") or {}
    obj = (fn.get("object") or "").strip()
    if obj and obj != "value":
        return obj
    segments = var["name"].split("__")
    prefix = segments[0]
    dim = (var.get("dimension") or "").strip()
    if dim and prefix.startswith(dim + "_"):
        return prefix[len(dim) + 1 :]
    if len(segments) > 1:
        semantic_seg = segments[1]
        for field in sorted(FIELD_SEMANTIC, key=len, reverse=True):
            if semantic_seg.endswith("_" + field):
                return field
    return ""


def pii_reason(var, events, variables_by_name):
    """为什么这个变量的值是个人信息 —— 由聚合字段、聚合方式与上游来源推导。"""
    return tidy(_pii_reason(var, events, variables_by_name))


def _pii_reason(var, events, variables_by_name):
    fn = var.get("function") or {}
    method = (fn.get("method") or "").strip()
    param = (fn.get("param") or "").strip()
    source = (fn.get("source") or "").strip()

    if source in events:
        label = (events[source].get("visible_name") or "").strip()
        origin = f"{label}事件" if label else f"`{source}` 事件"
    elif source in variables_by_name:
        origin = f"上游变量 `{source}`"
    else:
        origin = "上游数据"

    obj = subject_field(var)
    semantic = token_qualifier(var) if obj in TOKEN_FIELDS else ""
    semantic = semantic or FIELD_SEMANTIC.get(obj, "")
    field = (f"`{obj}`" + (f"({semantic})" if semantic else "")) if obj else "个人标识字段"
    subject = semantic or (f"`{obj}`" if obj else "个人标识")

    if method in ("last", "first", "last_value"):
        when = "首次" if method == "first" else "最近一次"
        return f"值是{origin}{when}写入的 {field}原文,未经聚合,直接指向具体自然人。"
    if method == "lastn":
        return (
            f"值是最近 {param or 'N'} 次{origin}的 {field}原文序列,"
            "按时间排列即构成可追踪的行为轨迹。"
        )
    if method == "distinct":
        return (
            f"值是{origin}中出现过的 {field}去重集合,"
            f"把分散的记录汇聚成该主体与{subject}的关联关系。"
        )
    if method in ("group_count", "merge", "merge_value", "top"):
        return (
            f"值是按 {field}分组汇总的{origin}分布,"
            "把分散的记录汇聚成可按主体追踪的行为轨迹。"
        )
    if semantic:
        return f"值取自{origin}的 {field},属于明文个人标识。"
    return f"值取自{origin},资产中已标注为个人信息。"


def filter_text(node, depth=0):
    """过滤条件树 → 人类可读。支持 simple 叶子与 and/or 嵌套。"""
    if not node:
        return ""
    ntype = (node.get("type") or "").strip()
    children = node.get("condition") or []
    if children:
        joiner = f" {LOGIC_LABEL.get(ntype, ntype)} "
        parts = [filter_text(c, depth + 1) for c in children]
        parts = [p for p in parts if p]
        if not parts:
            return ""
        text = joiner.join(parts)
        return f"({text})" if depth > 0 and len(parts) > 1 else text
    source = (node.get("source") or "").strip()
    obj = (node.get("object") or "").strip()
    op = OPERATION_LABEL.get(node.get("operation", ""), node.get("operation", ""))
    value = node.get("value", "")
    value_text = f"`{value}`" if str(value).strip() != "" else "空值"
    field = f"`{source}.{obj}`" if source else f"`{obj}`"
    return f"{field} {op} {value_text}"


# ---------------------------------------------------------------- 依赖图
class Graph:
    """变量 → 上游(变量或事件)的有向图,用于解析来源事件与依赖深度。"""

    def __init__(self, variables, events):
        self.vars = {v["name"]: v for v in variables}
        self.events = {e["name"]: e for e in events}
        self.parents = {
            v["name"]: [s.get("name") for s in (v.get("source") or []) if s.get("name")]
            for v in variables
        }
        self._roots = {}
        self._depth = {}

    def root_events(self, name):
        """向上追溯到根事件集合(seeds/events 里定义的事件)。对环安全。"""
        if name in self._roots:
            return self._roots[name]
        result = self._walk_roots(name, frozenset())
        self._roots[name] = result
        return result

    def _walk_roots(self, name, seen):
        if name in seen:
            return frozenset()
        if name in self.events and name not in self.vars:
            return frozenset([name])
        if name not in self.vars:
            return frozenset()
        var = self.vars[name]
        # base 层的 event 变量自身就是事件的解包,其 source 指向同名事件
        acc = set()
        for parent in self.parents.get(name, []):
            if parent in self.events and (parent not in self.vars or parent == name):
                acc.add(parent)
            else:
                acc |= self._walk_roots(parent, seen | {name})
        if not acc and var.get("type") == "event" and name in self.events:
            acc.add(name)
        return frozenset(acc)

    def depth(self, name):
        """依赖链长度:自身算 1,每向上一层变量 +1(事件不计入)。"""
        if name in self._depth:
            return self._depth[name]
        value = self._walk_depth(name, frozenset())
        self._depth[name] = value
        return value

    def _walk_depth(self, name, seen):
        if name not in self.vars or name in seen:
            return 0
        ups = [
            self._walk_depth(p, seen | {name})
            for p in self.parents.get(name, [])
            if p != name
        ]
        return 1 + max(ups or [0])

    def chain(self, name):
        """最长依赖链的一条具体路径,用于附录展示。链末是 base 层变量。"""
        path = [name]
        seen = {name}
        cur = name
        while True:
            ups = [p for p in self.parents.get(cur, []) if p in self.vars and p not in seen]
            if not ups:
                break
            cur = max(sorted(ups), key=self.depth)
            path.append(cur)
            seen.add(cur)
        return path


# ---------------------------------------------------------------- 文档片段
def counts_table(title, counter, order, labeler, total):
    order_index = {k: i for i, k in enumerate(order)}
    keys = sorted(counter, key=lambda k: (order_index.get(k, len(order)), -counter[k], k))
    lines = [f"**{title}**", "", "| 取值 | 含义 | 数量 | 占比 |", "|---|---|---:|---:|"]
    for k in keys:
        n = counter[k]
        key_cell = f"`{k}`" if k != "" else "(空)"
        lines.append(f"| {key_cell} | {labeler(k)} | {n} | {n * 100 / total:.1f}% |")
    lines.append(f"| **合计** | | **{total}** | 100.0% |")
    lines.append("")
    return lines


def overview_section(variables, events, graph):
    total = len(variables)
    by_module = collections.Counter(v.get("module", "") for v in variables)
    by_dim = collections.Counter(v.get("dimension", "") for v in variables)
    by_type = collections.Counter(v.get("type", "") for v in variables)
    by_vt = collections.Counter(v.get("value_type", "") for v in variables)

    lines = [
        "## 一、概览",
        "",
        f"- 变量总数:**{total}**(全部 `status = enable`)"
        if all(v.get("status") == "enable" for v in variables)
        else f"- 变量总数:**{total}**",
        f"- 事件总数:**{len(events)}**",
        f"- 依赖链最长:**{max(graph.depth(v['name']) for v in variables)}** 层",
        "",
    ]
    lines += counts_table(
        "按模块(计算层)分布",
        by_module,
        MODULE_ORDER,
        lambda k: MODULE_LABEL.get(k, k),
        total,
    )
    lines += counts_table(
        "按维度分布",
        by_dim,
        DIMENSION_ORDER,
        lambda k: DIMENSION_LABEL.get(k, k),
        total,
    )
    lines += counts_table(
        "按变量类型分布", by_type, TYPE_ORDER, lambda k: TYPE_LABEL.get(k, k), total
    )
    lines += counts_table(
        "按值类型分布",
        by_vt,
        VALUE_TYPE_ORDER,
        lambda k: VALUE_TYPE_LABEL.get(k, k),
        total,
    )
    lines += sensitivity_overview(variables, total)
    return lines


def module_distribution(counter):
    """模块分布短语。只有一个模块时不再重复计数(上文已给出总数)。"""
    items = sorted(counter.items(), key=lambda kv: (-kv[1], kv[0]))
    if len(items) == 1:
        return f"`{items[0][0]}` 模块"
    return "、".join(f"`{m}` 模块({n} 个)" for m, n in items)


def sensitivity_overview(variables, total):
    """敏感级别分布。`sensitivity` 缺省为 internal,统计时要把缺省与显式标注分开说清楚。"""
    levels = collections.Counter()
    explicit = collections.Counter()
    maskings = collections.Counter()
    for v in variables:
        level, is_default = sensitivity_of(v)
        levels[level] += 1
        if not is_default:
            explicit[level] += 1
        maskings[masking_of(v)] += 1

    def labeler(k):
        note = SENSITIVITY_NOTE.get(k, "")
        n_explicit = explicit.get(k, 0)
        mark = f"资产中显式标注 {n_explicit} 个" if n_explicit else "全部按 schema 缺省值计入"
        return f"{SENSITIVITY_LABEL.get(k, k)} —— {note}({mark})"

    lines = counts_table("按敏感级别分布", levels, SENSITIVITY_ORDER, labeler, total)
    mask_line = "、".join(
        f"`{k}` {MASKING_LABEL.get(k, k)} {n} 个"
        for k, n in sorted(
            maskings.items(),
            key=lambda kv: (
                ["none", "hash", "partial"].index(kv[0])
                if kv[0] in ("none", "hash", "partial")
                else 99,
                kv[0],
            ),
        )
    )
    pii_vars = [v for v in variables if sensitivity_of(v)[0] in PII_LEVELS]
    pii_modules = collections.Counter(v.get("module", "") for v in pii_vars)
    module_line = module_distribution(pii_modules)
    spread = "全部位于" if len(pii_modules) == 1 else "分布在"
    lines += [
        f"**保护方式(`value_masking`)分布**:{mask_line}。"
        "schema 约束:`sensitivity` 为 `pii` 或 `sensitive` 时,`value_masking` 不允许为 `none`。",
        "",
        f"> **为什么 profile 模块的标注尤其重要**:承载个人信息的 {len(pii_vars)} 个变量{spread} {module_line} —— "
        f"而 `profile` 是保留期最长的一层(默认 {PROFILE_RETENTION},其余层为小时/分钟级窗口)。"
        "长期画像的价值恰恰来自保留可识别的历史,隐私风险也因此最集中。"
        f"新增 `profile` 变量时必须评估其值的敏感级别,详见[隐私设计]({PRIVACY_DOC})。",
        "",
        "> 注意:敏感级别描述的是**变量值本身**,与来源事件字段的敏感级别是两件事 —— "
        "非敏感字段可以聚合出敏感的值(如「账号最近 10 个登录 IP」),"
        "敏感字段也可以聚合出非敏感的值(如「手机号修改次数」)。",
        "",
    ]
    return lines


def naming_section(variables):
    """命名规范:从实际数据归纳,而不是照抄约定。"""
    seg_counts = collections.Counter(len(v["name"].split("__")) for v in variables)
    four = [v for v in variables if len(v["name"].split("__")) == 4]
    prefixes = collections.Counter(v["name"].split("__")[0] for v in four)
    windows = collections.Counter(v["name"].split("__")[-2] for v in four)
    suffixes = collections.Counter(v["name"].split("__")[-1] for v in variables if "__" in v["name"])

    sample = sorted(
        (v for v in four if v.get("module") == "slot" and v.get("dimension") == "ip"),
        key=lambda v: len(v["name"]),
    )
    example = sample[0]["name"] if sample else four[0]["name"]
    parts = example.split("__")

    lines = [
        "## 二、命名规范",
        "",
        "变量名由**双下划线**分段,复合维度在段内用单下划线连接(如 `did_ip`、`uid_geo_city`):",
        "",
        "```",
        "{维度key}__{业务语义}__{窗口}__{模块}",
        "```",
        "",
        f"例:`{example}`",
        "",
        "| 段 | 值 | 含义 |",
        "|---|---|---|",
        f"| 维度 key | `{parts[0]}` | 按该主体分组统计 |",
        f"| 业务语义 | `{parts[1]}` | 统计什么 |",
        f"| 窗口 | `{parts[2]}` | 统计窗口 |",
        f"| 模块 | `{parts[3]}` | 计算层 |",
        "",
        "实际资产中的分段情况:",
        "",
        "| 分段数 | 数量 | 说明 |",
        "|---:|---:|---|",
    ]
    seg_note = {
        1: "base 层事件/过滤变量,直接用事件名(全大写),无分段",
        3: "省略窗口段(多为 profile 长期变量,窗口即「长期」)",
        4: "标准四段式",
        5: "业务语义本身跨两段(少数注册类画像变量)",
    }
    for k in sorted(seg_counts):
        lines.append(f"| {k} | {seg_counts[k]} | {seg_note.get(k, '—')} |")
    lines += [
        "",
        "**维度前缀**(四段式变量):"
        + "、".join(
            f"`{k}` {v} 个" for k, v in sorted(prefixes.items(), key=lambda x: (-x[1], x[0]))
        ),
        "",
        "**窗口段**(四段式变量):"
        + "、".join(
            f"`{k}` {v} 个" for k, v in sorted(windows.items(), key=lambda x: (-x[1], x[0]))
        ),
        "",
        "**模块后缀**:"
        + "、".join(
            f"`{k}` {v} 个" for k, v in sorted(suffixes.items(), key=lambda x: (-x[1], x[0]))
        )
        + "(`rt` 即 realtime)",
        "",
        "> 命名段是**约定**而非强校验,窗口段与 `period` 字段可能不完全一一对应;"
        "以资产中的 `period`、`module` 字段为准,本文档所有窗口列均由 `period` 渲染。",
        "",
    ]
    return lines


def variable_rows(variables, graph):
    rows = []
    for v in sorted(variables, key=lambda x: x["name"]):
        roots = sorted(graph.root_events(v["name"]))
        rows.append(
            "| `{name}` | {desc} | {dim} | {typ} | {vt} | {win} | {src} | {sens} |".format(
                name=v["name"],
                desc=cell(describe(v)),
                dim=cell(dimension_text(v.get("dimension", ""))),
                typ=cell(type_text(v.get("type", ""))),
                vt=cell(value_type_text(v.get("value_type", ""), v.get("value_subtype", ""))),
                win=cell(period_text(v)),
                src=cell("、".join(f"`{r}`" for r in roots)) if roots else "—",
                sens=cell(sensitivity_text(v)),
            )
        )
    return rows


def full_table_section(variables, graph):
    lines = [
        "## 三、变量全表(按模块分组)",
        "",
        "列说明:**窗口**由 `period` 字段渲染;**来源事件**是沿 `source` 依赖链向上追溯到的根事件"
        "(即 `seeds/events/` 中定义的事件),不是直接上游变量;"
        "**敏感级别**由 `sensitivity` 渲染,未显式标注的按 schema 缺省值 `internal`(内部)呈现,"
        "括号内是 `value_masking` 指定的存储保护方式。",
        "",
    ]
    by_module = collections.defaultdict(list)
    for v in variables:
        by_module[v.get("module", "")].append(v)
    modules = sorted(
        by_module, key=lambda m: (MODULE_ORDER.index(m) if m in MODULE_ORDER else 99, m)
    )
    for m in modules:
        group = by_module[m]
        windows = collections.Counter(period_text(v) for v in group)
        window_line = "、".join(
            f"{w}({n} 个)"
            for w, n in sorted(windows.items(), key=lambda kv: (-kv[1], kv[0]))
        )
        lines += [
            f"### {m} —— {MODULE_SHORT.get(m, m)}({len(group)} 个)",
            "",
            f"窗口分布:{window_line}。",
            "",
            "| 变量名 | 说明 | 维度 | 类型 | 值类型 | 窗口 | 来源事件 | 敏感级别 |",
            "|---|---|---|---|---|---|---|---|",
        ]
        lines += variable_rows(group, graph)
        lines.append("")
    return lines


def profile_section(variables, graph):
    profiles = sorted(
        (v for v in variables if v.get("module") == "profile"), key=lambda v: v["name"]
    )
    lines = [
        f"## 四、长期画像变量详述({len(profiles)} 个)",
        "",
        "`profile` 变量刻画主体的长期行为基线,是复用价值最高的一批资产——"
        "策略里「这次行为和这个账号平时不一样」的判断几乎都建立在它们之上。"
        "以下逐个展开聚合函数与过滤条件。",
        "",
    ]
    by_dim = collections.defaultdict(list)
    for v in profiles:
        by_dim[v.get("dimension", "")].append(v)
    dims = sorted(
        by_dim, key=lambda d: (DIMENSION_ORDER.index(d) if d in DIMENSION_ORDER else 99, d)
    )
    for d in dims:
        group = by_dim[d]
        lines += [f"### 维度:{dimension_text(d)}({len(group)} 个)", ""]
        for v in group:
            fn = v.get("function") or {}
            upstream = [s.get("name") for s in (v.get("source") or []) if s.get("name")]
            roots = sorted(graph.root_events(v["name"]))
            flt = filter_text(v.get("filter") or {})
            desc = describe(v)
            lines += [f"#### `{v['name']}`", ""]
            lines.append(f"- **说明**:{desc or '—'}")
            lines.append(f"- **聚合**:{function_text(v)}")
            lines.append(f"- **过滤条件**:{flt if flt else '无(全部上游数据参与计算)'}")
            lines.append(f"- **窗口**:{period_text(v)}")
            lines.append(
                "- **取值**:{vt}".format(
                    vt=value_type_text(v.get("value_type", ""), v.get("value_subtype", ""))
                )
                + (
                    f",语义类别 `{v['value_category']}`"
                    if (v.get("value_category") or "").strip()
                    else ""
                )
            )
            gbk = v.get("groupbykeys") or []
            lines.append(
                "- **分组键**:" + ("、".join(f"`{k}`" for k in gbk) if gbk else "—")
            )
            lines.append(
                "- **直接输入**:"
                + ("、".join(f"`{u}`" for u in upstream) if upstream else "—")
                + " → **根事件**:"
                + ("、".join(f"`{r}`" for r in roots) if roots else "—")
            )
            lines.append(f"- **类型**:{type_text(v.get('type', ''))}")
            level, is_default = sensitivity_of(v)
            suffix = "(未显式标注,按 schema 缺省值)" if is_default else ""
            lines.append(f"- **敏感级别**:{sensitivity_text(v)}{suffix}")
            lines.append("")
    return lines


def pii_section(variables, events, index):
    """承载个人信息的变量。这些值受最长的保留期约束,单列一节便于合规审阅。"""
    by_name = {v["name"]: v for v in variables}
    pii_vars = sorted(
        (v for v in variables if sensitivity_of(v)[0] in PII_LEVELS), key=lambda v: v["name"]
    )
    modules = collections.Counter(v.get("module", "") for v in pii_vars)
    module_line = module_distribution(modules)
    spread = "全部位于" if len(modules) == 1 else "分布在"
    lines = [
        f"## {index}、承载个人信息的变量({len(pii_vars)} 个)",
        "",
        "下列变量的 `sensitivity` 标注为 `pii` 或 `sensitive`,即**变量值本身**可关联到自然人。"
        f"它们{spread} {module_line} —— `profile` 是保留期最长的一层(默认 {PROFILE_RETENTION}),"
        "因此这批变量是隐私风险最集中的地方。",
        "",
        f"存储与保留期的具体规定见[隐私设计]({PRIVACY_DOC})。"
        "HMAC 存储保留了值的**可比较性**(能判断「这次登录的 IP 与历史是否一致」),"
        "但去掉了**可读性**(无法从库中还原原文)——这正是风控场景需要的性质。",
        "",
        "| 变量名 | 中文说明 | 值类型 | 敏感级别 | 保护方式 | 为什么是个人信息 |",
        "|---|---|---|---|---|---|",
    ]
    for v in pii_vars:
        level, _ = sensitivity_of(v)
        masking = masking_of(v)
        lines.append(
            "| `{name}` | {desc} | {vt} | {lvl} | {mask} | {why} |".format(
                name=v["name"],
                desc=cell(describe(v)),
                vt=cell(value_type_text(v.get("value_type", ""), v.get("value_subtype", ""))),
                lvl=cell(SENSITIVITY_LABEL.get(level, level)),
                mask=cell(f"`{masking}` {MASKING_LABEL.get(masking, masking)}"),
                why=cell(pii_reason(v, events, by_name)),
            )
        )
    lines.append("")

    risky = [
        v
        for v in variables
        if sensitivity_of(v)[0] in PII_LEVELS and masking_of(v) == DEFAULT_MASKING
    ]
    if risky:
        lines += [
            "> ⚠️ 以下变量标注为个人信息但未声明保护方式,违反 schema 约束,请修复资产:"
            + "、".join(f"`{v['name']}`" for v in risky)
            + "。",
            "",
        ]
    return lines


def appendix_section(variables, events, graph, index):
    ref = collections.Counter()
    direct = collections.Counter()
    event_names = {e["name"] for e in events}
    for v in variables:
        for r in graph.root_events(v["name"]):
            ref[r] += 1
        for s in v.get("source") or []:
            n = s.get("name")
            if n in event_names:
                direct[n] += 1
    event_label = {e["name"]: (e.get("visible_name") or e.get("remark") or "").strip() for e in events}
    prop_count = {e["name"]: len(e.get("properties") or []) for e in events}

    top = sorted(ref.items(), key=lambda kv: (-kv[1], kv[0]))[:TOP_EVENTS_N]
    lines = [
        f"## {index}、附录",
        "",
        f"### A. 被变量引用最多的 Top {TOP_EVENTS_N} 事件",
        "",
        "「被引用变量数」按依赖链传递计算:只要变量沿 `source` 向上能追溯到该事件即计入。",
        "",
        "| 排名 | 事件 | 中文名 | 被引用变量数 | 其中直接引用 | 自有字段数 |",
        "|---:|---|---|---:|---:|---:|",
    ]
    for i, (name, n) in enumerate(top, 1):
        lines.append(
            f"| {i} | `{name}` | {cell(event_label.get(name, ''))} | {n} | "
            f"{direct.get(name, 0)} | {prop_count.get(name, 0)} |"
        )
    unused = sorted(event_names - set(ref))
    if unused:
        lines += [
            "",
            "未被任何变量引用的事件:" + "、".join(f"`{n}`" for n in unused) + "。",
        ]

    deepest = sorted(
        ((graph.depth(v["name"]), v["name"]) for v in variables), key=lambda x: (-x[0], x[1])
    )[:DEEPEST_N]
    lines += [
        "",
        f"### B. 依赖链最深的 {DEEPEST_N} 个变量",
        "",
        "深度 = 从该变量出发,沿 `source` 向上经过的变量层数(事件本身不计入)。"
        "链路越长,单条事件进入后要传播的计算节点越多;链末的 base 层变量即事件解包点。",
        "",
        "| 深度 | 变量 | 依赖链(自下而上) | 根事件 |",
        "|---:|---|---|---|",
    ]
    for depth, name in deepest:
        rendered = " ← ".join(f"`{p}`" for p in graph.chain(name))
        roots = "、".join(f"`{r}`" for r in sorted(graph.root_events(name))) or "—"
        lines.append(f"| {depth} | `{name}` | {rendered} | {roots} |")
    lines.append("")
    return lines


def render(variables, events):
    graph = Graph(variables, events)
    lines = [
        "# 变量参考",
        "",
        "> **本文件由 `tools/gen_variable_reference.py` 自动生成,请勿手工编辑。**",
        ">",
        f"> 数据来源:`seeds/variables/*.json`({len(variables)} 个变量,不含 `index.json`)"
        f"、`seeds/events/*.json`({len(events)} 个事件)。",
        ">",
        "> 要修改内容,请改 seeds 资产后重新运行 `python3 tools/gen_variable_reference.py`;"
        "CI 通过 `python3 tools/gen_variable_reference.py --check` 校验文档与资产是否一致。",
        ">",
        "> 敏感级别(`sensitivity`)与保护方式(`value_masking`)随资产一同维护:"
        "**新增变量时必须评估其值的敏感级别**,未显式标注即按 schema 缺省值 `internal` 处理;"
        f"标注为 `pii`/`sensitive` 的变量必须声明 `hash` 或 `partial`。详见[隐私设计]({PRIVACY_DOC})。",
        "",
        "变量是在事件流上计算出的统计特征,是策略的输入。"
        "本文档给出全部内置变量的机读事实;概念与设计动机见"
        "[风控数据模型](../concepts/data-model.md)。",
        "",
        "---",
        "",
    ]
    lines += overview_section(variables, events, graph)
    lines += ["---", ""]
    lines += naming_section(variables)
    lines += ["---", ""]
    lines += full_table_section(variables, graph)
    lines += ["---", ""]
    lines += profile_section(variables, graph)
    lines += ["---", ""]
    lines += pii_section(variables, {e["name"]: e for e in events}, "五")
    lines += ["---", ""]
    lines += appendix_section(variables, events, graph, "六")
    text = "\n".join(lines).rstrip() + "\n"
    return text


# ---------------------------------------------------------------- 入口
def main(argv=None):
    parser = argparse.ArgumentParser(description="从 seeds 资产生成变量参考文档")
    parser.add_argument(
        "--check",
        action="store_true",
        help="只校验现有文档是否与 seeds 资产一致,不写文件;不一致返回退出码 1",
    )
    parser.add_argument(
        "-o",
        "--output",
        default=str(DEFAULT_OUT),
        help=f"输出路径(默认 {DEFAULT_OUT.relative_to(ROOT)})",
    )
    args = parser.parse_args(argv)

    variables, events = load_assets()
    text = render(variables, events)
    out = pathlib.Path(args.output)

    if args.check:
        if not out.exists():
            print(f"✗ 文档不存在:{out}", file=sys.stderr)
            print("  运行 python3 tools/gen_variable_reference.py 生成", file=sys.stderr)
            return 1
        current = out.read_text(encoding="utf-8")
        if current == text:
            print(f"✓ {out.name} 与 seeds 资产一致({len(variables)} 个变量,{len(events)} 个事件)")
            return 0
        diff = list(
            difflib.unified_diff(
                current.splitlines(),
                text.splitlines(),
                fromfile=f"{out}(现有)",
                tofile="(由 seeds 生成)",
                lineterm="",
                n=1,
            )
        )
        print(f"✗ {out} 与 seeds 资产不一致,差异 {len(diff)} 行:", file=sys.stderr)
        for line in diff[:40]:
            print("  " + line, file=sys.stderr)
        if len(diff) > 40:
            print(f"  …… 其余 {len(diff) - 40} 行省略", file=sys.stderr)
        print("  运行 python3 tools/gen_variable_reference.py 重新生成", file=sys.stderr)
        return 1

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text, encoding="utf-8")
    print(
        f"✓ 已生成 {out}:{len(text.splitlines())} 行,"
        f"覆盖 {len(variables)} 个变量、{len(events)} 个事件"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
