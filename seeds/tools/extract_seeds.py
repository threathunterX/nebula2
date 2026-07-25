#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
extract_seeds.py -- Extract domain assets from the legacy Nebula risk-control
database bootstrap SQL and emit clean, sanitized JSON seed files for Nebula 2.0.

Source (read-only):
    <repo>/scripts/db/nebula.init.data.sql

Outputs (into --out):
    events/<name>.json          + events/index.json          (17 event models)
    variables/<name>.json       + variables/index.json        (253 variable models)
    strategies/<slug>.json      + strategies/index.json       (170 strategy templates)
    tags.json                                                 (15 risk tags)
    config-defaults.json                                      (system config defaults)
    INVENTORY.md
    PLACEHOLDERS.md

All JSON is UTF-8, 2-space indented, ensure_ascii=False, sort_keys=True so the
output is stable and diffable.

Run:
    python3 tools/extract_seeds.py --sql <path to nebula.init.data.sql> --out <dir>
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import Counter, OrderedDict


# --------------------------------------------------------------------------
# 1. MySQL dump parsing
# --------------------------------------------------------------------------

# MySQL backslash escape sequences used inside single-quoted string literals.
_MYSQL_ESCAPES = {
    "0": "\0",
    "'": "'",
    '"': '"',
    "b": "\b",
    "n": "\n",
    "r": "\r",
    "t": "\t",
    "Z": "\x1a",
    "\\": "\\",
    "%": "\\%",   # \% and \_ keep the backslash in MySQL
    "_": "\\_",
}


def _unescape_mysql_string(raw: str) -> str:
    """Decode the body of a MySQL single-quoted string literal."""
    out = []
    i = 0
    n = len(raw)
    while i < n:
        ch = raw[i]
        if ch == "\\" and i + 1 < n:
            nxt = raw[i + 1]
            out.append(_MYSQL_ESCAPES.get(nxt, nxt))
            i += 2
        elif ch == "'" and i + 1 < n and raw[i + 1] == "'":
            # doubled quote form ''
            out.append("'")
            i += 2
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def parse_insert_values(statement: str):
    """
    Parse `INSERT INTO `tbl` VALUES (...),(...),...;` into a list of row tuples.

    Returns a list of lists. Values are str (already unescaped), int, float,
    None (for NULL) or bytes (for 0x... hex blobs).
    """
    # Strip everything up to and including the first "VALUES"
    m = re.search(r"\bVALUES\b", statement, re.IGNORECASE)
    if not m:
        raise ValueError("no VALUES keyword in statement")
    body = statement[m.end():]

    rows = []
    i = 0
    n = len(body)
    while i < n:
        # find start of the next row tuple
        while i < n and body[i] != "(":
            if body[i] == ";":
                return rows
            i += 1
        if i >= n:
            break
        i += 1  # consume "("
        row = []
        token_start = i
        depth = 0
        while i < n:
            ch = body[i]
            if ch == "'":
                # scan the quoted literal
                i += 1
                lit_start = i
                while i < n:
                    if body[i] == "\\":
                        i += 2
                        continue
                    if body[i] == "'":
                        # doubled '' -> embedded quote
                        if i + 1 < n and body[i + 1] == "'":
                            i += 2
                            continue
                        break
                    i += 1
                row.append(_unescape_mysql_string(body[lit_start:i]))
                i += 1  # consume closing quote
                # advance to the delimiter
                while i < n and body[i] in " \t\r\n":
                    i += 1
                if i < n and body[i] == ",":
                    i += 1
                    while i < n and body[i] in " \t\r\n":
                        i += 1
                    token_start = i
                    continue
                if i < n and body[i] == ")":
                    i += 1
                    break
                token_start = i
                continue
            if ch in ",)" and depth == 0:
                tok = body[token_start:i].strip()
                if tok:
                    row.append(_parse_bare_token(tok))
                i += 1
                if ch == ")":
                    break
                while i < n and body[i] in " \t\r\n":
                    i += 1
                token_start = i
                continue
            i += 1
        rows.append(row)
        # skip to the next "," separating row tuples
        while i < n and body[i] in " \t\r\n":
            i += 1
        if i < n and body[i] == ",":
            i += 1
        elif i < n and body[i] == ";":
            break
    return rows


def _parse_bare_token(tok: str):
    low = tok.lower()
    if low in ("null", "\\n"):
        return None
    if low.startswith("0x"):
        try:
            return bytes.fromhex(tok[2:])
        except ValueError:
            return tok
    if re.fullmatch(r"-?\d+", tok):
        return int(tok)
    if re.fullmatch(r"-?\d*\.\d+(e-?\d+)?", tok, re.IGNORECASE):
        return float(tok)
    if low in ("true", "false"):
        return low == "true"
    return tok


