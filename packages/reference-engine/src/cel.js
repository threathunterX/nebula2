'use strict';
/**
 * CEL 表达式求值 —— 参考实现只覆盖内置资产实际用到的子集。
 *
 * 真正的引擎应使用标准 CEL 实现(cel-java),本文件的作用是让参考引擎
 * 能跑通那 3 条使用 CEL 的策略,并验证 packages/cel-functions/ 中定义的
 * 函数语义(尤其是边界行为)是否可实现、是否有歧义。
 *
 * 支持的子集:
 *   inTimeWindow("HH:MM", "HH:MM")
 *   ipLocation(field, "level") == "值"  /  != /  in [...]
 *   !( ... )
 */

const DEFAULT_TZ_OFFSET_MIN = 8 * 60;    // Asia/Shanghai,对应 nebula.timezone 默认值

let locationResolver = () => 'unknown';  // 规格:查询失败返回 "unknown"
function setLocationResolver(fn) { locationResolver = fn; }

let tzOffsetMin = DEFAULT_TZ_OFFSET_MIN;
function setTimezoneOffsetMinutes(min) { tzOffsetMin = min; }

/** 事件时间在部署时区下的「当日分钟数」 */
function minutesOfDay(tsMs) {
  const local = tsMs + tzOffsetMin * 60 * 1000;
  const dayMs = ((local % 86400000) + 86400000) % 86400000;
  return Math.floor(dayMs / 60000);
}

function parseHHMM(s) {
  const m = /^(\d{1,2}):(\d{2})$/.exec(String(s).trim());
  if (!m) throw new Error(`inTimeWindow 的时刻格式应为 HH:MM,实际: ${s}`);
  return parseInt(m[1], 10) * 60 + parseInt(m[2], 10);
}

const FUNCTIONS = {
  /** 规格:start 含、end 不含;start > end 表示跨零点;按部署时区判断 */
  inTimeWindow(args, event) {
    const start = parseHHMM(args[0]);
    const end = parseHHMM(args[1]);
    const now = minutesOfDay(event.timestamp);
    if (start <= end) return now >= start && now < end;
    return now >= start || now < end;          // 跨零点
  },

  /** 规格:查询失败或无结果返回 "unknown",不抛异常 */
  ipLocation(args) {
    const ip = args[0];
    const level = args[1];
    if (ip === null || ip === undefined || ip === '') return 'unknown';
    try {
      const r = locationResolver(ip, level);
      return r === null || r === undefined || r === '' ? 'unknown' : r;
    } catch (_) {
      return 'unknown';
    }
  },
};

/** 极简求值器:够用即可,不追求完整 CEL 语法 */
function evalCel(expr, event) {
  const src = String(expr).trim();

  // !( ... )
  if (src.startsWith('!(') && src.endsWith(')')) {
    return !evalCel(src.slice(2, -1), event);
  }

  // <call> in [ ... ]
  const inMatch = /^(.+?)\s+in\s+(\[.*\])$/.exec(src);
  if (inMatch) {
    const left = evalTerm(inMatch[1], event);
    const list = JSON.parse(inMatch[2]);
    return list.map(String).includes(String(left));
  }

  // <call> == "值"  /  != "值"
  const cmp = /^(.+?)\s*(==|!=)\s*(.+)$/.exec(src);
  if (cmp) {
    const left = evalTerm(cmp[1], event);
    const right = stripQuotes(cmp[3].trim());
    return cmp[2] === '==' ? String(left) === right : String(left) !== right;
  }

  // 单独的布尔函数调用
  const v = evalTerm(src, event);
  return !!v;
}

function evalTerm(src, event) {
  const s = src.trim();
  const call = /^([A-Za-z_][A-Za-z0-9_]*)\((.*)\)$/.exec(s);
  if (call) {
    const name = call[1];
    const fn = FUNCTIONS[name];
    if (!fn) {
      throw new Error(`未实现的 CEL 函数: ${name}(定义见 packages/cel-functions/README.md)`);
    }
    const args = splitArgs(call[2]).map((a) => {
      const t = a.trim();
      if (/^".*"$/.test(t) || /^'.*'$/.test(t)) return stripQuotes(t);
      if (/^-?\d+(\.\d+)?$/.test(t)) return Number(t);
      return event[t];                    // 裸标识符 = 事件字段
    });
    return fn(args, event);
  }
  if (/^".*"$/.test(s) || /^'.*'$/.test(s)) return stripQuotes(s);
  return event[s];
}

function splitArgs(s) {
  const out = []; let depth = 0; let cur = ''; let quote = null;
  for (const ch of s) {
    if (quote) { cur += ch; if (ch === quote) quote = null; continue; }
    if (ch === '"' || ch === "'") { quote = ch; cur += ch; continue; }
    if (ch === '(' || ch === '[') depth++;
    if (ch === ')' || ch === ']') depth--;
    if (ch === ',' && depth === 0) { out.push(cur); cur = ''; continue; }
    cur += ch;
  }
  if (cur.trim() !== '') out.push(cur);
  return out;
}

function stripQuotes(s) {
  const t = String(s).trim();
  if ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith("'") && t.endsWith("'"))) {
    return t.slice(1, -1);
  }
  return t;
}

module.exports = { evalCel, setLocationResolver, setTimezoneOffsetMinutes, minutesOfDay };
