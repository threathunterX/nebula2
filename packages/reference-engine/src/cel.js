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
 *   checkNotice(keyType, keyValue, strategyName, withinSeconds) > N
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

/**
 * keyType 归一化。
 *
 * 规格最初写的取值是 `ip` / `uid` / `did` / `page` —— 那是 1.x 的词汇,而 2.0 的名单
 * 主体类型是 check_type(IP / USER / DeviceID / OrderID)。**`page` 没有对应的名单
 * 类型**,也就是说按它查永远查不到东西。
 *
 * 这里接受 check_type 本身,也接受 1.x 的三个别名。`page` 与其它未知取值一律抛错 ——
 * 静默返回 0 会让一条永远不命中的策略看起来在正常工作。
 */
const KEY_TYPES = { ip: 'IP', uid: 'USER', did: 'DeviceID', order_id: 'OrderID' };
const CHECK_TYPES = new Set(['IP', 'USER', 'DeviceID', 'OrderID']);

function normalizeKeyType(t) {
  const s = String(t);
  if (CHECK_TYPES.has(s)) return s;
  const alias = KEY_TYPES[s.toLowerCase()];
  if (alias) return alias;
  throw new Error(
    `checkNotice 的 keyType 取值非法: ${t}(可取 ${[...CHECK_TYPES].join(' / ')},`
    + `或 1.x 别名 ip / uid / did / order_id)`);
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

  /**
   * 策略级联:该主体在过去 withinSeconds 内命中 strategyName 的次数。
   *
   * 规格:1.x SPL 里唯一真正可用的业务函数($CHECKNOTICE)。
   *
   * **数的是已产出的告警,不含被去重压掉的那些。** 去重意味着「这条告警没有被报出去」,
   * 而级联判定的语义是「之前报过没有」—— 把压掉的也算进来会让下游看到一个它从未收到过
   * 的依据。这条规格里没写,是实现时定的,写在这里与 Java 端保持一致。
   *
   * 时间窗是 **[now - withinSeconds*1000, now)** —— 起点含,终点不含。终点不含是
   * 关键:同一条事件里先求值的策略可能刚产出一条告警,把它算进来会让结果依赖策略的
   * 求值顺序,而顺序不是契约。checkNotice 看到的是「在这条事件之前已经报过的」。
   */
  checkNotice(args, event, ctx) {
    const [keyType, keyValue, strategyName, withinSeconds] = args;
    const type = normalizeKeyType(keyType);
    if (!ctx || typeof ctx.countNotices !== 'function') {
      throw new Error('checkNotice 需要告警历史,当前求值上下文没有提供');
    }
    const within = Number(withinSeconds);
    if (!Number.isFinite(within) || within <= 0) {
      throw new Error(`checkNotice 的 withinSeconds 应为正整数,实际: ${withinSeconds}`);
    }
    return ctx.countNotices(type, String(keyValue ?? ''), String(strategyName),
      event.timestamp - within * 1000, event.timestamp);
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
function evalCel(expr, event, ctx) {
  const src = String(expr).trim();

  // !( ... )
  if (src.startsWith('!(') && src.endsWith(')')) {
    return !evalCel(src.slice(2, -1), event, ctx);
  }

  // <call> in [ ... ]
  const inMatch = /^(.+?)\s+in\s+(\[.*\])$/.exec(src);
  if (inMatch) {
    const left = evalTerm(inMatch[1], event, ctx);
    const list = JSON.parse(inMatch[2]);
    return list.map(String).includes(String(left));
  }

  // <call> > N / >= N / < N / <= N —— checkNotice 返回整数,需要数值比较
  const num = /^(.+?)\s*(>=|<=|>|<)\s*(-?\d+(?:\.\d+)?)$/.exec(src);
  if (num) {
    const left = Number(evalTerm(num[1], event, ctx));
    const right = Number(num[3]);
    switch (num[2]) {
      case '>': return left > right;
      case '<': return left < right;
      case '>=': return left >= right;
      default: return left <= right;
    }
  }

  // <call> == "值"  /  != "值"
  const cmp = /^(.+?)\s*(==|!=)\s*(.+)$/.exec(src);
  if (cmp) {
    const left = evalTerm(cmp[1], event, ctx);
    const right = stripQuotes(cmp[3].trim());
    return cmp[2] === '==' ? String(left) === right : String(left) !== right;
  }

  // 单独的布尔函数调用
  const v = evalTerm(src, event, ctx);
  return !!v;
}

function evalTerm(src, event, ctx) {
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
    return fn(args, event, ctx);
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