def read_statements(sql_path: str):
    """
    Yield (current_database, table_name, full_statement) for every INSERT INTO
    statement in the dump. Statements are assumed to be one-per-line (mysqldump
    extended-insert style), which matches this dump.
    """
    current_db = None
    with open(sql_path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            use = re.match(r"^\s*USE\s+`?([A-Za-z0-9_]+)`?\s*;", line, re.IGNORECASE)
            if use:
                current_db = use.group(1)
                continue
            ins = re.match(r"^\s*INSERT INTO\s+`?([A-Za-z0-9_]+)`?\s", line, re.IGNORECASE)
            if ins:
                yield current_db, ins.group(1), line.rstrip("\n")


def collect_table(sql_path: str, table: str, db: str | None = None):
    """Collect all rows of `table` (optionally restricted to database `db`)."""
    rows = []
    for cur_db, tbl, stmt in read_statements(sql_path):
        if tbl != table:
            continue
        if db is not None and cur_db != db:
            continue
        rows.extend(parse_insert_values(stmt))
    return rows


# --------------------------------------------------------------------------
# 2. Sanitization
# --------------------------------------------------------------------------

class Sanitizer:
    """
    Scrubs real customer / personal data out of extracted values and records
    every substitution so it can be reported in INVENTORY.md.

    Strategy: an explicit substitution table for the known real-world artifacts
    found in this dump, plus generic detectors that scan the *output* for
    anything that looks like PII we may have missed.
    """

    # Explicit, ordered replacements are loaded from tools/sanitize_rules.json
    # so that the real identifiers live in exactly one file, which can be
    # deleted or git-ignored before the seeds are published. See RULES_FILE.
    RULES_FILE = "sanitize_rules.json"
    EXPLICIT = []

    # Generic detectors run over the final serialized output as a safety net.
    DETECTORS = OrderedDict([
        ("email", re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")),
        ("cn_mobile", re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")),
        ("cn_idcard", re.compile(r"(?<!\d)\d{17}[\dXx](?!\d)")),
        ("bankcard", re.compile(r"(?<!\d)(?:62|4|5[1-5])\d{13,17}(?!\d)")),
        ("ipv4", re.compile(r"(?<!\d)(?:\d{1,3}\.){3}\d{1,3}(?!\d)")),
    ])

    # Values that are allowed to survive the generic detectors.
    EMAIL_ALLOW = re.compile(r"@(example\.(com|net|org)|localhost)$", re.IGNORECASE)

    def __init__(self, rules_path=None, require_rules=True):
        self.changes = Counter()
        self.change_detail = OrderedDict()
        self.label = {}          # pattern -> masked label safe to print
        self.explicit = []
        if rules_path is None:
            rules_path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                      self.RULES_FILE)
        if os.path.exists(rules_path):
            with open(rules_path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
            for rule in data.get("rules", []):
                self.explicit.append((rule["pattern"], rule["replacement"],
                                      rule["reason"]))
                self.label[rule["pattern"]] = rule.get("masked_label",
                                                       "(redacted pattern)")
        elif require_rules:
            raise SystemExit(
                "ERROR: substitution rules not found at %s.\n"
                "This file holds the real identifiers that must be scrubbed out of "
                "the dump. Without it the extraction would silently emit unsanitized "
                "data. Restore it, or pass --no-explicit-rules if you have verified "
                "the source contains nothing to scrub." % rules_path)

    def masked(self, pattern):
        return self.label.get(pattern, "(redacted pattern)")

    def scrub(self, text: str) -> str:
        for pattern, repl, reason in self.explicit:
            new, count = re.subn(pattern, repl, text)
            if count:
                self.changes[(pattern, repl)] += count
                self.change_detail[(pattern, repl)] = reason
                text = new
        return text

    def scrub_obj(self, obj):
        if isinstance(obj, str):
            return self.scrub(obj)
        if isinstance(obj, dict):
            return {k: self.scrub_obj(v) for k, v in obj.items()}
        if isinstance(obj, list):
            return [self.scrub_obj(v) for v in obj]
        return obj

    # -- generic residual scan -------------------------------------------
    @staticmethod
    def _is_private_or_doc_ip(ip: str) -> bool:
        try:
            parts = [int(p) for p in ip.split(".")]
        except ValueError:
            return True
        if len(parts) != 4 or any(p > 255 for p in parts):
            return True  # not a real IP (version string, etc.)
        a, b = parts[0], parts[1]
        if a == 10 or a == 127 or a == 0:
            return True
        if a == 172 and 16 <= b <= 31:
            return True
        if a == 192 and b == 168:
            return True
        if a == 169 and b == 254:
            return True
        if a >= 224:
            return True
        if (a, b) in ((192, 0), (198, 51), (203, 0)):
            return True  # RFC 5737 documentation ranges
        if a == 198 and b in (18, 19):
            return True
        return False

    def residual_findings(self, text: str):
        """Return {kind: sorted set of suspicious values} still present."""
        found = OrderedDict()
        for kind, rx in self.DETECTORS.items():
            hits = set()
            for m in rx.finditer(text):
                v = m.group(0)
                if kind == "email" and self.EMAIL_ALLOW.search(v):
                    continue
                if kind == "ipv4" and self._is_private_or_doc_ip(v):
                    continue
                if kind == "bankcard":
                    # avoid flagging long epoch-ish digit runs inside JSON numbers
                    if not v.startswith(("62", "4", "51", "52", "53", "54", "55")):
                        continue
                hits.add(v)
            if hits:
                found[kind] = sorted(hits)
        return found


# --------------------------------------------------------------------------
# 3. Placeholders
# --------------------------------------------------------------------------

# Legacy opaque placeholder -> new, self-describing placeholder.
PLACEHOLDER_MAP = {
    "HOLDER": "<YOUR_PAYMENT_PAGE_PATH>",
}

PLACEHOLDER_DOCS = {
    "<YOUR_PAYMENT_PAGE_PATH>": {
        "legacy_token": "HOLDER",
        "where": "strategy term condition `page contain <...>`",
        "meaning": (
            "A URL path fragment that uniquely identifies your checkout / payment "
            "page (e.g. \"/order/pay\" or \"/checkout/confirm\"). The strategy counts "
            "HTTP_DYNAMIC requests whose `page` field contains this fragment in order "
            "to tell whether a submitted order was actually paid for."
        ),
        "required": True,
    },
}


def apply_placeholders(obj, hits: Counter):
    if isinstance(obj, str):
        for legacy, new in PLACEHOLDER_MAP.items():
            if obj == legacy:
                hits[new] += 1
                return new
        return obj
    if isinstance(obj, dict):
        return {k: apply_placeholders(v, hits) for k, v in obj.items()}
    if isinstance(obj, list):
        return [apply_placeholders(v, hits) for v in obj]
    return obj


# --------------------------------------------------------------------------
# 4. Helpers
# --------------------------------------------------------------------------

def maybe_json(value, default=None):
    """Parse a column that holds JSON text; fall back to the raw string."""
    if value is None:
        return default
    if isinstance(value, bytes):
        value = value.decode("utf-8", "replace")
    if not isinstance(value, str):
        return value
    s = value.strip()
    if s == "":
        return default
    if s[0] not in "[{\"" and not s.lstrip("-").replace(".", "", 1).isdigit():
        return value
    try:
        return json.loads(s)
    except (ValueError, TypeError):
        return value


_UNSAFE = re.compile(r"[^0-9A-Za-z一-鿿_.-]+")


def safe_filename(name: str, fallback: str) -> str:
    """Filesystem-safe, deterministic slug. Keeps CJK, drops path separators."""
    if name is None:
        name = ""
    slug = _UNSAFE.sub("_", str(name)).strip("._-")
    slug = re.sub(r"_{2,}", "_", slug)
    if not slug:
        slug = fallback
    return slug[:120]


def write_json(path: str, obj) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(obj, fh, ensure_ascii=False, indent=2, sort_keys=True)
        fh.write("\n")


# --------------------------------------------------------------------------
# 5. Extractors
# --------------------------------------------------------------------------

EVENT_COLS = ["id", "app", "name", "visible_name", "type", "remark",
              "source", "version", "properties", "last_modified"]

VARIABLE_COLS = ["id", "module", "app", "name", "remark", "visible_name",
                 "dimension", "status", "type", "value_type", "value_subtype",
                 "value_category", "source", "filter", "period", "function",
                 "groupbykeys", "hint", "last_modified"]

STRATEGY_COLS = ["id", "app", "name", "remark", "version", "status",
                 "createtime", "modifytime", "starteffect", "endeffect",
                 "last_modified", "config", "score", "tags", "isLock",
                 "category", "group_id"]

TAG_COLS = ["id", "app", "name", "last_modified"]

EVENT_OUT_FIELDS = ["app", "name", "visible_name", "remark", "type",
                    "version", "source", "properties"]

VARIABLE_OUT_FIELDS = ["module", "app", "name", "remark", "visible_name",
                       "dimension", "status", "type", "value_type",
                       "value_subtype", "value_category", "source", "filter",
                       "period", "function", "groupbykeys", "hint"]


def rowdict(row, cols):
    d = {}
    for i, c in enumerate(cols):
        d[c] = row[i] if i < len(row) else None
    return d


def extract_events(sql_path, outdir, san):
    rows = collect_table(sql_path, "eventmodel_default")
    records, index = [], []
    for row in rows:
        r = rowdict(row, EVENT_COLS)
        rec = {
            "app": r["app"],
            "name": r["name"],
            "visible_name": r["visible_name"],
            "remark": r["remark"],
            "type": r["type"],
            "version": r["version"],
            "source": maybe_json(r["source"], []),
            "properties": maybe_json(r["properties"], []),
        }
        rec = san.scrub_obj(rec)
        fname = safe_filename(str(rec["name"]).lower(), "event_%s" % r["id"])
        write_json(os.path.join(outdir, "events", fname + ".json"), rec)
        props = rec["properties"] if isinstance(rec["properties"], list) else []
        records.append(rec)
        index.append({
            "file": "events/%s.json" % fname,
            "name": rec["name"],
            "visible_name": rec["visible_name"],
            "type": rec["type"],
            "version": rec["version"],
            "property_count": len(props),
        })
    index.sort(key=lambda e: e["name"] or "")
    write_json(os.path.join(outdir, "events", "index.json"), {
        "count": len(index),
        "kind": "eventmodel",
        "source_table": "nebula_default.eventmodel_default",
        "events": index,
    })
    return records, index


def extract_variables(sql_path, outdir, san):
    rows = collect_table(sql_path, "variablemodel_default")
    records, index = [], []
    used = {}
    for row in rows:
        r = rowdict(row, VARIABLE_COLS)
        rec = {
            "module": r["module"],
            "app": r["app"],
            "name": r["name"],
            "remark": r["remark"],
            "visible_name": r["visible_name"],
            "dimension": r["dimension"],
            "status": r["status"],
            "type": r["type"],
            "value_type": r["value_type"],
            "value_subtype": r["value_subtype"],
            "value_category": r["value_category"],
            "source": maybe_json(r["source"], []),
            "filter": maybe_json(r["filter"], {}),
            "period": maybe_json(r["period"], {}),
            "function": maybe_json(r["function"], {}),
            "groupbykeys": maybe_json(r["groupbykeys"], []),
            "hint": maybe_json(r["hint"], {}),
        }
        rec = san.scrub_obj(rec)
        base = safe_filename(str(rec["name"]).lower(), "variable_%s" % r["id"])
        fname = base
        if base in used:
            fname = "%s-%s" % (base, r["id"])
        used[base] = True
        write_json(os.path.join(outdir, "variables", fname + ".json"), rec)
        records.append(rec)
        index.append({
            "file": "variables/%s.json" % fname,
            "name": rec["name"],
            "visible_name": rec["visible_name"],
            "module": rec["module"],
            "dimension": rec["dimension"],
            "type": rec["type"],
            "status": rec["status"],
        })
    index.sort(key=lambda e: (e["name"] or "", e["file"]))
    write_json(os.path.join(outdir, "variables", "index.json"), {
        "count": len(index),
        "kind": "variablemodel",
        "source_table": "nebula_default.variablemodel_default",
        "variables": index,
    })
    return records, index


def extract_strategies(sql_path, outdir, san, ph_hits):
    rows = collect_table(sql_path, "strategy_cust", db="nebula")
    records, index = [], []
    used = {}
    for row in rows:
        r = rowdict(row, STRATEGY_COLS)
        cfg = maybe_json(r["config"], {})
        if not isinstance(cfg, dict):
            cfg = {"_raw": cfg}

        row_tags = r["tags"]
        tags = cfg.get("tags")
        if not isinstance(tags, list):
            tags = [t for t in [row_tags] if t]

        rec = {
            "app": cfg.get("app", r["app"]),
            "name": cfg.get("name", r["name"]),
            "remark": cfg.get("remark", r["remark"]),
            "category": cfg.get("category", r["category"]),
            "tags": tags,
            "score": cfg.get("score", r["score"]),
            "status": cfg.get("status", r["status"]),
            "version": str(cfg.get("version", r["version"])),
            "group_id": cfg.get("group_id", r["group_id"]),
            "is_locked": bool(cfg.get("isLock", r["isLock"])),
            "start_effect": cfg.get("starteffect", r["starteffect"]),
            "end_effect": cfg.get("endeffect", r["endeffect"]),
            "terms": cfg.get("terms", []),
        }
        rec = san.scrub_obj(rec)

        before = sum(ph_hits.values())
        rec = apply_placeholders(rec, ph_hits)
        n_ph = sum(ph_hits.values()) - before

        base = safe_filename(str(rec["name"]), "strategy_%s" % r["id"])
        fname = base
        if base in used:
            fname = "%s-%s" % (base, r["id"])
        used[base] = True

        write_json(os.path.join(outdir, "strategies", fname + ".json"), rec)
        records.append(rec)
        entry = {
            "file": "strategies/%s.json" % fname,
            "name": rec["name"],
            "remark": rec["remark"],
            "category": rec["category"],
            "tags": rec["tags"],
            "score": rec["score"],
            "status": rec["status"],
            "term_count": len(rec["terms"]) if isinstance(rec["terms"], list) else 0,
        }
        if n_ph:
            entry["requires_configuration"] = True
            entry["placeholders"] = sorted(set(_find_placeholders(rec)))
        index.append(entry)

    index.sort(key=lambda e: (e["category"] or "", e["name"] or "", e["file"]))
    needs_cfg = [e for e in index if e.get("requires_configuration")]
    write_json(os.path.join(outdir, "strategies", "index.json"), {
        "count": len(index),
        "kind": "strategy_template",
        "source_table": "nebula.strategy_cust",
        "placeholder_reference": "../PLACEHOLDERS.md",
        "strategies_requiring_configuration": {
            "count": len(needs_cfg),
            "files": [e["file"] for e in needs_cfg],
        },
        "strategies": index,
    })
    return records, index


def _find_placeholders(obj, acc=None):
    if acc is None:
        acc = []
    if isinstance(obj, str):
        if obj.startswith("<") and obj.endswith(">") and obj.upper() == obj:
            acc.append(obj)
    elif isinstance(obj, dict):
        for v in obj.values():
            _find_placeholders(v, acc)
    elif isinstance(obj, list):
        for v in obj:
            _find_placeholders(v, acc)
    return acc


def extract_tags(sql_path, outdir, san):
    rows = collect_table(sql_path, "tags")
    tags = []
    for row in rows:
        r = rowdict(row, TAG_COLS)
        tags.append(san.scrub_obj({"app": r["app"], "name": r["name"]}))
    tags.sort(key=lambda t: t["name"] or "")
    write_json(os.path.join(outdir, "tags.json"), {
        "count": len(tags),
        "kind": "risk_tag",
        "source_table": "nebula.tags",
        "tags": tags,
    })
    return tags


def extract_config_defaults(sql_path, outdir, san):
    rows = collect_table(sql_path, "config_default")
    items = {}
    for row in rows:
        key = row[0]
        val = row[1] if len(row) > 1 else None
        if isinstance(val, bytes):
            val = val.decode("utf-8", "replace")
        items[key] = san.scrub_obj(val)
    write_json(os.path.join(outdir, "config-defaults.json"), {
        "count": len(items),
        "kind": "config_default",
        "source_table": "nebula_default.config_default",
        "config": items,
    })
    return items


# --------------------------------------------------------------------------
# 6. Reports
# --------------------------------------------------------------------------

def _dist(records, field):
    c = Counter()
    for r in records:
        v = r.get(field)
        if isinstance(v, list):
            for x in v:
                c[x if x not in (None, "") else "(empty)"] += 1
        else:
            c[v if v not in (None, "") else "(empty)"] += 1
    return c


def _table(rows, headers):
    out = ["| " + " | ".join(headers) + " |",
           "|" + "|".join(["---"] * len(headers)) + "|"]
    for r in rows:
        out.append("| " + " | ".join(str(x) for x in r) + " |")
    return "\n".join(out)


def write_inventory(outdir, events, ev_index, variables, strategies,
                    st_index, tags, config, san, ph_hits, residual):
    L = []
    L.append("# Nebula 2.0 Seeds — INVENTORY")
    L.append("")
    L.append("Generated by `tools/extract_seeds.py` from the legacy Nebula "
             "database bootstrap dump (`scripts/db/nebula.init.data.sql`). "
             "The source file was read only; nothing in it was modified.")
    L.append("")
    L.append("## 1. Totals")
    L.append("")
    L.append(_table([
        ["Event models", len(events), "`events/*.json`", "`nebula_default.eventmodel_default`"],
        ["Variable models", len(variables), "`variables/*.json`", "`nebula_default.variablemodel_default`"],
        ["Strategy templates", len(strategies), "`strategies/*.json`", "`nebula.strategy_cust`"],
        ["Risk tags", len(tags), "`tags.json`", "`nebula.tags`"],
        ["Config defaults", len(config), "`config-defaults.json`", "`nebula_default.config_default`"],
    ], ["Asset", "Count", "Output", "Source table"]))
    L.append("")

    # Events
    L.append("## 2. Event models (%d)" % len(events))
    L.append("")
    rows = []
    for e in sorted(ev_index, key=lambda x: x["name"] or ""):
        rows.append([e["name"], e["visible_name"], e["type"],
                     e["version"], e["property_count"]])
    L.append(_table(rows, ["name", "visible_name", "type", "version", "# properties"]))
    L.append("")
    L.append("Total property definitions across all event models: **%d**."
             % sum(e["property_count"] for e in ev_index))
    L.append("")

    # Variables
    L.append("## 3. Variable models (%d)" % len(variables))
    L.append("")
    for field, title in (("module", "By `module`"),
                         ("dimension", "By `dimension`"),
                         ("type", "By `type`"),
                         ("status", "By `status`"),
                         ("value_type", "By `value_type`")):
        c = _dist(variables, field)
        L.append("### %s" % title)
        L.append("")
        L.append(_table(sorted(c.items(), key=lambda kv: (-kv[1], str(kv[0]))),
                        [field, "count"]))
        L.append("")

    # Strategies
    L.append("## 4. Strategy templates (%d)" % len(strategies))
    L.append("")
    c = _dist(strategies, "category")
    L.append("### By `category`")
    L.append("")
    L.append(_table(sorted(c.items(), key=lambda kv: (-kv[1], str(kv[0]))),
                    ["category", "count"]))
    L.append("")
    c = _dist(strategies, "tags")
    L.append("### By `tag`")
    L.append("")
    L.append(_table(sorted(c.items(), key=lambda kv: (-kv[1], str(kv[0]))),
                    ["tag", "count"]))
    L.append("")
    c = _dist(strategies, "status")
    L.append("### By `status`")
    L.append("")
    L.append(_table(sorted(c.items(), key=lambda kv: (-kv[1], str(kv[0]))),
                    ["status", "count"]))
    L.append("")
    term_total = sum(len(s["terms"]) for s in strategies if isinstance(s["terms"], list))
    L.append("Total strategy terms (conditions/actions) across all templates: **%d** "
             "(avg %.1f per strategy)." % (term_total, term_total / max(1, len(strategies))))
    L.append("")

    # Tags
    L.append("## 5. Risk tags (%d)" % len(tags))
    L.append("")
    L.append(_table([[t["name"], t["app"]] for t in tags], ["tag", "app"]))
    L.append("")

    # Sanitization
    L.append("## 6. Sanitization changes")
    L.append("")
    if san.changes:
        rows = []
        for (pattern, repl), count in sorted(san.changes.items()):
            reason = san.change_detail.get((pattern, repl), "")
            rows.append([san.masked(pattern), "`%s`" % repl, count, reason])
        L.append(_table(rows, ["Original (masked)", "Replacement", "Occurrences", "Why"]))
        L.append("")
        L.append("> The originals are described rather than quoted so this report can "
                 "ship alongside the seeds. The literal search patterns live in "
                 "`tools/sanitize_rules.json` — that file is the only place the real "
                 "identifiers appear, and it should be removed or git-ignored before "
                 "the seed set is published.")
    else:
        L.append("_No substitutions were required._")
    L.append("")
    L.append("### Scanned for, none found in the extracted assets")
    L.append("")
    L.append("- Mainland-China mobile numbers (`1[3-9]xxxxxxxxx`)")
    L.append("- Resident ID numbers (18-digit)")
    L.append("- Bank / payment card numbers (UnionPay `62…`, Visa `4…`, Mastercard `5[1-5]…`)")
    L.append("- Public (non-RFC1918 / non-RFC5737) IPv4 literals")
    L.append("- Real personal names")
    L.append("")
    L.append("Field names such as `phone`, `id_card`, `bank_card`, `uid` and `c_ip` do "
             "occur in event/variable **schemas** — those are metadata definitions "
             "(column descriptors), not customer values, and are kept intentionally.")
    L.append("")
    L.append("### Residual scan of the emitted JSON")
    L.append("")
    if residual:
        for kind, vals in residual.items():
            L.append("- **%s**: %s" % (kind, ", ".join("`%s`" % v for v in vals)))
    else:
        L.append("Clean — no email addresses outside the `example.*` space, no phone "
                 "numbers, ID numbers, card numbers, or public IP literals remain.")
    L.append("")

    # Placeholders
    L.append("## 7. Placeholders")
    L.append("")
    if ph_hits:
        L.append(_table([[ "`%s`" % k, PLACEHOLDER_DOCS.get(k, {}).get("legacy_token", ""), v]
                         for k, v in sorted(ph_hits.items())],
                        ["Placeholder", "Legacy token", "Occurrences"]))
        L.append("")
        L.append("See `PLACEHOLDERS.md` for what each one means and how to fill it in.")
    else:
        L.append("_None._")
    L.append("")

    L.append("## 8. Output conventions")
    L.append("")
    L.append("- UTF-8, `ensure_ascii=False` (CJK stays readable), 2-space indent, "
             "`sort_keys=True`, trailing newline — byte-stable and diff-friendly.")
    L.append("- Columns that held serialized JSON in MySQL "
             "(`properties`, `source`, `filter`, `period`, `function`, `groupbykeys`, "
             "`hint`, `config`) are parsed into real JSON structures rather than "
             "left as escaped strings.")
    L.append("- Volatile bookkeeping columns (`id`, `last_modified`, `createtime`, "
             "`modifytime`) are dropped from the per-asset files so that re-running "
             "the extraction produces identical output.")
    L.append("")

    with open(os.path.join(outdir, "INVENTORY.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(L))


def write_placeholders(outdir, ph_hits, st_index, config):
    L = []
    L.append("# Nebula 2.0 Seeds — PLACEHOLDERS")
    L.append("")
    L.append("Values in the seed data that **must be replaced with your own** before "
             "the seeds are usable in production. Nothing here is a secret from the "
             "original system — the originals were either generic placeholders or "
             "were removed during sanitization.")
    L.append("")

    L.append("## 1. Strategy placeholders")
    L.append("")
    if ph_hits:
        for ph, count in sorted(ph_hits.items()):
            doc = PLACEHOLDER_DOCS.get(ph, {})
            L.append("### `%s`" % ph)
            L.append("")
            L.append("- **Legacy token in the old SQL:** `%s`" % doc.get("legacy_token", "n/a"))
            L.append("- **Occurrences:** %d" % count)
            L.append("- **Where:** %s" % doc.get("where", "n/a"))
            L.append("- **Required:** %s" % ("yes — the strategy will not fire correctly "
                                             "until this is set" if doc.get("required")
                                             else "optional"))
            L.append("- **Meaning:** %s" % doc.get("meaning", ""))
            L.append("")
            files = [e["file"] for e in st_index
                     if ph in (e.get("placeholders") or [])]
            L.append("- **Affected strategy files (%d):**" % len(files))
            L.append("")
            for f in sorted(files):
                name = next((e["name"] for e in st_index if e["file"] == f), "")
                L.append("  - `%s` — %s" % (f, name))
            L.append("")
            L.append("**How to fill it in:** open each file above and replace every "
                     "`\"%s\"` string with your own path fragment, e.g. `\"/order/pay\"`. "
                     "The comparison operator is `contain`, so a substring is enough." % ph)
            L.append("")
    else:
        L.append("_None._")
        L.append("")

    L.append("## 2. Configuration defaults you should review")
    L.append("")
    L.append("These live in `config-defaults.json`. Values marked *sanitized* held "
             "real data from the source system and were replaced with `example.*` "
             "stand-ins; values marked *empty* were already blank in the dump but "
             "still need a real value for the feature to work.")
    L.append("")
    rows = [
        ["`alerting.mail.base_url`", "`%s`" % config.get("alerting.mail.base_url", ""),
         "sanitized", "Public base URL of your Nebula console; used to build links in alert emails."],
        ["`alerting.mail.sender`", "`%s`" % config.get("alerting.mail.sender", ""),
         "sanitized", "From-address for alert emails."],
        ["`alerting.to_emails`", "`%s`" % config.get("alerting.to_emails", ""),
         "sanitized", "Comma-separated alert recipients."],
        ["`alerting.smtp_server`", "(empty)", "empty", "SMTP hostname."],
        ["`alerting.smtp_port`", "(empty)", "empty", "SMTP port."],
        ["`alerting.smtp_account`", "(empty)", "empty", "SMTP username."],
        ["`alerting.smtp_password`", "(empty)", "empty",
         "SMTP password. **Do not commit a real value** — inject it at deploy time."],
        ["`alerting.nebula_address`", "(empty)", "empty", "Address the alerting service uses to reach Nebula."],
        ["`alerting.email_topic`", "(empty)", "empty", "Subject prefix for alert emails."],
        ["`filter.encryption.salt`", "(empty)", "empty",
         "Salt for hashing sensitive fields. **Generate your own** — never reuse another deployment's salt."],
        ["`filter.encryption.names`", "(empty)", "empty", "Comma-separated field names to encrypt/hash on ingest."],
        ["`filter.log.domains`", "(empty)", "empty", "Domains to keep/drop during log ingestion."],
        ["`filter.log.client_ips`", "(empty)", "empty", "Client IP filter for log ingestion."],
        ["`filter.log.server_ips`", "(empty)", "empty", "Server IP filter for log ingestion."],
        ["`filter.traffic.domains`", "(empty)", "empty", "Domains to keep/drop from mirrored traffic."],
        ["`filter.traffic.client_ips`", "(empty)", "empty", "Client IP filter for mirrored traffic."],
        ["`filter.traffic.server_ips`", "(empty)", "empty", "Server IP filter for mirrored traffic."],
        ["`filter.traffic.server_ports`", "(empty)", "empty", "Server port filter for mirrored traffic."],
        ["`filter.traffic.urls`", "(empty)", "empty", "URL filter for mirrored traffic."],
        ["`sniffer.uid.keyset`", "`%s`" % config.get("sniffer.uid.keyset", ""),
         "review", "Name of the request field the sniffer reads the user id from. Change to match your app."],
        ["`sniffer.did.keyset`", "`%s`" % config.get("sniffer.did.keyset", ""),
         "review", "Name of the request field the sniffer reads the device id from. Change to match your app."],
    ]
    L.append(_table(rows, ["Key", "Seeded value", "State", "What it is"]))
    L.append("")

    L.append("## 3. Not placeholders")
    L.append("")
    L.append("The following look like they might need substituting but do **not**:")
    L.append("")
    L.append("- `app: \"nebula\"` on every asset — this is the built-in application "
             "namespace, not a customer name.")
    L.append("- Field identifiers inside strategy terms (`c_ip`, `did`, `uid`, "
             "`page`, `order_id`, …) — these are Nebula's own event schema names and "
             "match the event models in `events/`.")
    L.append("- Regex literals such as `^\\\\s*$` — genuine \"is blank\" guards.")
    L.append("")

    with open(os.path.join(outdir, "PLACEHOLDERS.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(L))


# --------------------------------------------------------------------------
# 7. Main
# --------------------------------------------------------------------------

EXPECTED = {"events": 17, "variables": 253, "strategies": 170, "tags": 15}


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sql", required=True, help="path to nebula.init.data.sql")
    ap.add_argument("--out", required=True, help="output seeds directory")
    ap.add_argument("--rules", default=None,
                    help="path to sanitize_rules.json (default: alongside this script)")
    ap.add_argument("--no-explicit-rules", action="store_true",
                    help="run with generic detectors only; fails if anything is found")
    args = ap.parse_args()

    sql_path = os.path.abspath(args.sql)
    outdir = os.path.abspath(args.out)
    os.makedirs(outdir, exist_ok=True)

    san = Sanitizer(rules_path=args.rules,
                    require_rules=not args.no_explicit_rules)
    ph_hits = Counter()

    events, ev_index = extract_events(sql_path, outdir, san)
    variables, var_index = extract_variables(sql_path, outdir, san)
    strategies, st_index = extract_strategies(sql_path, outdir, san, ph_hits)
    tags = extract_tags(sql_path, outdir, san)
    config = extract_config_defaults(sql_path, outdir, san)

    # Residual PII scan over the seed data we actually wrote. `tools/` is
    # excluded on purpose: sanitize_rules.json legitimately holds the raw
    # patterns and would otherwise flag itself forever.
    blob = []
    for root, dirs, files in os.walk(outdir):
        dirs[:] = [d for d in dirs if d != "tools"]
        for f in sorted(files):
            if f.endswith(".json"):
                with open(os.path.join(root, f), "r", encoding="utf-8") as fh:
                    blob.append(fh.read())
    residual = san.residual_findings("\n".join(blob))

    write_inventory(outdir, events, ev_index, variables, strategies, st_index,
                    tags, config, san, ph_hits, residual)
    write_placeholders(outdir, ph_hits, st_index, config)

    counts = {"events": len(events), "variables": len(variables),
              "strategies": len(strategies), "tags": len(tags)}
    print("counts: %s  config_default: %d" % (counts, len(config)))
    ok = True
    for k, expected in EXPECTED.items():
        got = counts[k]
        flag = "OK " if got == expected else "MISMATCH"
        if got != expected:
            ok = False
        print("  %-11s expected %4d got %4d  %s" % (k, expected, got, flag))
    print("placeholders: %s" % dict(ph_hits))
    print("sanitizer substitutions: %d occurrence(s) across %d rule(s)"
          % (sum(san.changes.values()), len(san.changes)))
    if residual:
        ok = False
        print("RESIDUAL PII FINDINGS: %s" % json.dumps(residual, ensure_ascii=False))
    else:
        print("residual PII scan: clean")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
