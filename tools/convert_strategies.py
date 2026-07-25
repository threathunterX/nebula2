#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 seeds/strategies/ 下的 1.x 结构策略转换为 2.0 schema 结构。

1.x 的策略是一个扁平的 terms 数组,条款之间隐含 AND 关系,其中一条是
setblacklist(处置动作)而非条件。2.0 把条件与动作分开,条件是一棵可嵌套
的布尔树。

条款映射:

    1.x term                        2.0
    ----------------------------------------------------------------
    event                       ->  comparison(left = event_field)
    func:count                  ->  comparison(left = counter)
    func:getvariable            ->  comparison(left = variable)
    func:time                   ->  expression(CEL: inTimeWindow)
    func:getlocation            ->  expression(CEL: ipLocation)
    func:sleep                  ->  strategy.delay,其后的条款进入 delay.condition
    func:setblacklist           ->  strategy.action

转换是**保守**的:遇到无法确定语义的结构会中止并报错,不做猜测。
用法:
    python3 tools/convert_strategies.py            # 转换全部
    python3 tools/convert_strategies.py --dry-run  # 只报告,不写文件
    python3 tools/convert_strategies.py --only <策略名>
"""
import argparse
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "seeds" / "strategies"

# 1.x 比较算子 -> 2.0。1.x 中 contain/containsby 是同一语义的不同写法。
OP_MAP = {
    "==": "==", "!=": "!=", ">": ">", "<": "<", ">=": ">=", "<=": "<=",
    "contain": "contains", "!contain": "!contains",
    "contains": "contains", "!contains": "!contains",
    "regex": "regex", "!regex": "!regex",
    "in": "in", "!in": "!in",
    "startwith": "startwith", "!startwith": "!startwith",
    "endwith": "endwith", "!endwith": "!endwith",
}


class ConvertError(Exception):
    pass


def qualified_event(ref):
    """['nebula', 'ACCOUNT_LOGIN'] -> 'ACCOUNT_LOGIN'"""
    if isinstance(ref, list) and len(ref) == 2:
        return ref[1]
    if isinstance(ref, str):
        return ref.split(".")[-1]
    raise ConvertError("无法解析事件引用: %r" % (ref,))


def conv_constant(node):
    """1.x 的 constant 值一律是字符串,这里保持原样 —— 引擎按左值类型做转换。
    过早在此处推断类型会引入错误(例如把 '5' 转成 int 但左值实际是字符串)。"""
    return {"kind": "constant", "value": node.get("config", {}).get("value")}


def conv_operand_right(node):
    if node is None:
        return None
    t = node.get("type")
    if t == "constant":
        return conv_constant(node)
    raise ConvertError("右值暂不支持的类型: %r" % (t,))


def conv_counter_filter(conditions, groupby):
    """1.x 内联计数器的 condition 数组含两类元素:
       - {'left': 'c_ip', 'op': '=', 'right': 'c_ip'} —— 分组维度对齐,不是过滤条件,
         它表达的是「按 c_ip 分组」,已由 groupby 表达,转换时丢弃
       - 真正的过滤条件
    """
    out = []
    for c in conditions or []:
        left, op, right = c.get("left"), c.get("op"), c.get("right")
        if op == "=" and left == right and left in (groupby or []):
            continue  # 分组对齐,非过滤
        if op not in OP_MAP:
            raise ConvertError("计数器过滤条件的算子无法映射: %r" % (op,))
        out.append({
            "type": "simple",
            "object": left,
            "operation": OP_MAP[op],
            "value": right,
        })
    if not out:
        return None
    if len(out) == 1:
        return out[0]
    return {"type": "and", "condition": out}


def conv_term(term, notes):
    """把一个 1.x 条款转换为 2.0 的 condition 节点。"""
    left = term.get("left") or {}
    ltype, lsub = left.get("type"), left.get("subtype")
    cfg = left.get("config") or {}
    op = term.get("op")
    right = term.get("right")

    if ltype == "event":
        if op not in OP_MAP:
            raise ConvertError("事件条款算子无法映射: %r" % (op,))
        return {
            "left": {"kind": "event_field", "field": cfg.get("field")},
            "op": OP_MAP[op],
            "right": conv_operand_right(right),
        }

    if ltype == "func" and lsub == "count":
        if op not in OP_MAP:
            raise ConvertError("计数器条款算子无法映射: %r" % (op,))
        groupby = cfg.get("groupby") or []
        counter = {
            "event": qualified_event(cfg.get("sourceevent")),
            "window": int(cfg.get("interval")),
            "algorithm": cfg.get("algorithm"),
            "groupby": groupby,
        }
        operand = cfg.get("operand") or []
        if operand:
            counter["operand"] = operand
        f = conv_counter_filter(cfg.get("condition"), groupby)
        if f:
            counter["filter"] = f
        return {
            "left": {"kind": "counter", "counter": counter},
            "op": OP_MAP[op],
            "right": conv_operand_right(right),
        }

    if ltype == "func" and lsub == "getvariable":
        if op not in OP_MAP:
            raise ConvertError("变量引用条款算子无法映射: %r" % (op,))
        var = cfg.get("variable")
        trig = cfg.get("trigger") or {}
        return {
            "left": {
                "kind": "variable",
                "variable": qualified_event(var) if isinstance(var, list) else var,
                "keys": trig.get("keys") or [],
            },
            "op": OP_MAP[op],
            "right": conv_operand_right(right),
        }

    if ltype == "func" and lsub == "time":
        start, end = cfg.get("start"), cfg.get("end")
        return {
            "cel": 'inTimeWindow("%s", "%s")' % (start, end),
            "remark": "事件发生在每日 %s ~ %s 之间" % (start, end),
        }

    if ltype == "func" and lsub == "getlocation":
        src = cfg.get("source_event_key") or ""
        field = src.split(".")[-1] if src else "c_ip"
        ltyp = cfg.get("location_type")
        vals = cfg.get("location_string") or []
        lop = cfg.get("op")
        if lop == "=":
            if len(vals) == 1:
                cel = 'ipLocation(%s, "%s") == "%s"' % (field, ltyp, vals[0])
            else:
                cel = 'ipLocation(%s, "%s") in %s' % (
                    field, ltyp, json.dumps(vals, ensure_ascii=False))
        elif lop in ("!=", "not"):
            cel = '!(ipLocation(%s, "%s") in %s)' % (
                field, ltyp, json.dumps(vals, ensure_ascii=False))
        else:
            raise ConvertError("地理条款算子无法映射: %r" % (lop,))
        notes.append("地理位置条款已转换为 CEL,依赖内置函数 ipLocation")
        return {"cel": cel, "remark": "IP 归属%s匹配 %s" % (ltyp, "、".join(vals))}

    raise ConvertError("未知条款类型: type=%r subtype=%r" % (ltype, lsub))


def conv_action(cfg):
    ttl = cfg.get("ttl")
    if not ttl or int(ttl) <= 0:
        raise ConvertError("setblacklist 的 ttl 非法: %r" % (ttl,))
    action = {
        "decision": cfg.get("decision"),
        "check_type": cfg.get("checktype"),
        "check_value": cfg.get("checkvalue"),
        "ttl": int(ttl),
    }
    cps = cfg.get("checkpoints")
    if cps:
        action["checkpoints"] = [cps] if isinstance(cps, str) else list(cps)
    return action


def wrap_and(conds):
    if not conds:
        raise ConvertError("策略没有任何判定条件")
    if len(conds) == 1:
        return conds[0]
    return {"op": "and", "conditions": conds}


def convert(doc):
    notes = []
    terms = doc.get("terms") or []

    action = None
    before, after = [], []
    delay_seconds = None

    for term in terms:
        left = term.get("left") or {}
        if left.get("type") == "func" and left.get("subtype") == "setblacklist":
            if action is not None:
                raise ConvertError("策略含多个 setblacklist 条款")
            action = conv_action(left.get("config") or {})
            continue
        if left.get("type") == "func" and left.get("subtype") == "sleep":
            if delay_seconds is not None:
                raise ConvertError("策略含多个 sleep 条款")
            cfg = left.get("config") or {}
            unit = (cfg.get("unit") or "s").lower()
            mult = {"s": 1, "m": 60, "h": 3600, "d": 86400}.get(unit)
            if mult is None:
                raise ConvertError("sleep 单位无法识别: %r" % (unit,))
            delay_seconds = int(cfg.get("duration")) * mult
            notes.append("原 sleep 条款转换为 delay,其后的条款进入 delay.condition")
            continue
        node = conv_term(term, notes)
        (after if delay_seconds is not None else before).append(node)

    if action is None:
        raise ConvertError("策略缺少 setblacklist 处置条款")

    out = {
        "app": doc.get("app", "nebula"),
        "name": doc["name"],
        "visible_name": doc.get("visible_name") or doc["name"],
        "remark": doc.get("remark", "") or "",
        "version": "2.0",
        "status": doc.get("status", "test"),
        "category": doc.get("category"),
        "tags": doc.get("tags") or [],
        "score": int(doc.get("score") or 0),
        "effective_from": doc.get("start_effect"),
        "effective_to": doc.get("end_effect"),
        "condition": wrap_and(before),
        "action": action,
        "explain": True,
    }

    if delay_seconds is not None:
        if not after:
            raise ConvertError("sleep 之后没有任何条款,delay 无意义")
        out["delay"] = {"duration_seconds": delay_seconds,
                        "condition": wrap_and(after)}

    # 触发事件:取第一个事件条款或计数器的 trigger
    trig = None
    for term in terms:
        cfg = (term.get("left") or {}).get("config") or {}
        if (term.get("left") or {}).get("type") == "event":
            trig = {"event": qualified_event(cfg.get("event"))}
            break
        t = cfg.get("trigger")
        if t:
            trig = {"event": qualified_event(t.get("event"))}
            if t.get("keys"):
                trig["keys"] = t["keys"]
            break
    if trig:
        out["trigger"] = trig

    src = {}
    if doc.get("group_id") is not None:
        src["group_id"] = doc["group_id"]
    if doc.get("is_locked") is not None:
        src["is_locked"] = bool(doc["is_locked"])
    if notes:
        src["notes"] = notes
    if src:
        out["source_1x"] = src

    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="只报告,不写文件")
    ap.add_argument("--only", help="只转换指定策略名")
    args = ap.parse_args()

    files = sorted(p for p in SRC.glob("*.json") if p.name != "index.json")
    ok, failed, skipped = 0, [], 0

    for p in files:
        doc = json.loads(p.read_text(encoding="utf-8"))
        if args.only and doc.get("name") != args.only:
            continue
        if "terms" not in doc:
            skipped += 1          # 已经是 2.0 结构
            continue
        try:
            new = convert(doc)
        except ConvertError as e:
            failed.append((doc.get("name", p.name), str(e)))
            continue
        if not args.dry_run:
            p.write_text(json.dumps(new, ensure_ascii=False, indent=2,
                                    sort_keys=True) + "\n", encoding="utf-8")
        ok += 1

    print("转换成功 %d 条,已是 2.0 结构 %d 条,失败 %d 条"
          % (ok, skipped, len(failed)))
    for name, err in failed:
        print("  ✗ %s: %s" % (name, err))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
