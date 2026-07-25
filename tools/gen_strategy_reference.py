#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成《策略模板参考》—— docs/reference/strategies.md。

数据源:seeds/strategies/(index.json + 170 个策略模板),
辅以 seeds/events/(字段中文名)与 seeds/variables/(变量中文名)。

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

# 「如何读懂一条策略」使用的样例策略:含事件条件、内联计数器、名单处置三类表达式,结构最有代表性
EXAMPLE_STRATEGY = "IP多次登录失败"

# ---------------------------------------------------------------- 展示用词表

DIM_PREFIXES = ("IP", "用户", "设备")

CHECKTYPE_CN = {"IP": "IP", "USER": "账号", "DeviceID": "设备"}

SUBJECT_CN = {"c_ip": "同一 IP", "did": "同一设备", "uid": "同一账号"}

OP_CN = {
    ">": ">", "<": "<", ">=": "≥", "<=": "≤", "==": "=", "=": "=", "!=": "≠",
    "regex": "匹配正则", "!regex": "不匹配正则",
    "contain": "包含", "!contain": "不包含",
    "in": "属于", "!in": "不属于",
    "startwith": "以…开头", "endwith": "以…结尾",
}

SUBTYPE_CN = {
    "count": "内联计数器 count",
    "getvariable": "变量引用 getvariable",
    "setblacklist": "名单处置 setblacklist",
    "time": "时间段判定 time",
    "sleep": "延迟判定 sleep",
    "getlocation": "地域判定 getlocation",
}

