#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成《策略模板参考》—— docs/reference/strategies.md。

数据源:seeds/strategies/(index.json + 170 个策略模板,2.0 schema 结构),
辅以 seeds/events/(字段中文名)与 seeds/variables/(变量中文名)。

2.0 的策略结构见 packages/domain-schema/strategy.schema.json:条件是一棵可嵌套的
布尔树(condition),处置动作独立成 action,「做了 A 之后一段时间内没有做 B」由
delay 表达。本脚本按该结构解析,不再认识 1.x 的扁平 terms 数组。

参考类文档不手工维护(见 docs/README.md「文档约定」)。本脚本是该文档的唯一来源:

    python3 tools/gen_strategy_reference.py            # 写入 docs/reference/strategies.md
    python3 tools/gen_strategy_reference.py --check    # 校验已提交内容与生成结果一致(CI 门禁)
    python3 tools/gen_strategy_reference.py --stdout   # 打印到标准输出

仅依赖标准库。输出不含时间戳等易变内容,保证同一份 seeds 永远生成同一份文档。
"""
import argparse
import datetime
import difflib
import json
import os
import pathlib
import re
import sys
from collections import Counter, defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent
SEEDS = ROOT / "seeds"
DEFAULT_OUT = ROOT / "docs" / "reference" / "strategies.md"

# 「如何读懂一条策略」使用的样例策略:含事件条件、内联计数器、名单处置三类结构,最有代表性
EXAMPLE_STRATEGY = "IP多次登录失败"

# ---------------------------------------------------------------- 展示用词表

DIM_PREFIXES = ("IP", "用户", "设备")

CHECKTYPE_CN = {"IP": "IP", "USER": "账号", "DeviceID": "设备", "OrderID": "订单"}

SUBJECT_CN = {"c_ip": "同一 IP", "did": "同一设备", "uid": "同一账号"}

OP_CN = {
    ">": ">", "<": "<", ">=": "≥", "<=": "≤", "==": "=", "=": "=", "!=": "≠",
    "regex": "匹配正则", "!regex": "不匹配正则",
    "contains": "包含", "!contains": "不包含",
    "in": "属于", "!in": "不属于",
    "startwith": "以…开头", "!startwith": "不以…开头",
    "endwith": "以…结尾", "!endwith": "不以…结尾",
    "empty": "为空", "!empty": "非空",
}

# 文本类算子的右值可能很长(正则特征库),在摘要里需要截断
TEXTUAL_OPS = ("regex", "!regex", "contains", "!contains", "in", "!in",
               "startwith", "!startwith", "endwith", "!endwith")

LOGICAL_CN = {"and": " 且 ", "or": " 或 "}

# condition 树的三类节点(见 strategy.schema.json 的 $defs)
NODE_DESC = {
    ("comparison", "event_field"): "取触发事件的某个字段做比较,是最基础的过滤条件。",
    ("comparison", "counter"): "内联计数器:在策略里就地定义一个窗口统计(等价于临时变量),"
                               "指定源事件、分组键、统计对象、窗口长度与过滤条件。",
    ("comparison", "variable"): "引用 `seeds/variables/` 中已定义的统计变量,复用其计算结果。",
    ("expression", ""): "CEL 表达式,在沙箱中求值并返回 bool。取代 1.x 的 `time`/`getlocation` 等"
                        "专用条款,可用函数见 [CEL 参考](../guide/cel-reference.md)。",
    ("logical", "and"): "布尔组合:全部子条件成立时成立。1.x 迁移过来的策略都是单层 `and`。",
    ("logical", "or"): "布尔组合:任一子条件成立时成立。",
    ("logical", "not"): "布尔组合:子条件不成立时成立。",
}

# 场景分组:(场景名, category, {tag, ...})。tag 未覆盖时落入「<category>-未归类」。
SCENES = [
    ("账号 · 登录与撞库", "ACCOUNT", {"高频登录", "关联登录"}),
    ("账号 · 注册与批量开号", "ACCOUNT", {"高频注册", "关联注册", "邀请注册"}),
    ("账号 · 身份关联异常", "ACCOUNT", {"高频关联", "一天关联"}),
    ("账号 · 访问路径异常", "ACCOUNT", {"跳跃访问"}),
    ("订单 · 高频下单", "ORDER", {"高频下单"}),
    ("订单 · 下单要素高度集中", "ORDER", {"单一下单"}),
    ("订单 · 下单要素异常分散", "ORDER", {"不同下单"}),
    ("订单 · 跨主体关联下单", "ORDER", {"关联下单"}),
    ("订单 · 下单不支付与取消", "ORDER", {"下单不支付", "取消订单"}),
    ("订单 · 深夜与特殊下单", "ORDER", {"午夜下单", "特殊下单"}),
    ("访客 · 高频与单一访问", "VISITOR", {"高频访问", "单一访问"}),
    ("访客 · 爬虫与异常 UA", "VISITOR", {"特殊UA"}),
    ("访客 · 恶意扫描", "VISITOR", {"恶意扫描"}),
    ("访客 · Web 攻击特征", "VISITOR", {"SQL注入", "XSS", "目录遍历", "RFI", "ngx_lua_waf"}),
]

SCENE_NOTE = {
    "账号 · 登录与撞库": "识别撞库、暴力破解、盗号后的批量登录。",
    "账号 · 注册与批量开号": "识别机器注册、养号、邀请返利套利。",
    "账号 · 身份关联异常": "同一主体在短时间内关联到过多其它主体,是代理池、群控设备、共享账号的典型特征。",
    "账号 · 访问路径异常": "正常用户到达登录/注册页前会先加载若干资源;直接打接口说明是脚本。",
    "订单 · 高频下单": "单位时间内下单次数异常。",
    "订单 · 下单要素高度集中": "多笔订单的商品/商户/收货信息高度雷同,典型刷单、薅券。",
    "订单 · 下单要素异常分散": "同一主体的多笔订单要素完全不重合,典型盗卡试单、代下单。",
    "订单 · 跨主体关联下单": "一个 IP/设备下挂多个账号下单,或一个账号跨多 IP/设备下单。",
    "订单 · 下单不支付与取消": "占库存、试探风控、恶意锁定优惠。",
    "订单 · 深夜与特殊下单": "时段异常与金额异常。",
    "访客 · 高频与单一访问": "无需解析业务语义,仅凭 HTTP 流量特征识别爬虫与压测式访问。",
    "访客 · 爬虫与异常 UA": "直接按 User-Agent 特征识别工具类客户端。",
    "访客 · 恶意扫描": "目录扫描、后台探测、大量错误响应。",
    "访客 · Web 攻击特征": "对请求参数做特征匹配,作为 WAF 的补充而非替代。",
}

PLACEHOLDER_MEANING = {
    "<YOUR_PAYMENT_PAGE_PATH>": (
        "能唯一标识你自己**支付/结算页面**的 URL 路径片段,例如 `/order/pay`、`/checkout/confirm`。"
        "策略用它来统计「下单之后有没有真的去付款」以及「进入登录/注册前有没有访问过必要页面」。"
        "比较运算符是 `contains`,填子串即可。"
    ),
}

# ---------------------------------------------------------------- 载入 seeds


def load_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


class Seeds:
    """seeds/ 中与策略文档相关的数据。"""

    def __init__(self, seeds_dir):
        self.dir = seeds_dir
        index = load_json(seeds_dir / "strategies" / "index.json")
        self.index = index
        self.meta = {e["name"]: e for e in index["strategies"]}

        self.strategies = []
        for entry in index["strategies"]:
            doc = load_json(seeds_dir / entry["file"])
            self.strategies.append(doc)
        self.strategies.sort(key=lambda d: (d["category"], d["name"]))

        # 事件:visible_name 与字段中文名。2.0 的事件引用是裸事件名(不再是 [app, name])
        self.event_label = {}
        self.field_label = defaultdict(dict)
        ev_index = load_json(seeds_dir / "events" / "index.json")
        for e in ev_index["events"]:
            doc = load_json(seeds_dir / e["file"])
            name = doc["name"]
            self.event_label[name] = doc.get("visible_name") or name
            for prop in doc.get("properties", []):
                self.field_label[name][prop["name"]] = prop.get("visible_name") or prop["name"]

        # 变量:visible_name
        self.variable_label = {}
        for v in load_json(seeds_dir / "variables" / "index.json")["variables"]:
            self.variable_label[v["name"]] = v.get("visible_name") or ""

    # -- 展示辅助 --------------------------------------------------

    def event_cn(self, event):
        return self.event_label.get(event, event)

    def field_cn(self, event, field):
        """字段中文名。事件自身没有该字段时回落到基础事件 HTTP_DYNAMIC。"""
        label = self.field_label.get(event, {}).get(field)
        if not label:
            label = self.field_label.get("HTTP_DYNAMIC", {}).get(field)
        return f"{label}({field})" if label and label != field else field

    def variable_cn(self, variable):
        label = self.variable_label.get(variable)
        return f"`{variable}`({label})" if label else f"`{variable}`"


# ---------------------------------------------------------------- 通用工具


def fmt_duration(seconds):
    seconds = int(seconds)
    if seconds % 86400 == 0:
        return f"{seconds // 86400} 天"
    if seconds % 3600 == 0:
        return f"{seconds // 3600} 小时"
    if seconds % 60 == 0:
        return f"{seconds // 60} 分钟"
    return f"{seconds} 秒"


def fmt_date(ms):
    """毫秒时间戳 → UTC 日期。固定用 UTC,保证不同时区生成的文档一致。"""
    if ms in (None, 0, ""):
        return "—"          # 模板不设生效时间窗,见 seeds/INVENTORY.md
    return datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).strftime("%Y-%m-%d")


def rel(path):
    try:
        return path.relative_to(ROOT)
    except ValueError:
        return path


def code(text):
    text = str(text)
    fence = "``" if "`" in text else "`"
    pad = " " if text.startswith("`") or text.endswith("`") else ""
    return f"{fence}{pad}{text}{pad}{fence}"


def cell(text):
    """转义为表格单元格可用的一行文本。"""
    return str(text).replace("|", "\\|").replace("\n", " ")


def clip(text, limit=48):
    text = str(text)
    return text if len(text) <= limit else text[: limit - 1] + "…"


def op_cn(op):
    return OP_CN.get(op, op)


def is_blank_check(op, value):
    return op == "!regex" and value in (r"^\s*$", r"^\\s*$")


# ---------------------------------------------------------------- 条件树遍历
#
# condition 是一棵树,节点有三种(见 strategy.schema.json):
#   logical     {"op": "and|or|not", "conditions": [...]}
#   expression  {"cel": "...", "remark": "..."}
#   comparison  {"left": <operand>, "op": "...", "right": <operand>}
# 下面所有遍历都要能处理任意嵌套,即使当前数据只有单层 and。


def node_kind(node):
    if "conditions" in node:
        return "logical"
    if "cel" in node:
        return "expression"
    return "comparison"


def walk(node):
    """先序遍历条件树的全部节点。"""
    yield node
    if node_kind(node) == "logical":
        for child in node["conditions"]:
            yield from walk(child)


def leaves(node):
    """只产出叶子节点(comparison / expression)。"""
    for n in walk(node):
        if node_kind(n) != "logical":
            yield n


def conditions_of(doc, include_delay=True):
    """策略的全部条件树根节点:主条件 +(可选)delay 条件。"""
    yield doc["condition"]
    if include_delay and doc.get("delay"):
        yield doc["delay"]["condition"]


def all_nodes(doc, include_delay=True):
    for root in conditions_of(doc, include_delay):
        yield from walk(root)


def all_leaves(doc, include_delay=True):
    for root in conditions_of(doc, include_delay):
        yield from leaves(root)


def flatten_and(node):
    """把顶层 and 摊平成条款列表,便于按「主条件 / 前置过滤」分类叙述。

    非 and 节点(单条比较、or、not、CEL)原样作为唯一一项返回 —— 嵌套结构由
    render_cond 递归渲染,不在这里展开。
    """
    if node_kind(node) == "logical" and node["op"] == "and":
        return list(node["conditions"])
    return [node]


def comparisons_with(doc, kind, include_delay=True):
    """左值为指定 kind 的全部比较节点。"""
    out = []
    for n in all_leaves(doc, include_delay):
        if node_kind(n) == "comparison" and n["left"].get("kind") == kind:
            out.append(n)
    return out


def filter_leaves(f):
    """计数器 filter 的叶子(type=simple)。filter 自身也可嵌套 and/or/not。"""
    if not f:
        return
    if f.get("type") in ("and", "or", "not"):
        for c in f.get("condition") or []:
            yield from filter_leaves(c)
    else:
        yield f


def conjunctive_filter_leaves(f):
    """只产出**必然成立**的过滤叶子(纯 and 路径上的)。

    or / not 分支下的条件不是「一定被计数器过滤掉」的,不能拿来给摘要去重。
    """
    if not f:
        return
    typ = f.get("type")
    if typ == "and":
        for c in f.get("condition") or []:
            yield from conjunctive_filter_leaves(c)
    elif typ in ("or", "not"):
        return
    else:
        yield f


def prune_blank_filters(f):
    """摘要里省略 `page 非空` 这类噪声过滤。整棵子树被剪空时返回 None。

    只在**纯 and 路径**上剪 —— or / not 分支里的条件是语义的一部分,剪掉会改变含义。
    """
    if not f:
        return None
    typ = f.get("type")
    if typ in ("or", "not"):
        return f
    if typ == "and":
        kids = [x for x in (prune_blank_filters(c) for c in f.get("condition") or []) if x]
        if not kids:
            return None
        if len(kids) == 1:
            return kids[0]
        out = dict(f)
        out["condition"] = kids
        return out
    if is_blank_check(f.get("operation"), f.get("value")):
        return None
    return f


# ---------------------------------------------------------------- 条件渲染


def compare(left, op, right):
    if not op:
        return left
    return f"{left} {op_cn(op)} {right}"


def render_filter(seeds, event, f, short=False, depth=0):
    """计数器自身的过滤条件,结构同 variable-model 的 filter,同样可以嵌套。"""
    typ = f.get("type")
    if typ in ("and", "or"):
        joiner = "、" if typ == "and" else " 或 "
        subs = [render_filter(seeds, event, c, short, depth + 1)
                for c in f.get("condition") or []]
        text = joiner.join(subs)
        return f"({text})" if depth and len(subs) > 1 else text
    if typ == "not":
        inner = "、".join(render_filter(seeds, event, c, short, depth + 1)
                         for c in f.get("condition") or [])
        return f"非({inner})"
    left = seeds.field_cn(event, f.get("object"))
    op, value = f.get("operation"), f.get("value")
    if is_blank_check(op, value):
        return f"{left} 非空"
    if op in TEXTUAL_OPS:
        return compare(left, op, code(clip(value) if short else value))
    return compare(left, op, code(value))


def render_counter(seeds, counter, short=False):
    event = counter["event"]
    window = fmt_duration(counter["window"])
    if counter["algorithm"] == "distinct_count":
        operand = "、".join(seeds.field_cn(event, o) for o in counter.get("operand") or [])
        stat = f"的{operand}去重数" if short else f"去重统计 {operand} 的个数"
    elif counter["algorithm"] == "sum":
        operand = "、".join(seeds.field_cn(event, o) for o in counter.get("operand") or [])
        stat = f"的{operand}求和" if short else f"对 {operand} 求和"
    else:
        stat = "的次数" if short else "累计事件次数"
    f = counter.get("filter")
    if short:
        # 摘要里省略 `page 非空` 这类噪声过滤
        f = prune_blank_filters(f)
    cond_txt = ""
    if f:
        joined = render_filter(seeds, event, f, short)
        cond_txt = f"({joined})" if short else f"(满足 {joined})"
    if short:
        return f"{window}内「{seeds.event_cn(event)}」事件{cond_txt}{stat}"
    groupby = "、".join(seeds.field_cn(event, g) for g in counter["groupby"])
    return f"最近 {window}内、按 {groupby} 分组的「{seeds.event_cn(event)}」事件{cond_txt}{stat}"


def render_operand(seeds, event, operand, short=False):
    kind = operand.get("kind")
    if kind == "constant":
        return code(operand.get("value"))
    if kind == "event_field":
        field = seeds.field_cn(event, operand["field"])
        return field if short else f"「{seeds.event_cn(event)}」事件的{field}"
    if kind == "variable":
        label = seeds.variable_cn(operand["variable"])
        return f"变量 {label}" if short else f"变量 {label} 的取值"
    if kind == "counter":
        return render_counter(seeds, operand["counter"], short)
    return str(kind)


def render_comparison(seeds, event, node, short=False):
    left = render_operand(seeds, event, node["left"], short)
    op, right = node.get("op"), node.get("right")
    if not op or right is None:
        return left
    if right.get("kind") == "constant":
        value = right.get("value")
        if is_blank_check(op, value):
            return f"{left} 非空"
        if op in TEXTUAL_OPS:
            return compare(left, op, code(clip(value) if short else value))
        return compare(left, op, code(value))
    return compare(left, op, render_operand(seeds, event, right, short))


def render_expression(node, short=False):
    cel = node["cel"]
    text = f"CEL {code(clip(cel, 56) if short else cel)}"
    remark = (node.get("remark") or "").strip()
    return f"{text}({remark})" if remark else text


def render_cond(seeds, event, node, short=False, depth=0):
    """递归渲染条件树。嵌套的 and/or/not 会加括号,保证读起来无歧义。"""
    kind = node_kind(node)
    if kind == "logical":
        op = node["op"]
        subs = [render_cond(seeds, event, c, short, depth + 1) for c in node["conditions"]]
        if op == "not":
            return "非(" + " 且 ".join(subs) + ")"
        text = LOGICAL_CN[op].join(subs)
        return f"({text})" if depth and len(subs) > 1 else text
    if kind == "expression":
        return render_expression(node, short)
    return render_comparison(seeds, event, node, short)


# ---------------------------------------------------------------- 策略级派生


def trigger_event(doc):
    return (doc.get("trigger") or {}).get("event", "")


def stat_dimension(doc):
    """策略实际的统计口径(分组键):优先取计数器的 groupby,其次取变量引用的 keys,
    最后回落到处置动作的取值字段。"""
    for node in comparisons_with(doc, "counter"):
        groupby = node["left"]["counter"].get("groupby")
        if groupby:
            return groupby[0]
    for node in comparisons_with(doc, "variable"):
        keys = node["left"].get("keys")
        if keys:
            return keys[0]
    trigger_keys = (doc.get("trigger") or {}).get("keys")
    if trigger_keys:
        return trigger_keys[0]
    return doc["action"]["check_value"]


def threshold_nodes(doc):
    """带阈值的比较节点(左值是计数器或变量)。"""
    return [n for n in all_leaves(doc)
            if node_kind(n) == "comparison"
            and n["left"].get("kind") in ("counter", "variable")
            and n.get("op")]


VAR_WINDOW_RE = re.compile(r"__(\d+[smhd])__")

VAR_UNIT_SECONDS = {"s": 1, "m": 60, "h": 3600, "d": 86400}


def threshold_signature(doc):
    """主阈值签名,例如 `>5 / 10 分钟`,用于比对镜像策略之间的阈值差异。

    一条策略可能有多个阈值条件(例如「次数 > 5 且页面数 <= 4」),这里取最能代表
    该策略量级的那个:优先取「大于」类比较,其次取第一个。
    """
    nodes = threshold_nodes(doc)
    if not nodes:
        return "无阈值(单事件特征匹配)"
    n = next((x for x in nodes if x["op"] in (">", ">=")), nodes[0])
    value = n["right"]["value"]
    if n["left"]["kind"] == "counter":
        window = fmt_duration(n["left"]["counter"]["window"])
    else:
        m = VAR_WINDOW_RE.search(n["left"]["variable"])
        if m:
            num, unit = int(m.group(1)[:-1]), m.group(1)[-1]
            window = fmt_duration(num * VAR_UNIT_SECONDS.get(unit, 1))
        else:
            window = "—"
    return f"{op_cn(n['op'])}{value} / {window}"


def counted_conditions(doc):
    """内联计数器的 filter 里已经表达过的过滤条件,用 (字段, 运算符, 取值) 表示。"""
    seen = set()
    for node in comparisons_with(doc, "counter"):
        for f in conjunctive_filter_leaves(node["left"]["counter"].get("filter")):
            seen.add((f.get("object"), f.get("operation"), f.get("value")))
    return seen


def summarize_conditions(seeds, doc, root):
    """把一棵条件树归纳成「主条件 / 前置过滤 / 另需」三段。"""
    event = trigger_event(doc)
    counted = counted_conditions(doc)
    main, filters, extras = [], [], []
    for node in flatten_and(root):
        kind = node_kind(node)
        if kind == "logical":
            extras.append(render_cond(seeds, event, node, short=True))
            continue
        if kind == "expression":
            extras.append(render_expression(node, short=True))
            continue
        left_kind = node["left"].get("kind")
        if left_kind in ("counter", "variable"):
            main.append(render_comparison(seeds, event, node, short=True))
        elif left_kind == "event_field":
            value = (node.get("right") or {}).get("value", "")
            if is_blank_check(node.get("op"), value):
                continue  # `page 非空` 是绝大多数策略都有的噪声过滤,不进摘要
            if (node["left"]["field"], node.get("op"), value) in counted:
                continue  # 同一条件在计数器 filter 里已经写过一遍,不重复叙述
            filters.append(render_comparison(seeds, event, node, short=True))
        else:
            extras.append(render_comparison(seeds, event, node, short=True))
    parts = []
    if main:
        parts.append(";".join(main))
    elif filters:
        parts.append("单条事件即命中 " + ";".join(filters))
        filters = []
    if filters:
        parts.append("前置 " + ";".join(filters))
    if extras:
        parts.append("另需 " + ";".join(extras))
    return ",".join(parts)


def summarize(seeds, doc):
    """从 condition(+ delay)归纳出「检测什么」的一句话。"""
    subject = SUBJECT_CN.get(stat_dimension(doc), "同一主体")
    text = summarize_conditions(seeds, doc, doc["condition"])
    delay = doc.get("delay")
    if delay:
        later = summarize_conditions(seeds, doc, delay["condition"])
        tail = f"延迟 {fmt_duration(delay['duration_seconds'])}后再判定:{later}"
        text = f"{text},{tail}" if text else tail
    return f"{subject}:{text}" if text else subject


def disposition(doc):
    action = doc["action"]
    subject = CHECKTYPE_CN.get(action["check_type"], action["check_type"])
    return (f"`{action['decision']}` · {subject}名单({action['check_value']})"
            f" · {fmt_duration(action['ttl'])}")


def scene_of(doc):
    tags = set(doc.get("tags") or [])
    for name, category, scene_tags in SCENES:
        if doc["category"] == category and tags & scene_tags:
            return name
    return f"{doc['category']} · 未归类"


def dimension_of(name):
    for prefix in DIM_PREFIXES:
        if name.startswith(prefix):
            return prefix
    return None


DIM_TOKEN_RE = re.compile("|".join(DIM_PREFIXES))


def mirror_families(strategies):
    """三维度镜像族:去掉维度前缀后,把名字中剩余的维度名替换为占位符再聚类。

    例:IP多用户请求下单 / 用户多设备请求下单 / 设备多IP请求下单 → 同一族「多×请求下单」。
    """
    families = defaultdict(list)
    for doc in strategies:
        dim = dimension_of(doc["name"])
        if not dim:
            continue
        rest = doc["name"][len(dim):]
        families[DIM_TOKEN_RE.sub("×", rest)].append((dim, doc))
    return families


# ---------------------------------------------------------------- 数据问题自检
#
# 下面几条都是从数据里**自动**检出的既有问题,不是人工维护的清单。
# 1.x 的这些手滑在转换到 2.0 时被原样保留(转换器只改结构不改语义)。

# 「骨架策略」里把页面路径写成 A / B 这类单个大写字母的字面占位值
PLACEHOLDER_LITERAL_RE = re.compile(r"[A-Z]")


def skeleton_placeholders(seeds, doc):
    """index.json 未标记、但把 page 写成 `A`/`B` 字面占位值的骨架策略。"""
    if seeds.meta[doc["name"]].get("requires_configuration"):
        return []
    values = []
    for node in all_leaves(doc):
        if node_kind(node) != "comparison":
            continue
        left = node["left"]
        if left.get("kind") == "event_field" and left.get("field") == "page":
            value = (node.get("right") or {}).get("value", "")
            if PLACEHOLDER_LITERAL_RE.fullmatch(str(value)):
                values.append(value)
        if left.get("kind") == "counter":
            for f in filter_leaves(left["counter"].get("filter")):
                if f.get("object") == "page" and \
                        PLACEHOLDER_LITERAL_RE.fullmatch(str(f.get("value", ""))):
                    values.append(f["value"])
    return sorted(set(values))


REMARK_GT_RE = re.compile(r"\s*>\s*([\d,]+)")


def remark_vs_condition_mismatch(doc):
    """备注写「> N」但实际条件是「== N」—— 1.x 里的手滑,会让策略几乎永远不命中。"""
    m = REMARK_GT_RE.match(doc.get("remark") or "")
    if not m:
        return None
    number = m.group(1).replace(",", "")
    for node in threshold_nodes(doc):
        if node.get("op") == "==" and node["right"]["value"] == number:
            return (doc, doc["remark"].strip(), f"=={number}")
    return None


def name_vs_list_mismatch(doc):
    """策略名声明的维度与它实际写入的名单主体是否一致。"""
    dim = dimension_of(doc["name"])
    if not dim:
        return None
    expected = {"IP": "IP", "用户": "USER", "设备": "DeviceID"}[dim]
    actual = doc["action"]["check_type"]
    if actual == expected:
        return None
    return (doc, expected, actual)


REGEX_LIKE_RE = re.compile(r"[\\^$*+]")


def regex_value_with_substring_op(doc):
    """把正则写进了 `contains` 这类子串算子的右值 —— 条件永不成立。

    例:计数器过滤 `page contains "^\\s*$"`,作者本意显然是 `!regex`(page 非空)。
    """
    hits = []
    for node in comparisons_with(doc, "counter"):
        counter = node["left"]["counter"]
        for f in filter_leaves(counter.get("filter")):
            op, value = f.get("operation"), f.get("value")
            if op in ("contains", "!contains") and REGEX_LIKE_RE.search(str(value)):
                hits.append((f.get("object"), op, value))
    for node in all_leaves(doc):
        if node_kind(node) != "comparison":
            continue
        left, right = node["left"], node.get("right") or {}
        if left.get("kind") == "event_field" and node.get("op") in ("contains", "!contains") \
                and REGEX_LIKE_RE.search(str(right.get("value", ""))):
            hits.append((left["field"], node["op"], right.get("value")))
    return sorted(set(hits))


# ---------------------------------------------------------------- 文档各章节


def sec_header(seeds):
    n = len(seeds.strategies)
    return [
        "# 策略模板参考",
        "",
        "<!-- 本文件由 tools/gen_strategy_reference.py 自动生成,请勿手工编辑。 -->",
        "",
        "> ⚠️ **本文由 `tools/gen_strategy_reference.py` 从 `seeds/strategies/` 自动生成,请勿手工编辑。**",
        "> 修改策略模板本身请改 `seeds/`,随后重新运行生成器;CI 会用 `--check` 校验本文与 seeds 一致。",
        "",
        f"本文覆盖从 Nebula 1.x 继承的全部 **{n} 条内置策略模板**"
        f"(`seeds/strategies/`,源表 `{seeds.index['source_table']}`)。"
        "它们已按 2.0 的 [`strategy.schema.json`](../../packages/domain-schema/strategy.schema.json) "
        "结构重写:条件是一棵可嵌套的布尔树(`condition`),处置动作独立成 `action`。",
        "它们是**模板**而不是开箱即用的生产策略:阈值来自 1.x 当年的业务流量,处置动作全部是「待人工审核」,"
        "启用前请先读[重要提示](#重要提示启用前必读)。",
        "",
        "## 目录",
        "",
        "- [概览](#概览)",
        "- [如何读懂一条策略](#如何读懂一条策略)",
        "- [按场景分组的策略全表](#按场景分组的策略全表)",
        "- [三维度镜像设计](#三维度镜像设计)",
        "- [延迟求值(delay)策略](#延迟求值delay策略)",
        "- [需要配置才能生效的策略](#需要配置才能生效的策略)",
        "- [重要提示(启用前必读)](#重要提示启用前必读)",
        "",
        "---",
        "",
    ]


def sec_overview(seeds):
    S = seeds.strategies
    total = len(S)
    lines = ["## 概览", ""]

    cat_note = {
        "ORDER": "订单与交易场景:下单、取消、支付",
        "ACCOUNT": "账号场景:注册、登录、密码、邀请码",
        "VISITOR": "访客场景:纯 HTTP 流量特征,不需要解析业务语义",
    }
    cats = Counter(d["category"] for d in S)
    lines += ["### 按场景大类(category)", "",
              "| category | 策略数 | 占比 | 说明 |", "|---|---:|---:|---|"]
    for c, n in sorted(cats.items(), key=lambda kv: (-kv[1], kv[0])):
        lines.append(f"| `{c}` | {n} | {n / total:.0%} | {cell(cat_note.get(c, ''))} |")
    lines += [f"| **合计** | **{total}** | | |", ""]

    tags = Counter(t for d in S for t in (d.get("tags") or []))
    untagged = sum(1 for d in S if not d.get("tags"))
    lines += ["### 按风险标签(tag)", "",
              "标签是 1.x 的风险分类,一条策略可带多个标签(实际数据中每条至多一个)。", "",
              "| 标签 | 策略数 | | 标签 | 策略数 |", "|---|---:|---|---|---:|"]
    items = sorted(tags.items(), key=lambda kv: (-kv[1], kv[0]))
    half = (len(items) + 1) // 2
    left_col, right_col = items[:half], items[half:]
    for i in range(half):
        lt, ln = left_col[i]
        if i < len(right_col):
            rt, rn = right_col[i]
            lines.append(f"| {cell(lt)} | {ln} | | {cell(rt)} | {rn} |")
        else:
            lines.append(f"| {cell(lt)} | {ln} | | | |")
    lines.append("")
    if untagged:
        lines += [f"另有 **{untagged}** 条策略没有任何标签。", ""]

    subj = Counter()
    ttl_by_subj = defaultdict(Counter)
    for d in S:
        action = d["action"]
        subj[action["check_type"]] += 1
        ttl_by_subj[action["check_type"]][action["ttl"]] += 1
    lines += ["### 按名单主体类型(action.check_type)", "",
              "命中后写入哪一类风险名单,决定了业务侧该拦谁。", "",
              "| 名单主体 | 含义 | 策略数 | 占比 | 名单有效期分布 |", "|---|---|---:|---:|---|"]
    for k, n in sorted(subj.items(), key=lambda kv: (-kv[1], kv[0])):
        ttls = "、".join(f"{fmt_duration(t)}×{c}" for t, c in sorted(ttl_by_subj[k].items()))
        lines.append(f"| `{k}` | {CHECKTYPE_CN.get(k, k)} | {n} | {n / total:.0%} | {cell(ttls)} |")
    lines += [f"| **合计** | | **{total}** | | |", ""]

    triggers = Counter(trigger_event(d) for d in S)
    lines += ["### 按触发事件(trigger.event)", "",
              "策略在该事件到达时求值。它可以与计数器统计的源事件不同 —— "
              "「下单不支付」正是靠这一点实现的。", "",
              "| 触发事件 | 事件名 | 策略数 |", "|---|---|---:|"]
    for ev, n in sorted(triggers.items(), key=lambda kv: (-kv[1], kv[0])):
        lines.append(f"| `{ev}` | {cell(seeds.event_cn(ev))} | {n} |")
    lines.append("")

    windows = Counter()
    for d in S:
        for node in comparisons_with(d, "counter"):
            windows[node["left"]["counter"]["window"]] += 1
    lines += ["### 统计窗口分布", "",
              "内联计数器(`left.kind = counter`)所用的窗口长度,反映 1.x 的口径偏好。", "",
              "| 窗口 | 计数器数量 |", "|---|---:|"]
    for w, n in sorted(windows.items()):
        lines.append(f"| {fmt_duration(w)} | {n} |")
    lines += ["", "---", ""]
    return lines


def sec_reading_guide(seeds):
    doc = next((d for d in seeds.strategies if d["name"] == EXAMPLE_STRATEGY), None)
    if doc is None:  # 样例被改名时退化为第一条策略,保证生成器不中断
        doc = seeds.strategies[0]
    meta = seeds.meta[doc["name"]]
    src = doc.get("source_1x") or {}

    head_keys = ("app", "name", "visible_name", "category", "tags", "remark",
                 "score", "status", "trigger")
    lines = ["## 如何读懂一条策略", "",
             f"后面的表格是归纳结果。要看懂原始模板,先读这一节 —— 以 **{doc['name']}** "
             f"(`{meta['file']}`)为例,把它的 JSON 逐条翻译成人话。", "",
             "### 1. 头部字段", "",
             "```json",
             json.dumps({k: doc[k] for k in head_keys if k in doc},
                        ensure_ascii=False, indent=2),
             "```", "",
             "| 字段 | 本例取值 | 含义 |", "|---|---|---|"]
    header_rows = [
        ("app", doc["app"], "应用命名空间,内置资产统一为 `nebula`,不是客户名"),
        ("name", doc["name"], "策略名,全局唯一,也是告警与名单的来源标识"),
        ("visible_name", doc["visible_name"], "展示名,可以改成运营看得懂的说法"),
        ("category", doc["category"], "风险场景大类"),
        ("tags", "、".join(doc.get("tags") or []) or "—", "风险标签,用于告警聚合与报表下钻"),
        ("remark", doc["remark"], "1.x 作者留下的速记备注,通常是「阈值 + 窗口」"),
        ("score", doc["score"], "风险分权重,参与风险分计算(1.x 未落地,见文末提示)"),
        ("status", doc["status"], "`online` 生效 / `test` 只观察 / `inedit` 编辑中 / `outline` 停用"),
        ("trigger.event", trigger_event(doc), "触发事件 —— 该事件到达时才对本策略求值"),
        ("effective_from / effective_to",
         f"{fmt_date(doc['effective_from'])} / {fmt_date(doc['effective_to'])}",
         "生效时间窗(毫秒时间戳),`null` 表示立即生效 / 长期有效"),
        ("version", doc["version"], "策略结构版本号,2.0 结构固定为 `2.0`"),
        ("explain", json.dumps(doc.get("explain", True)),
         "命中时是否记录变量值快照(1.x 恒为空导致告警不可解释,2.0 默认开启)"),
        ("source_1x.group_id", src.get("group_id", "—"), "1.x 的策略分组编号,仅供溯源,引擎不读取"),
        ("source_1x.is_locked", json.dumps(src.get("is_locked")), "1.x 里是否被编辑锁定"),
    ]
    for k, v, note in header_rows:
        lines.append(f"| `{k}` | {cell(v)} | {cell(note)} |")

    kinds = Counter()
    for d in seeds.strategies:
        for node in all_nodes(d):
            kind = node_kind(node)
            if kind == "logical":
                kinds[(kind, node["op"])] += 1
            elif kind == "expression":
                kinds[(kind, "")] += 1
            else:
                kinds[(kind, node["left"].get("kind", ""))] += 1

    lines += ["", "### 2. condition:一棵条件树", "",
              "`condition` 是策略的主体。相比 1.x 的扁平 `terms` 数组,2.0 的条件是一棵"
              "**可嵌套的布尔树**,节点有三种(权威定义见 "
              "[`strategy.schema.json`](../../packages/domain-schema/strategy.schema.json)):",
              "",
              "- `logical` —— `{\"op\": \"and|or|not\", \"conditions\": [...]}`,可以任意嵌套;",
              "- `comparison` —— `{\"left\": …, \"op\": …, \"right\": …}`,`left` 的 `kind` 决定"
              "取值来源:`event_field`(事件字段)、`counter`(内联计数器)、`variable`(已定义变量)、"
              "`constant`(常量);",
              "- `expression` —— `{\"cel\": \"…\"}`,一段返回 bool 的 CEL 表达式。",
              "",
              "从 1.x 迁移过来的策略全部表现为**单层 `and`**(1.x 的条款之间只有 AND 关系),"
              "语义与原策略完全等价;自己写新策略时可以放心用嵌套的 `or` / `not`"
              "(见[从 1.x 迁移](../migration/from-1x.md))。",
              "",
              f"全部 {len(seeds.strategies)} 条策略中出现过的节点(含 `delay` 里的条件):", "",
              "| 节点类型 | 形态 | 出现次数 | 含义 |", "|---|---|---:|---|"]
    for (kind, sub), n in sorted(kinds.items(), key=lambda kv: (-kv[1], kv[0])):
        if kind == "logical":
            shape = f"`op = {sub}`"
        elif kind == "comparison":
            shape = f"`left.kind = {sub}`"
        else:
            shape = "—"
        lines.append(f"| `{kind}` | {shape} | {n} | {cell(NODE_DESC.get((kind, sub), ''))} |")

    const_n = sum(1 for d in seeds.strategies for node in all_leaves(d)
                  if node_kind(node) == "comparison"
                  and isinstance(node.get("right"), dict)
                  and node["right"].get("kind") == "constant")
    lines += ["", f"(`right` 侧共出现 {const_n} 次 `constant` 常量,不再单列。"
              "1.x 的常量一律是字符串,转换时保持原样 —— 引擎按左值类型做转换。)", "",
              "### 3. 逐条翻译本例的 condition", "",
              f"本例的顶层是 `{doc['condition'].get('op', '(单条件)')}`,"
              f"下挂 {len(flatten_and(doc['condition']))} 个子条件:", ""]

    event = trigger_event(doc)
    for i, node in enumerate(flatten_and(doc["condition"]), 1):
        kind = node_kind(node)
        if kind == "comparison":
            label = f"comparison,`left.kind = {node['left'].get('kind')}`"
        elif kind == "expression":
            label = "expression,CEL 表达式"
        else:
            label = f"logical,`op = {node['op']}`"
        lines += [f"**子条件 {i} —— {label}**", "", "```json",
                  json.dumps(node, ensure_ascii=False, indent=2), "```", "",
                  f"→ {render_cond(seeds, event, node)}", ""]

    lines += ["### 4. action:命中后的处置动作", "",
              "1.x 把处置写成 `terms` 里的一条 `setblacklist` 条款,和判定条件混在一个数组里;"
              "2.0 把它提到策略级的 `action`,条件与动作彻底分开。", "",
              "```json",
              json.dumps({"action": doc["action"]}, ensure_ascii=False, indent=2),
              "```", "",
              f"→ 把本次事件的 `{doc['action']['check_value']}` 作为"
              f"{CHECKTYPE_CN.get(doc['action']['check_type'], doc['action']['check_type'])}"
              f"写入风险名单,决策 `{doc['action']['decision']}`,"
              f"有效期 {fmt_duration(doc['action']['ttl'])}。", "",
              "`action` 还有两个 2.0 新增字段,内置模板里都没有用到:`checkpoints`(限定生效检查点,"
              "为空表示全局生效)、`handlers`(命中后的额外动作 —— 阻断、二次验证、限流、降级、通知、"
              "webhook)。要让策略真的「做点什么」,就是在 `handlers` 里加。", "",
              "合起来,这条策略的意思是:", "",
              f"> {summarize(seeds, doc)}", "",
              "> 命中后:" + disposition(doc).replace("`", "") + "。", "",
              "几个容易踩的点:", "",
              "- `page` 是**伪静态化之后的页面标识**,不是原始 URI;`page 不匹配正则 ^\\s*$` "
              "这类条件在多数策略里都有,含义是「只统计能解析出页面的请求」,属于噪声过滤;",
              "- 计数器的 `groupby` 才是**统计口径**(按谁分组),它未必等于 `action.check_value`"
              "(拉黑谁)—— 少数 1.x 策略这两处对不上,见[名称与名单主体不一致](#-名称与名单主体不一致的策略);",
              "- `trigger.event` 指定**哪个事件触发本次判定**,它可以与计数器的 `event`(被统计的事件)"
              "不同 —— 「下单不支付」正是靠这一点实现的:下单事件触发,却去数支付页面的访问量;",
              "- 计数器的 `algorithm` 为 `distinct_count` 时统计的是 `operand` 的**去重个数**,"
              "为 `count` 时统计**事件条数**,为 `sum` 时对 `operand` 求和;",
              "- 1.x 计数器里那条 `c_ip = c_ip` 的样板条件(把分组键绑定到自身)在转换时已被丢弃 —— "
              "它表达的是「按 c_ip 分组」,已经由 `groupby` 表达。",
              "", "---", ""]
    return lines


def sec_full_table(seeds):
    lines = ["## 按场景分组的策略全表", "",
             "「检测什么」由 `remark` 与 `condition` 归纳而来;「命中后处置」的三段依次是 "
             "**决策 · 名单主体(取值字段) · 有效期**。", ""]

    order = [name for name, _, _ in SCENES]
    grouped = defaultdict(list)
    for doc in seeds.strategies:
        grouped[scene_of(doc)].append(doc)
    for extra in sorted(set(grouped) - set(order)):
        order.append(extra)

    for scene in order:
        docs = grouped.get(scene)
        if not docs:
            continue
        lines += [f"### {scene}({len(docs)} 条)", ""]
        note = SCENE_NOTE.get(scene)
        if note:
            lines += [note, ""]
        lines += ["| 策略名 | 检测什么 | 命中后处置 | 标签 |", "|---|---|---|---|"]
        for doc in sorted(docs, key=lambda d: d["name"]):
            meta = seeds.meta[doc["name"]]
            needs_config = meta.get("requires_configuration") or skeleton_placeholders(seeds, doc)
            flag = " 🔧" if needs_config else ""
            flag += " ⏱" if doc.get("delay") else ""
            summary = summarize(seeds, doc)
            summary = f"{summary}(原始备注:{code(doc['remark'].strip() or '—')})"
            tags = "、".join(doc.get("tags") or []) or "—"
            lines.append(f"| **{cell(doc['name'])}**{flag} | {cell(summary)} | "
                         f"{cell(disposition(doc))} | {cell(tags)} |")
        lines.append("")
    lines += ["🔧 = 含占位符,需要先配置才能生效,见[下文](#需要配置才能生效的策略);"
              "⏱ = 用 `delay` 做延迟求值,见[延迟求值(delay)策略](#延迟求值delay策略)。", "", "---", ""]
    return lines


def sec_mirrors(seeds):
    families = mirror_families(seeds.strategies)
    by_dim_count = defaultdict(dict)
    for key, members in families.items():
        by_dim_count[len({dim for dim, _ in members})][key] = members
    full3 = by_dim_count.get(3, {})
    partial = by_dim_count.get(2, {})
    singles = by_dim_count.get(1, {})
    covered = sum(len(v) for v in families.values())
    no_dim = [d for d in seeds.strategies if not dimension_of(d["name"])]

    lines = ["## 三维度镜像设计", "",
             "1.x 的内置策略遵循一条明确的设计规律:**同一个风险模式,按 IP / 设备 / 账号三个维度各写一条**。",
             "同一族的三条策略结构几乎相同,区别只在计数器的分组键(`groupby`)、变量引用的 `keys` "
             "和写入的名单主体(`action.check_type` / `action.check_value`)。",
             "",
             "这样设计的原因是三个维度各有盲区:IP 会被代理池稀释,设备指纹会被改机工具伪造,"
             "账号在注册环节还不存在。三条一起跑才能互相补位。",
             "",
             "下面的分族由生成器自动识别:去掉策略名的维度前缀,再把名字中剩余的维度词"
             "(IP/用户/设备)替换成 `×`,相同者归为一族。", "",
             "### 分族统计", "",
             "| | 族数 | 策略数 |", "|---|---:|---:|",
             f"| 三个维度齐全 | {len(full3)} | {sum(len(v) for v in full3.values())} |",
             f"| 只覆盖两个维度 | {len(partial)} | {sum(len(v) for v in partial.values())} |",
             f"| 只有单个维度(无镜像) | {len(singles)} | {sum(len(v) for v in singles.values())} |",
             f"| 策略名不带维度前缀(`visit_*`、UA 类、测试策略) | — | {len(no_dim)} |",
             f"| **合计** | | **{covered + len(no_dim)}** |", ""]

    def family_table(title, fams, intro):
        out = [f"### {title}", ""]
        if intro:
            out += [intro, ""]
        out += ["| 风险模式 | IP 维度 | 账号维度 | 设备维度 | 阈值/窗口 | 阈值一致 |",
                "|---|---|---|---|---|---|"]
        for key in sorted(fams):
            members = sorted(fams[key], key=lambda x: (DIM_PREFIXES.index(x[0]), x[1]["name"]))
            by_dim = defaultdict(list)
            for dim, doc in members:
                by_dim[dim].append(doc)
            sigs = []
            for dim, doc in members:
                sigs.append((dim, doc["name"], threshold_signature(doc)))
            consistent = len({s for _, _, s in sigs}) == 1
            cols = []
            for dim in DIM_PREFIXES:
                docs = by_dim.get(dim)
                cols.append("<br>".join(cell(d["name"]) for d in docs) if docs else "—")
            if consistent:
                sig_txt = sigs[0][2]
            else:
                sig_txt = "<br>".join(f"{name}:{sig}" for _, name, sig in sigs)
            out.append(f"| {cell(key)} | {cols[0]} | {cols[1]} | {cols[2]} | "
                       f"{cell(sig_txt)} | {'是' if consistent else '**否**'} |")
        out.append("")
        return out

    lines += family_table("三个维度齐全的策略族", full3,
                          "这些是最典型的镜像族。上线时建议**整族一起启停**,否则会留下盲区。")
    lines += family_table("只覆盖两个维度的策略族", partial,
                          "1.x 没有把这些模式补齐。缺失的那一维通常是可以照着补的 —— "
                          "复制一条,改计数器的 `groupby`、变量引用的 `keys`、filter 里的键,"
                          "以及 `action.check_type` / `action.check_value` 即可。")

    lines += ["### 只有单个维度、没有镜像的策略", "",
              "多数是访客场景(VISITOR)的流量特征策略 —— 这一场景在 1.x 里**只有 IP 一个维度**,"
              "因为纯 HTTP 流量里往往拿不到账号,设备号也未必可信。"
              "账号/订单场景里的几条(如 `用户换密码登录`、`用户深夜多次请求下单`)则是真正的缺口:"
              "换个维度同样成立,1.x 只是没写。", "",
              "| 策略名 | 维度 | 场景 |", "|---|---|---|"]
    for doc, dim in sorted(((doc, dim) for members in singles.values() for dim, doc in members),
                           key=lambda x: x[0]["name"]):
        lines.append(f"| {cell(doc['name'])} | {dim} | {cell(scene_of(doc))} |")
    lines += ["", f"另有 {len(no_dim)} 条策略名不带维度前缀:"
              + "、".join(f"`{d['name']}`" for d in sorted(no_dim, key=lambda d: d["name"])) + "。",
              "它们全部按 IP 维度处置。", ""]

    mismatches = [m for m in (name_vs_list_mismatch(d) for d in seeds.strategies) if m]
    if mismatches:
        lines += ["### ⚠️ 名称与名单主体不一致的策略", "",
                  "生成器发现下列策略的**名字声明的维度**与**实际写入的名单主体**"
                  "(`action.check_type`)对不上 —— 这是 1.x 数据里的既有问题(复制粘贴时漏改),"
                  "转换到 2.0 时按原样保留,不是生成器的误判。启用前请逐条确认到底想拉黑谁。", "",
                  "| 策略名 | 名称暗示的主体 | 实际写入的名单 | 实际统计口径(groupby) |",
                  "|---|---|---|---|"]
        for doc, expected, actual in sorted(mismatches, key=lambda m: m[0]["name"]):
            lines.append(f"| {cell(doc['name'])} | `{expected}` | `{actual}` | "
                         f"`{cell(stat_dimension(doc))}` |")
        lines.append("")
    lines += ["---", ""]
    return lines


def sec_delay(seeds):
    """delay 类策略 —— 「做了 A 之后一段时间内没有做 B」。"""
    docs = [d for d in seeds.strategies if d.get("delay")]
    lines = ["## 延迟求值(delay)策略", ""]
    if not docs:
        lines += ["当前模板中没有使用 `delay` 的策略。", "", "---", ""]
        return lines

    durations = sorted({d["delay"]["duration_seconds"] for d in docs})
    dur_txt = "、".join(fmt_duration(x) for x in durations)
    lines += [
        f"内置模板里有 **{len(docs)} 条**策略带 `delay` 字段。它们检测的是一类特殊风险:"
        "**做了 A 之后,一段时间内始终没有做 B**。",
        "",
        "普通条件在事件到达的**那一瞬间**求值,而「没做 B」在那一瞬间总是成立 —— "
        "B 本来就还没来得及发生。所以这类模式没法用普通条件表达,必须**等一会儿再看**。"
        "`delay` 就是干这个的:",
        "",
        "```",
        "事件到达  ──►  condition 成立?  ──否──►  丢弃",
        "                    │是",
        "                    ▼",
        f"              挂起,等待 duration_seconds({dur_txt})",
        "                    │",
        "                    ▼",
        "            delay.condition 成立?  ──否──►  丢弃(说明用户后来做了 B,行为正常)",
        "                    │是",
        "                    ▼",
        "               产出告警 + 执行 action",
        "```",
        "",
        "**两段条件都成立才命中**。`delay.condition` 与主 `condition` 结构完全一样"
        "(同一棵条件树的语法),里面照样可以写计数器、变量引用和嵌套布尔。",
        "",
    ]

    lines += ["### 模板中的 delay 策略", "",
              "| 策略名 | 前置条件(condition) | 延迟 | 到期后判定(delay.condition) | 命中后处置 |",
              "|---|---|---|---|---|"]
    for doc in sorted(docs, key=lambda d: d["name"]):
        event = trigger_event(doc)
        pre = render_cond(seeds, event, doc["condition"], short=True)
        post = render_cond(seeds, event, doc["delay"]["condition"], short=True)
        lines.append(f"| **{cell(doc['name'])}** | {cell(pre)} | "
                     f"{fmt_duration(doc['delay']['duration_seconds'])} | {cell(post)} | "
                     f"{cell(disposition(doc))} |")
    lines.append("")

    sample = sorted(docs, key=lambda d: d["name"])[0]
    delay = sample["delay"]
    counters = comparisons_with(sample, "counter")
    window_txt = (fmt_duration(counters[0]["left"]["counter"]["window"])
                  if counters else "—")
    lines += [
        f"### 以 `{sample['name']}` 为例", "",
        "```json",
        json.dumps({"condition": sample["condition"], "delay": delay},
                   ensure_ascii=False, indent=2),
        "```", "",
        "逐步展开:", "",
        f"1. 触发事件 `{trigger_event(sample)}` 到达,先判 `condition` —— "
        f"{render_cond(seeds, trigger_event(sample), sample['condition'])};",
        f"2. **不立即出告警**,而是为这个主体挂起一个 "
        f"{fmt_duration(delay['duration_seconds'])}的定时器;",
        f"3. 到期后再求 `delay.condition` —— "
        f"{render_cond(seeds, trigger_event(sample), delay['condition'])};",
        "4. 两段都成立才产出告警并执行 `action`。中途只要主体做了 B,计数器就不为 0,"
        "到期判定不成立,什么也不会发生。", "",
        "注意计数器的窗口(`window`)与延迟时长(`duration_seconds`)是**两件事**:"
        f"本例的窗口是 {window_txt}、延迟是 {fmt_duration(delay['duration_seconds'])},"
        "到期时回看的是「最近一个窗口内」的行为。两者相等时语义最直观(等多久就看多久),"
        "但并不强制。", "",
        "### 用它写自己的策略", "",
        "这是模板里唯一一组**否定式(negative)**策略,可以直接当样板改。常见场景:", "",
        "- 加购/下单后 N 分钟未支付(占库存、锁优惠);",
        "- 领券后 N 天未核销(薅券转卖);",
        "- 注册后 N 分钟未完成实名(养号);",
        "- 触发风控挑战后 N 分钟未通过验证。", "",
        "工程上要留意:", "",
        "- **有状态**:每条挂起的判定都要在引擎里保留到到期,待定量约等于"
        "「触发事件量 × 前置条件命中率」,延迟越长占用越多,别把 `duration_seconds` 设成几天;",
        "- **延迟出告警**:告警会比风险发生晚一个 `duration_seconds`,不适合需要实时拦截的场景;",
        "- **到期时重新计算**:`delay.condition` 里的计数器是在**到期那一刻**按当时的窗口求值的,"
        "不是事件到达时的快照;",
        "- 1.x 用 `terms` 里的一条 `sleep` 条款表达同一件事(`sleep` 之后的条款就是到期后再判的部分),"
        f"转换时提升为策略级的 `delay` 字段,溯源信息记在 `source_1x.notes` 里。", "",
    ]

    skeleton = [d for d in docs if skeleton_placeholders(seeds, d)]
    if skeleton:
        lines += [f"> ⚠️ 这 {len(skeleton)} 条模板里的 A / B 是**字面占位值**,"
                  "不替换成真实页面路径就永远不会命中,详见"
                  "[需要配置才能生效的策略](#需要配置才能生效的策略)。", ""]
    lines += ["---", ""]
    return lines


def placeholder_spots(seeds, doc, ph):
    """占位符在这条策略里出现的位置(条件树 + delay)。"""
    spots = []
    for node in all_leaves(doc):
        if node_kind(node) != "comparison":
            continue
        left, right = node["left"], node.get("right") or {}
        if left.get("kind") == "counter":
            counter = left["counter"]
            for f in filter_leaves(counter.get("filter")):
                if f.get("value") == ph:
                    spots.append(f"`{counter['event']}` 计数器过滤 "
                                 f"`{f.get('object')} {f.get('operation')} {ph}`")
        if right.get("value") == ph and left.get("kind") == "event_field":
            spots.append(f"事件条件 `{left.get('field')} {node.get('op')} {ph}`")
    return list(dict.fromkeys(spots))


def sec_requires_config(seeds, out_dir):
    entries = [e for e in seeds.index["strategies"] if e.get("requires_configuration")]
    # index.json 里的 placeholder_reference 是相对 seeds/strategies/ 的,这里换算成相对本文档的路径
    ph_doc = (seeds.dir / "strategies" / seeds.index.get("placeholder_reference",
                                                        "../PLACEHOLDERS.md")).resolve()
    ph_link = os.path.relpath(ph_doc, out_dir.resolve())
    skeleton_count = sum(1 for d in seeds.strategies if skeleton_placeholders(seeds, d))
    lines = ["## 需要配置才能生效的策略", "",
             f"`index.json` 标了 **{len(entries)} 条**含占位符的策略;生成器另外扫出 "
             f"**{skeleton_count} 条**写死了字面占位值的骨架策略。"
             f"这 **{len(entries) + skeleton_count} 条**在配置之前**导入后不会正确工作**"
             "(全表中以 🔧 标出)。占位符的完整说明见 "
             f"[`seeds/PLACEHOLDERS.md`]({ph_link}) 对应条目。", ""]

    placeholders = sorted({p for e in entries for p in e.get("placeholders", [])})
    for ph in placeholders:
        lines += [f"### `{ph}`", "",
                  PLACEHOLDER_MEANING.get(ph, "见 `seeds/PLACEHOLDERS.md`。"), ""]
        users = [e for e in entries if ph in e.get("placeholders", [])]
        lines += ["| 策略名 | 出现位置 | 不配置的后果 |", "|---|---|---|"]
        for e in sorted(users, key=lambda x: x["name"]):
            doc = next(d for d in seeds.strategies if d["name"] == e["name"])
            spots = placeholder_spots(seeds, doc, ph)
            consequence = ("计数器恒为 0 —— 策略会把**所有**下单主体都判成「下单不支付」"
                           if "不支付" in e["name"] else
                           "计数器恒为 0 —— 策略会把**所有**登录/注册主体都判成「未访问必要资源」")
            lines.append(f"| **{cell(e['name'])}** | {cell('；'.join(spots) or '—')} | "
                         f"{cell(consequence)} |")
        lines.append("")

    skeletons = [(doc, vals) for doc, vals in
                 ((d, skeleton_placeholders(seeds, d)) for d in seeds.strategies) if vals]

    if skeletons:
        lines += ["### 另外 %d 条「骨架策略」(index.json 未标记)" % len(skeletons), "",
                  "这几条策略把页面路径写成了 `A`、`B` 这样的字面占位值 —— 它们是 1.x 留下的**模式骨架**,"
                  "不是可用策略。`index.json` 没有把它们标成 `requires_configuration`(占位符扫描只认 "
                  "`<YOUR_*>` 形式),但不改同样不会有任何意义:`page == \"A\"` 在真实流量里永不成立。", "",
                  "| 策略名 | 占位值 | 出现位置 | 要填什么 |", "|---|---|---|---|"]
        for doc, values in sorted(skeletons, key=lambda x: x[0]["name"]):
            spots = []
            for v in values:
                spots += placeholder_spots(seeds, doc, v)
            lines.append(f"| **{cell(doc['name'])}** | {cell('、'.join(code(v) for v in values))} | "
                         f"{cell('；'.join(dict.fromkeys(spots)) or '—')} | "
                         f"A = 先访问的页面,B = 本应随后访问的页面;两处 `page` 条件都要换成真实路径 |")
        lines += ["", "语义是「访问了 A,但 5 分钟内没有访问 B」—— 用 `delay` 延迟求值实现,"
                  "是模板里唯一一组否定式(negative)策略,"
                  "工作方式见[延迟求值(delay)策略](#延迟求值delay策略)。", ""]

    lines += ["**怎么改**:直接编辑 `seeds/strategies/` 下对应文件,把占位符字符串替换掉;"
              "或在导入控制台后于策略编辑页修改该条件。替换后重新运行 "
              "`python3 tools/validate_seeds.py` 确认仍然合法。", "",
              "> 这两组策略之所以都用同一个占位符,是因为它们都在问「用户有没有访问过某个关键页面」。"
              "「下单不支付」问的是支付页,「未访问必要资源」问的是登录/注册前应当加载的页面 —— "
              "后者在你的站点上很可能是**另一个路径**,不要无脑填成一样的。", "", "---", ""]
    return lines


def sec_caveats(seeds):
    S = seeds.strategies
    total = len(S)
    decisions = Counter(d["action"]["decision"] for d in S)
    scores = Counter(d["score"] for d in S)
    statuses = Counter(d["status"] for d in S)
    nonzero = sorted((d["name"], d["score"]) for d in S if d["score"] != 0)
    ends = [d["effective_to"] for d in S if d.get("effective_to")]
    starts = [d["effective_from"] for d in S if d.get("effective_from")]

    dec_txt = "、".join(f"`{k}` × {n}" for k, n in sorted(decisions.items()))
    score_txt = "、".join(f"{k} × {n}" for k, n in sorted(scores.items()))
    handlers = sum(1 for d in S if d["action"].get("handlers"))

    lines = ["## 重要提示(启用前必读)", "",
             "以下不是「注意事项」式的套话,而是这批模板**当前的真实状态**。全部由生成器从数据中统计得出。", "",
             "### 1. 没有一条策略会自动阻断", "",
             f"{total} 条策略的处置决策(`action.decision`)分布:{dec_txt}。",
             "",
             f"也就是说 **{decisions.get('review', 0)}/{total} 条全部是 `review`(转人工审核)**,"
             "没有任何一条会自动拦截、二次验证或限流。1.x 当年设计了处置能力但没有落地,"
             "系统实际只能产出「待审核」告警。",
             "",
             f"2.0 在 `action` 下新增了 `handlers`(`block` / `captcha` / `throttle` / `degrade` / "
             f"`notify` / `webhook`),但内置模板里 **{handlers}/{total} 条**用到它 —— "
             "**启用前你需要自己决定处置动作**:哪些策略可以直接阻断,哪些只发告警,哪些走二次验证。"
             "建议路径是先全部保持 `review` 观察一段时间,拿到误报率之后再逐条提升处置强度。", "",
             "### 2. 风险分(score)没有落地", "",
             f"score 取值分布:{score_txt}。"]
    if nonzero:
        detail = "、".join(f"`{n}`(score={s})" for n, s in nonzero)
        lines += ["",
                  f"**{scores.get(0, 0)}/{total} 条的 score 为 0**,只有 {detail} 是例外 —— "
                  "这个孤例没有任何配套逻辑,基本可以判定为 1.x 里的遗留噪声,而非有意设计。"]
    lines += ["",
              "score 为 0 意味着**风险评分能力等于没有开启**:命中再多条策略,主体的风险分仍然是 0,"
              "无法按分数分级处置。2.0 的 schema 要求显式赋值,但迁移时保留了 1.x 的原值 —— "
              "要用起来,需要按自身业务给每条策略赋权,通常按「误报代价 × 风险严重度」定档,"
              "而不是拍脑袋给 60/80/100。", "",
              "### 3. 阈值来自 1.x 当年的业务流量,必须按自己的量级校准", ""]

    windows = [n["left"]["counter"]["window"]
               for d in S for n in comparisons_with(d, "counter")]
    lines += [f"这批模板里的阈值(`>5 in 5m`、`>300 in 5m` 之类)是 1.x 某个电商站点的经验值,"
              f"窗口集中在 {fmt_duration(min(windows))}–{fmt_duration(max(windows))}。"
              "**直接照搬几乎一定不合适**:",
              "",
              "- 流量比当年大的站点会被淹没在误报里;流量小的站点则永远打不到阈值;",
              "- 移动端 App 与 Web 的行为基线差别很大(模板里 `IP页面停留时间过短App/Web` 就分了两条);",
              "- NAT、企业出口、运营商网关后面的 IP 天然「多用户多设备」,IP 维度阈值尤其需要放宽或加白名单。",
              "",
              "**推荐做法**:先把策略置为 `test` 状态跑历史回放或影子流量,统计每条策略的命中量与命中主体分布,"
              "再把阈值定在「日均命中量可人工消化」的水位上。"
              "另外注意 2.0 的 `distinct_count` 修正了 1.x 的高估问题,"
              "同一份流量下新值会**略低于**旧值,基于去重计数的阈值需要下调"
              "(见[从 1.x 迁移](../migration/from-1x.md))。", "",
              "### 4. 其它需要留意的现状", ""]

    status_txt = "、".join(f"`{k}` × {n}" for k, n in sorted(statuses.items()))
    if statuses.get("online"):
        lines.append(f"- **导入即全部生效**:status 分布为 {status_txt}。"
                     f"{statuses['online']} 条策略的状态是 `online`,导入后会立刻开始产生告警。"
                     "如需先观察,导入后请批量改为 `test`。")
    else:
        lines.append(f"- **默认为观察状态**:status 分布为 {status_txt}。"
                     "模板全部以 `test` 状态分发 —— 照常计算并产出告警,但告警标记 `test=true`,"
                     "不参与线上决策。校准完阈值后再逐条切到 `online`。")
    if ends:
        lines.append(f"- **生效时间戳是历史值**:{len(ends)} 条模板的 `effective_to` 落在 "
                     f"{fmt_date(min(ends))} — {fmt_date(max(ends))} 之间。若已是过去时间,"
                     "引擎按生效期判定后策略会「一条都不命中」—— 需要在导入时重写该字段。")
    else:
        note = ("- **不设生效截止时间**:全部模板的 `effective_to` 都是 `null`(长期有效)。"
                "1.x 出厂数据中该字段全部是当年生产实例的历史值且均已过期,"
                "照原样分发会导致导入后**一条都不触发且没有任何提示**,"
                "因此在 seeds 中做了规范化,详见 "
                "[`seeds/INVENTORY.md`](../../seeds/INVENTORY.md)。")
        if starts:
            note += (f"`effective_from` 保留了 1.x 的原值({fmt_date(min(starts))} — "
                     f"{fmt_date(max(starts))}),都是过去时间,不影响生效判定。")
        lines.append(note)
    ttls = Counter(d["action"]["ttl"] for d in S)
    top_ttl, top_ttl_n = ttls.most_common(1)[0]
    lines += [
        f"- **名单有效期普遍很短**:{top_ttl_n}/{total} 条策略的 `action.ttl` 是 "
        f"{fmt_duration(top_ttl)},只够用于实时联防;要做长期黑名单需要自己调 `ttl` 或在下游落库。",
        "- **策略之间会重复命中**:三维度镜像意味着一次攻击往往同时触发 3 条策略,"
        "告警去重与合并要在消费侧做,否则运营会被同一事件刷屏。"]

    dedup = sum(1 for d in S if d.get("dedup_window") is not None)
    if not dedup:
        lines.append("- **未设置去重窗口**:没有一条模板显式写 `dedup_window`,全部落到 schema 默认的 "
                     "300 秒。同一主体同一策略 5 分钟内只出一条告警,评估命中量时要把这一点算进去。")

    remark_bad = [m for m in (remark_vs_condition_mismatch(d) for d in S) if m]
    if remark_bad:
        detail = "、".join(f"`{doc['name']}`(备注 {rem},实际 `{actual}`)"
                          for doc, rem, actual in sorted(remark_bad, key=lambda m: m[0]["name"]))
        lines.append(f"- **个别策略的备注与实际条件不符**:{detail}。"
                     "`==` 意味着「不多不少正好等于」,在真实流量里几乎永远不命中,"
                     "看起来是 1.x 里写错了运算符。本文表格中的「检测什么」以 **`condition` 实际条件**为准,"
                     "备注仅供对照。")

    regex_bad = [(d, hits) for d, hits in ((d, regex_value_with_substring_op(d)) for d in S) if hits]
    if regex_bad:
        pairs = sorted({(f"{obj} {op} {value}") for _, hits in regex_bad
                        for obj, op, value in hits})
        names = "、".join(f"`{d['name']}`" for d, _ in sorted(regex_bad, key=lambda x: x[0]["name"]))
        lines.append(f"- **{len(regex_bad)} 条策略把正则写进了子串算子**:{names} 的计数器过滤条件是 "
                     + "、".join(code(p) for p in pairs) +
                     " —— `contains` 做的是**子串包含**,不会把 "
                     "`^\\s*$` 当正则解释,因此该条件永不成立、计数器恒为 0,策略实际上是死的。"
                     "作者本意应当是 `!regex ^\\s*$`(即「page 非空」)。"
                     "这是 1.x 数据里的既有缺陷,转换到 2.0 时按原样保留;要启用这几条请先改算子。")

    test_like = [d["name"] for d in S if d["name"].startswith("测试")]
    if test_like:
        lines.append("- **含测试策略**:" + "、".join(f"`{n}`" for n in sorted(test_like))
                     + " 是 1.x 留下的功能验证策略,没有业务含义,建议导入后直接停用或删除。")
    lines += ["", "---", "",
              "*本文由 `tools/gen_strategy_reference.py` 生成。数据源:`seeds/strategies/`"
              f"({total} 条,2.0 schema 结构)、`seeds/events/`、`seeds/variables/`。*", ""]
    return lines


def render(seeds, out_dir=DEFAULT_OUT.parent):
    lines = []
    lines += sec_header(seeds)
    lines += sec_overview(seeds)
    lines += sec_reading_guide(seeds)
    lines += sec_full_table(seeds)
    lines += sec_mirrors(seeds)
    lines += sec_delay(seeds)
    lines += sec_requires_config(seeds, out_dir)
    lines += sec_caveats(seeds)
    text = "\n".join(lines)
    return text if text.endswith("\n") else text + "\n"


# ---------------------------------------------------------------- CLI


def main(argv=None):
    parser = argparse.ArgumentParser(description="生成《策略模板参考》docs/reference/strategies.md")
    parser.add_argument("--seeds", type=pathlib.Path, default=SEEDS, help="seeds 目录(默认:仓库内 seeds/)")
    parser.add_argument("--output", "-o", type=pathlib.Path, default=DEFAULT_OUT, help="输出文件路径")
    parser.add_argument("--check", action="store_true",
                        help="不写文件,校验已有内容与生成结果一致;不一致时打印 diff 并返回 1")
    parser.add_argument("--stdout", action="store_true", help="打印到标准输出")
    args = parser.parse_args(argv)

    if not (args.seeds / "strategies" / "index.json").exists():
        print(f"找不到 {args.seeds / 'strategies' / 'index.json'}", file=sys.stderr)
        return 2

    seeds = Seeds(args.seeds)
    text = render(seeds, args.output.parent)

    if args.stdout:
        sys.stdout.write(text)
        return 0

    if args.check:
        if not args.output.exists():
            print(f"✗ {rel(args.output)} 不存在,请运行 "
                  f"python3 tools/gen_strategy_reference.py 生成。", file=sys.stderr)
            return 1
        current = args.output.read_text(encoding="utf-8")
        if current == text:
            print(f"✓ {rel(args.output)} 与 seeds 一致({len(seeds.strategies)} 条策略)")
            return 0
        diff = difflib.unified_diff(current.splitlines(True), text.splitlines(True),
                                    fromfile="已提交内容", tofile="按 seeds 生成", n=2)
        sys.stderr.writelines(list(diff)[:200])
        print(f"\n✗ {rel(args.output)} 与 seeds 不一致,请重新运行 "
              f"python3 tools/gen_strategy_reference.py 并提交结果。", file=sys.stderr)
        return 1

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(text, encoding="utf-8")
    print(f"✓ 已生成 {rel(args.output)}"
          f"({len(seeds.strategies)} 条策略,{len(text.splitlines())} 行)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