SUBTYPE_DESC = {
    "count": "在策略里就地定义一个窗口计数器(等价于临时变量):指定源事件、分组键、统计对象、窗口长度与过滤条件。",
    "getvariable": "引用 `seeds/variables/` 中已定义的统计变量,复用其计算结果。",
    "setblacklist": "命中后的处置动作:把某个主体(IP / 账号 / 设备)写入风险名单,并附带决策与有效期。",
    "time": "限定事件发生的时钟区间,用于「深夜下单」这类与时段相关的风险。",
    "sleep": "延迟一段时间后再判定,用于「做了 A 却始终没做 B」这类否定式风险。",
    "getlocation": "由 IP 求归属地并与给定地域比较。",
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
        "比较运算符是 `contain`,填子串即可。"
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

        # 事件:visible_name 与字段中文名
        self.event_label = {}
        self.field_label = defaultdict(dict)
        ev_index = load_json(seeds_dir / "events" / "index.json")
        for e in ev_index["events"]:
            doc = load_json(seeds_dir / e["file"])
            key = (doc["app"], doc["name"])
            self.event_label[key] = doc.get("visible_name") or doc["name"]
            for prop in doc.get("properties", []):
                self.field_label[key][prop["name"]] = prop.get("visible_name") or prop["name"]

        # 变量:visible_name
        self.variable_label = {}
        for v in load_json(seeds_dir / "variables" / "index.json")["variables"]:
            self.variable_label[v["name"]] = v.get("visible_name") or ""

    # -- 展示辅助 --------------------------------------------------

    def event_cn(self, event):
        key = tuple(event)
        return self.event_label.get(key, key[-1])

    def field_cn(self, event, field):
        """字段中文名。事件自身没有该字段时回落到基础事件 HTTP_DYNAMIC。"""
        key = tuple(event)
        label = self.field_label.get(key, {}).get(field)
        if not label:
            label = self.field_label.get(("nebula", "HTTP_DYNAMIC"), {}).get(field)
        return f"{label}({field})" if label and label != field else field

    def variable_cn(self, variable):
        name = variable[-1]
        label = self.variable_label.get(name)
        return f"`{name}`({label})" if label else f"`{name}`"


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


# ---------------------------------------------------------------- 表达式渲染


def compare(left, op, right):
    if not op:
        return left
    return f"{left} {op_cn(op)} {right}"


def render_condition(seeds, event, cond, short=False):
    """count 内联计数器的 condition 条目。"""
    left = seeds.field_cn(event, cond["left"])
    right = cond["right"]
    if is_blank_check(cond["op"], right):
        return f"{left} 非空"
    if cond["op"] in ("regex", "!regex", "contain", "!contain", "in", "!in", "endwith", "startwith"):
        return compare(left, cond["op"], code(clip(right) if short else right))
    return compare(left, cond["op"], code(right))


def meaningful_conditions(cfg):
    """剔除 `c_ip = c_ip` 这类把分组键绑定到自身的样板条件。"""
    out = []
    for c in cfg.get("condition", []):
        if c.get("op") == "=" and c.get("left") == c.get("right"):
            continue
        out.append(c)
    return out


def render_count(seeds, cfg, short=False):
    event = cfg["sourceevent"]
    window = fmt_duration(cfg["interval"])
    if cfg["algorithm"] == "distinct_count":
        operand = "、".join(seeds.field_cn(event, o) for o in cfg["operand"])
        stat = f"去重统计 {operand} 的个数" if not short else f"的{operand}去重数"
    else:
        stat = "累计事件次数" if not short else "的次数"
    conds = meaningful_conditions(cfg)
    if short:
        # 摘要里省略 `page 非空` 这类噪声过滤,以及与分组键重复的信息
        conds = [c for c in conds if not is_blank_check(c["op"], c["right"])]
    cond_txt = ""
    if conds:
        joined = "、".join(render_condition(seeds, event, c, short) for c in conds)
        cond_txt = f"({joined})" if short else f"(满足 {joined})"
    if short:
        return f"{window}内「{seeds.event_cn(event)}」事件{cond_txt}{stat}"
    groupby = "、".join(seeds.field_cn(event, g) for g in cfg["groupby"])
    return f"最近 {window}内、按 {groupby} 分组的「{seeds.event_cn(event)}」事件{cond_txt}{stat}"


def render_left(seeds, left, short=False):
    typ, sub, cfg = left.get("type"), left.get("subtype"), left.get("config", {})
    if typ == "event":
        field = seeds.field_cn(cfg["event"], cfg["field"])
        if short:
            return field
        return f"「{seeds.event_cn(cfg['event'])}」事件的{field}"
    if sub == "count":
        return render_count(seeds, cfg, short)
    if sub == "getvariable":
        label = seeds.variable_cn(cfg["variable"])
        return f"变量 {label}" if short else f"变量 {label} 的取值"
    if sub == "setblacklist":
        subject = CHECKTYPE_CN.get(cfg["checktype"], cfg["checktype"])
        return (f"把本次事件的 {cfg['checkvalue']} 作为{subject}写入 {cfg['name']} 名单,"
                f"决策 {code(cfg['decision'])},有效期 {fmt_duration(cfg['ttl'])}")
    if sub == "time":
        return f"事件发生时刻在 {cfg['start']}–{cfg['end']} 之间"
    if sub == "sleep":
        unit = {"s": "秒", "m": "分钟", "h": "小时", "d": "天"}.get(cfg.get("unit"), cfg.get("unit", ""))
        return f"等待 {cfg['duration']} {unit}后再判定后续条件"
    if sub == "getlocation":
        loc = "、".join(cfg.get("location_string") or [])
        return (f"{code(cfg.get('source_event_key'))} 的归属地({cfg.get('location_type')})"
                f"{op_cn(cfg.get('op', '='))} {loc}")
    return f"{typ}/{sub}"


def render_term(seeds, term, short=False):
    """把一条 term 翻译成一句自然语言。"""
    left = render_left(seeds, term["left"], short)
    op, right = term.get("op"), term.get("right")
    if not op or right is None:
        text = left
    else:
        value = right.get("config", {}).get("value", "")
        if is_blank_check(op, value):
            text = f"{left} 非空"
        elif op in ("regex", "!regex", "contain", "!contain", "in", "!in", "endwith", "startwith"):
            text = compare(left, op, code(clip(value) if short else value))
        else:
            text = compare(left, op, code(value))
    remark = (term.get("remark") or "").strip()
    if remark and not short:
        text = f"{text} —— {remark}"
    return text


# ---------------------------------------------------------------- 策略级派生


def terms_of(doc, subtype=None, typ=None):
    out = []
    for t in doc["terms"]:
        left = t["left"]
        if subtype and left.get("subtype") != subtype:
            continue
        if typ and left.get("type") != typ:
            continue
        out.append(t)
    return out


def blacklist_cfg(doc):
    terms = terms_of(doc, subtype="setblacklist")
    return terms[0]["left"]["config"] if terms else None


def stat_dimension(doc):
    """策略实际的统计口径(分组键),优先取 count 的 groupby,其次取 trigger.keys。"""
    for t in doc["terms"]:
        cfg = t["left"].get("config", {})
        if t["left"].get("subtype") == "count" and cfg.get("groupby"):
            return cfg["groupby"][0]
    for t in doc["terms"]:
        cfg = t["left"].get("config", {})
        trigger = cfg.get("trigger") or {}
        if trigger.get("keys"):
            return trigger["keys"][0]
    bl = blacklist_cfg(doc)
    return bl["checkvalue"] if bl else ""


def threshold_terms(doc):
    return [t for t in doc["terms"]
            if t["left"].get("subtype") in ("count", "getvariable") and t.get("op")]


VAR_WINDOW_RE = re.compile(r"__(\d+[smhd])__")


VAR_UNIT_SECONDS = {"s": 1, "m": 60, "h": 3600, "d": 86400}


def threshold_signature(doc):
    """主阈值签名,例如 `>5 / 10 分钟`,用于比对镜像策略之间的阈值差异。

    一条策略可能有多个阈值条件(例如「次数 > 5 且页面数 <= 4」),这里取最能代表
    该策略量级的那个:优先取「大于」类比较,其次取第一个。
    """
    terms = threshold_terms(doc)
    if not terms:
        return "无阈值(单事件特征匹配)"
    t = next((x for x in terms if x["op"] in (">", ">=")), terms[0])
    cfg = t["left"]["config"]
    value = t["right"]["config"]["value"]
    if t["left"]["subtype"] == "count":
        window = fmt_duration(cfg["interval"])
    else:
        m = VAR_WINDOW_RE.search(cfg["variable"][-1])
        if m:
            num, unit = int(m.group(1)[:-1]), m.group(1)[-1]
            window = fmt_duration(num * VAR_UNIT_SECONDS.get(unit, 1))
        else:
            window = "—"
    return f"{op_cn(t['op'])}{value} / {window}"


def counted_conditions(doc):
    """内联计数器里已经表达过的过滤条件,用 (字段, 运算符, 取值) 表示。"""
    seen = set()
    for t in terms_of(doc, subtype="count"):
        for c in t["left"]["config"].get("condition", []):
            seen.add((c["left"], c["op"], c["right"]))
    return seen


def summarize(seeds, doc):
    """从 terms 归纳出「检测什么」的一句话。"""
    subject = SUBJECT_CN.get(stat_dimension(doc), "同一主体")
    counted = counted_conditions(doc)
    main, filters, extras = [], [], []
    for t in doc["terms"]:
        sub = t["left"].get("subtype")
        if sub == "setblacklist":
            continue
        if sub in ("count", "getvariable"):
            main.append(render_term(seeds, t, short=True))
        elif t["left"].get("type") == "event":
            cfg = t["left"]["config"]
            value = (t.get("right") or {}).get("config", {}).get("value", "")
            if is_blank_check(t.get("op"), value):
                continue  # `page 非空` 是绝大多数策略都有的噪声过滤,不进摘要
            if (cfg["field"], t.get("op"), value) in counted:
                continue  # 同一条件在计数器里已经写过一遍,不重复叙述
            filters.append(render_term(seeds, t, short=True))
        else:
            extras.append(render_term(seeds, t, short=True))
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
    return f"{subject}:" + ",".join(parts) if parts else subject


def disposition(doc):
    cfg = blacklist_cfg(doc)
    if not cfg:
        return "无处置动作"
    subject = CHECKTYPE_CN.get(cfg["checktype"], cfg["checktype"])
    return (f"`{cfg['decision']}` · {subject}名单({cfg['checkvalue']})"
            f" · {cfg['name']} · {fmt_duration(cfg['ttl'])}")


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


# 「骨架策略」里把页面路径写成 A / B 这类单个大写字母的字面占位值
PLACEHOLDER_LITERAL_RE = re.compile(r"[A-Z]")


def skeleton_placeholders(seeds, doc):
    """index.json 未标记、但把 page 写成 `A`/`B` 字面占位值的骨架策略。"""
    if seeds.meta[doc["name"]].get("requires_configuration"):
        return []
    values = []
    for t in doc["terms"]:
        cfg = t["left"].get("config", {})
        if t["left"].get("type") == "event" and cfg.get("field") == "page":
            value = (t.get("right") or {}).get("config", {}).get("value", "")
            if PLACEHOLDER_LITERAL_RE.fullmatch(str(value)):
                values.append(value)
        for c in cfg.get("condition", []):
            if c.get("left") == "page" and PLACEHOLDER_LITERAL_RE.fullmatch(str(c.get("right", ""))):
                values.append(c["right"])
    return sorted(set(values))

REMARK_GT_RE = re.compile(r"\s*>\s*([\d,]+)")


def remark_vs_terms_mismatch(doc):
    """备注写「> N」但实际条件是「== N」—— 1.x 里的手滑,会让策略几乎永远不命中。"""
    m = REMARK_GT_RE.match(doc.get("remark") or "")
    if not m:
        return None
    number = m.group(1).replace(",", "")
    for t in threshold_terms(doc):
        if t.get("op") == "==" and t["right"]["config"]["value"] == number:
            return (doc, doc["remark"].strip(), f"=={number}")
    return None


def name_vs_list_mismatch(doc):
    """策略名声明的维度与它实际写入的名单主体是否一致。"""
    dim = dimension_of(doc["name"])
    cfg = blacklist_cfg(doc)
    if not dim or not cfg:
        return None
    expected = {"IP": "IP", "用户": "USER", "设备": "DeviceID"}[dim]
    if cfg["checktype"] == expected:
        return None
    return (doc, expected, cfg["checktype"])


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
        f"(`seeds/strategies/`,源表 `{seeds.index['source_table']}`)。",
        "它们是**模板**而不是开箱即用的生产策略:阈值来自 1.x 当年的业务流量,处置动作全部是「待人工审核」,"
        "启用前请先读[重要提示](#重要提示启用前必读)。",
        "",
        "## 目录",
        "",
        "- [概览](#概览)",
        "- [如何读懂一条策略](#如何读懂一条策略)",
        "- [按场景分组的策略全表](#按场景分组的策略全表)",
        "- [三维度镜像设计](#三维度镜像设计)",
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
        cfg = blacklist_cfg(d)
        if cfg:
            subj[cfg["checktype"]] += 1
            ttl_by_subj[cfg["checktype"]][cfg["ttl"]] += 1
    lines += ["### 按名单主体类型(checktype)", "",
              "命中后写入哪一类风险名单,决定了业务侧该拦谁。", "",
              "| 名单主体 | 含义 | 策略数 | 占比 | 名单有效期分布 |", "|---|---|---:|---:|---|"]
    for k, n in sorted(subj.items(), key=lambda kv: (-kv[1], kv[0])):
        ttls = "、".join(f"{fmt_duration(t)}×{c}" for t, c in sorted(ttl_by_subj[k].items()))
        lines.append(f"| `{k}` | {CHECKTYPE_CN.get(k, k)} | {n} | {n / total:.0%} | {cell(ttls)} |")
    lines += [f"| **合计** | | **{total}** | | |", ""]

    windows = Counter()
    for d in S:
        for t in terms_of(d, subtype="count"):
            windows[t["left"]["config"]["interval"]] += 1
    lines += ["### 统计窗口分布", "",
              "内联计数器(`count`)所用的窗口长度,反映 1.x 的口径偏好。", "",
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

    lines = ["## 如何读懂一条策略", "",
             f"后面的表格是归纳结果。要看懂原始模板,先读这一节 —— 以 **{doc['name']}** "
             f"(`{meta['file']}`)为例,把它的 JSON 逐条翻译成人话。", "",
             "### 1. 头部字段", "",
             "```json",
             json.dumps({k: doc[k] for k in
                         ("app", "name", "category", "tags", "remark", "score", "status")},
                        ensure_ascii=False, indent=2),
             "```", "",
             "| 字段 | 本例取值 | 含义 |", "|---|---|---|"]
    header_rows = [
        ("app", doc["app"], "应用命名空间,内置资产统一为 `nebula`,不是客户名"),
        ("name", doc["name"], "策略名,全局唯一,也是名单来源的标识"),
        ("category", doc["category"], "场景大类,决定写入哪个名单集合"),
        ("tags", "、".join(doc.get("tags") or []) or "—", "风险标签,用于报表聚合"),
        ("remark", doc["remark"], "1.x 作者留下的速记备注,通常是「阈值 + 窗口」"),
        ("score", doc["score"], "风险分权重(1.x 未落地,见文末提示)"),
        ("status", doc["status"], "`online` 生效 / `test` 只观察不产出 / `offline` 停用"),
        ("group_id", doc["group_id"], "1.x 的策略分组编号,2.0 未使用"),
        ("is_locked", json.dumps(doc["is_locked"]), "是否被编辑锁定"),
        ("start_effect / end_effect", f"{fmt_date(doc['start_effect'])} / {fmt_date(doc['end_effect'])}",
         "生效起止时间(毫秒时间戳),继承自 1.x 的历史值"),
        ("version", doc["version"], "模板版本号"),
    ]
    for k, v, note in header_rows:
        lines.append(f"| `{k}` | {cell(v)} | {cell(note)} |")

    lines += ["", "### 2. terms:条件与动作的列表", "",
              "`terms` 是策略的主体,是一个**扁平列表**。1.x 的语义是:",
              "",
              "- 所有 term **之间只有 AND 关系**,不支持 OR 和嵌套(2.0 支持嵌套布尔,见"
              "[从 1.x 迁移](../migration/from-1x.md));",
              "- 每条 term 形如 `left` `op` `right`,`op` 为空表示这条 term 不是比较、而是一个**动作或修饰**"
              "(例如写名单、延时、限定时段);",
              "- `scope` 取 `realtime`(实时窗口)或 `profile`(离线画像);",
              "- `remark` 是作者对该条件的注释。",
              "",
              "`left` 的 `type`/`subtype` 决定了这条 term 是什么。全部 "
              f"{len(seeds.strategies)} 条策略中出现过的类型:", ""]

    kinds = Counter()
    for d in seeds.strategies:
        for t in d["terms"]:
            left = t["left"]
            kinds[(left.get("type"), left.get("subtype") or "")] += 1
    lines += ["| type | subtype | 出现次数 | 含义 |", "|---|---|---:|---|"]
    type_desc = {
        ("event", ""): "取当前事件的某个字段做比较,是最基础的过滤条件。",
        ("constant", ""): "常量,只出现在 `right` 侧。",
    }
    for (typ, sub), n in sorted(kinds.items(), key=lambda kv: (-kv[1], kv[0])):
        desc = type_desc.get((typ, sub)) or SUBTYPE_DESC.get(sub, "")
        lines.append(f"| `{typ}` | {('`' + sub + '`') if sub else '—'} | {n} | {cell(desc)} |")
    const_n = sum(1 for d in seeds.strategies for t in d["terms"]
                  if isinstance(t.get("right"), dict) and t["right"].get("type") == "constant")
    lines += ["", f"(`right` 侧共出现 {const_n} 次 `constant` 常量,不再单列。)", "",
              "### 3. 逐条翻译本例的 terms", ""]

    for i, term in enumerate(doc["terms"], 1):
        left = term["left"]
        kind = SUBTYPE_CN.get(left.get("subtype")) or "事件条件 event"
        lines += [f"**term {i} —— {kind}**", "", "```json",
                  json.dumps(term, ensure_ascii=False, indent=2), "```", "",
                  f"→ {render_term(seeds, term)}", ""]

    lines += ["合起来,这条策略的意思是:", "",
              f"> {summarize(seeds, doc)}", "",
              "> 命中后:" + disposition(doc).replace("`", "") + "。", "",
              "几个容易踩的点:", "",
              "- `page` 是**伪静态化之后的页面标识**,不是原始 URI;`page 不匹配正则 ^\\s*$` "
              "这类条件在多数策略里都有,含义是「只统计能解析出页面的请求」,属于噪声过滤;",
              "- 内联计数器的 `condition` 里那条 `c_ip = c_ip` 是把分组键绑定到自身的样板写法,不是真实过滤条件;",
              "- `trigger` 指定**哪个事件触发本次判定**,它可以与 `sourceevent`(被统计的事件)不同 —— "
              "「下单不支付」正是靠这一点实现的:下单事件触发,却去数支付页面的访问量;",
              "- `algorithm` 为 `distinct_count` 时统计的是 `operand` 的**去重个数**,为 `count` 时统计**事件条数**。",
              "", "---", ""]
    return lines


def sec_full_table(seeds):
    lines = ["## 按场景分组的策略全表", "",
             "「检测什么」由 `remark` 与 `terms` 归纳而来;「命中后处置」的四段依次是 "
             "**决策 · 名单主体(取值字段) · 名单集合 · 有效期**。", ""]

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
            summary = summarize(seeds, doc)
            summary = f"{summary}(原始备注:{code(doc['remark'].strip() or '—')})"
            tags = "、".join(doc.get("tags") or []) or "—"
            lines.append(f"| **{cell(doc['name'])}**{flag} | {cell(summary)} | "
                         f"{cell(disposition(doc))} | {cell(tags)} |")
        lines.append("")
    lines += ["🔧 = 含占位符,需要先配置才能生效,见[下文](#需要配置才能生效的策略)。", "", "---", ""]
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
             "同一族的三条策略结构几乎相同,区别只在分组键(`groupby`)、触发键(`trigger.keys`)"
             "和写入的名单主体(`checktype`)。",
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
                          "复制一条,改 `groupby`、`trigger.keys`、`condition` 里的键和 `checkvalue`/`checktype` 即可。")

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
                  "生成器发现下列策略的**名字声明的维度**与**实际写入的名单主体**对不上 —— "
                  "这是 1.x 数据里的既有问题(复制粘贴时漏改),不是生成器的误判。"
                  "启用前请逐条确认到底想拉黑谁。", "",
                  "| 策略名 | 名称暗示的主体 | 实际写入的名单 | 实际统计口径(groupby) |",
                  "|---|---|---|---|"]
        for doc, expected, actual in sorted(mismatches, key=lambda m: m[0]["name"]):
            lines.append(f"| {cell(doc['name'])} | `{expected}` | `{actual}` | "
                         f"`{cell(stat_dimension(doc))}` |")
        lines.append("")
    lines += ["---", ""]
    return lines


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
            spots = []
            for t in doc["terms"]:
                cfg = t["left"].get("config", {})
                for c in cfg.get("condition", []):
                    if c.get("right") == ph:
                        spots.append(f"`{'.'.join(cfg['sourceevent'])}` 计数器条件 "
                                     f"`{c['left']} {c['op']} {ph}`")
                if (t.get("right") or {}).get("config", {}).get("value") == ph:
                    spots.append(f"事件条件 `{cfg.get('field', '')} {t['op']} {ph}`")
            consequence = ("计数器恒为 0 —— 策略会把**所有**下单主体都判成「下单不支付」"
                           if "不支付" in e["name"] else
                           "计数器恒为 0 —— 策略会把**所有**登录/注册主体都判成「未访问必要资源」")
            lines.append(f"| **{cell(e['name'])}** | {cell('；'.join(dict.fromkeys(spots)) or '—')} | "
                         f"{cell(consequence)} |")
        lines.append("")

    skeletons = [(doc, vals) for doc, vals in
                 ((d, skeleton_placeholders(seeds, d)) for d in seeds.strategies) if vals]

    if skeletons:
        lines += ["### 另外 %d 条「骨架策略」(index.json 未标记)" % len(skeletons), "",
                  "这几条策略把页面路径写成了 `A`、`B` 这样的字面占位值 —— 它们是 1.x 留下的**模式骨架**,"
                  "不是可用策略。`index.json` 没有把它们标成 `requires_configuration`(占位符扫描只认 "
                  "`<YOUR_*>` 形式),但不改同样不会有任何意义:`page == \"A\"` 在真实流量里永不成立。", "",
                  "| 策略名 | 占位值 | 要填什么 |", "|---|---|---|"]
        for doc, values in sorted(skeletons, key=lambda x: x[0]["name"]):
            lines.append(f"| **{cell(doc['name'])}** | {cell('、'.join(code(v) for v in values))} | "
                         f"A = 先访问的页面,B = 本应随后访问的页面;两处 `page` 条件都要换成真实路径 |")
        lines += ["", "语义是「访问了 A,但 5 分钟内没有访问 B」—— 用 `sleep` 延迟判定实现,"
                  "是模板里唯一一组否定式(negative)策略,可以拿它当自定义策略的样板。", ""]

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
    decisions = Counter()
    for d in S:
        cfg = blacklist_cfg(d)
        if cfg:
            decisions[cfg["decision"]] += 1
    scores = Counter(d["score"] for d in S)
    statuses = Counter(d["status"] for d in S)
    nonzero = sorted((d["name"], d["score"]) for d in S if d["score"] != 0)
    max_end = max(d["end_effect"] for d in S)
    min_end = min(d["end_effect"] for d in S)

    dec_txt = "、".join(f"`{k}` × {n}" for k, n in sorted(decisions.items()))
    score_txt = "、".join(f"{k} × {n}" for k, n in sorted(scores.items()))

    lines = ["## 重要提示(启用前必读)", "",
             "以下不是「注意事项」式的套话,而是这批模板**当前的真实状态**。全部由生成器从数据中统计得出。", "",
             "### 1. 没有一条策略会自动阻断", "",
             f"{total} 条策略的处置决策分布:{dec_txt}。",
             "",
             f"也就是说 **{decisions.get('review', 0)}/{total} 条全部是 `review`(转人工审核)**,"
             "没有任何一条会自动拦截、二次验证或限流。1.x 当年设计了处置能力但没有落地,"
             "系统实际只能产出「待审核」告警。",
             "",
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
              "无法按分数分级处置。要用起来,需要按自身业务给每条策略赋权 —— "
              "通常按「误报代价 × 风险严重度」定档,而不是拍脑袋给 60/80/100。", "",
              "### 3. 阈值来自 1.x 当年的业务流量,必须按自己的量级校准", ""]

    ths = []
    for d in S:
        for t in terms_of(d, subtype="count"):
            ths.append(t["left"]["config"]["interval"])
    lines += [f"这批模板里的阈值(`>5 in 5m`、`>300 in 5m` 之类)是 1.x 某个电商站点的经验值,"
              f"窗口集中在 {fmt_duration(min(ths))}–{fmt_duration(max(ths))}。**直接照搬几乎一定不合适**:",
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
    lines += [f"- **导入即全部生效**:status 分布为 {status_txt}。"
              f"{statuses.get('online', 0)} 条策略的状态是 `online`,导入后会立刻开始产生告警。"
              "如需先观察,导入后请批量改为 `test`。",
              f"- **生效时间戳是历史值**:所有模板的 `end_effect` 落在 "
              f"{fmt_date(min_end)} — {fmt_date(max_end)} 之间,均已是过去时间。"
              "如果引擎严格按这两个字段判生效期,导入后策略会「一条都不命中」—— 需要在导入时重写这两个字段。",
              "- **名单有效期普遍很短**:多数策略的 TTL 是 5 分钟,只够用于实时联防;"
              "要做长期黑名单需要自己调 `ttl` 或在下游落库。",
              "- **策略之间会重复命中**:三维度镜像意味着一次攻击往往同时触发 3 条策略,"
              "告警去重与合并要在消费侧做,否则运营会被同一事件刷屏。"]

    remark_bad = [m for m in (remark_vs_terms_mismatch(d) for d in S) if m]
    if remark_bad:
        detail = "、".join(f"`{doc['name']}`(备注 {rem},实际 `{actual}`)"
                          for doc, rem, actual in sorted(remark_bad, key=lambda m: m[0]["name"]))
        lines.append(f"- **个别策略的备注与实际条件不符**:{detail}。"
                     "`==` 意味着「不多不少正好等于」,在真实流量里几乎永远不命中,"
                     "看起来是 1.x 里写错了运算符。本文表格中的「检测什么」以 **terms 实际条件**为准,"
                     "备注仅供对照。")

    test_like = [d["name"] for d in S if d["name"].startswith("测试")]
    if test_like:
        lines.append("- **含测试策略**:" + "、".join(f"`{n}`" for n in sorted(test_like))
                     + " 是 1.x 留下的功能验证策略,没有业务含义,建议导入后直接停用或删除。")
    lines += ["", "---", "",
              "*本文由 `tools/gen_strategy_reference.py` 生成。数据源:`seeds/strategies/`"
              f"({total} 条)、`seeds/events/`、`seeds/variables/`。*", ""]
    return lines


def render(seeds, out_dir=DEFAULT_OUT.parent):
    lines = []
    lines += sec_header(seeds)
    lines += sec_overview(seeds)
    lines += sec_reading_guide(seeds)
    lines += sec_full_table(seeds)
    lines += sec_mirrors(seeds)
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
