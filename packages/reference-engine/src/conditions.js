'use strict';
/**
 * 过滤条件算子的参考实现,对应 docs/reference/operators.md §3。
 *
 * 规格要求:**下表全部算子均有实现**。1.x 声明了完整算子集但引擎只实现了
 * 其中一小部分,用户配得出来的变量跑不了 —— 这正是 2.0 引入强制校验的动因。
 *
 * 两条贯穿性约定:
 *   - 类型严格:实际类型与声明类型不符时判定为**不通过**,不做隐式转换
 *   - null 输入判定为不通过(empty/!empty 除外,它们就是用来判空的)
 */

const REGEX_CACHE = new Map();
function compileRegex(pattern) {
  if (!REGEX_CACHE.has(pattern)) {
    // 规格:整串匹配语义
    REGEX_CACHE.set(pattern, new RegExp(`^(?:${pattern})$`));
  }
  return REGEX_CACHE.get(pattern);
}

/** 部分匹配语义的正则(1.x 的 !regex 用于判空,如 `^\s*$`,需要能匹配整串) */
function testRegex(pattern, value) {
  return compileRegex(pattern).test(value);
}

function asString(v) {
  return typeof v === 'string' ? v : null;
}
function asNumber(v) {
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

/** 地理位置查询由调用方注入,缺省实现返回 unknown(规格 §3.4) */
let locationResolver = () => 'unknown';
function setLocationResolver(fn) { locationResolver = fn; }

const CONDITIONS = {
  // ---------- 通用 ----------
  '==': (a, b) => looseEq(a, b),
  '!=': (a, b) => !looseEq(a, b),

  empty: (a) => a === null || a === undefined || a === '',
  '!empty': (a) => !(a === null || a === undefined || a === ''),

  // ---------- 数值 ----------
  '>': (a, b) => cmpNum(a, b, (x, y) => x > y),
  '>=': (a, b) => cmpNum(a, b, (x, y) => x >= y),
  '<': (a, b) => cmpNum(a, b, (x, y) => x < y),
  '<=': (a, b) => cmpNum(a, b, (x, y) => x <= y),

  // ---------- 字符串 ----------
  contains: (a, b) => strOp(a, b, (x, y) => x.includes(y)),
  '!contains': (a, b) => negate(strOp(a, b, (x, y) => x.includes(y))),
  startwith: (a, b) => strOp(a, b, (x, y) => x.startsWith(y)),
  '!startwith': (a, b) => negate(strOp(a, b, (x, y) => x.startsWith(y))),
  endwith: (a, b) => strOp(a, b, (x, y) => x.endsWith(y)),
  '!endwith': (a, b) => negate(strOp(a, b, (x, y) => x.endsWith(y))),
  regex: (a, b) => strOp(a, b, (x, y) => testRegex(y, x)),
  '!regex': (a, b) => negate(strOp(a, b, (x, y) => testRegex(y, x))),
  // containsby:字段值是给定值的子串(反向包含)
  containsby: (a, b) => strOp(a, b, (x, y) => y.includes(x)),
  '!containsby': (a, b) => negate(strOp(a, b, (x, y) => y.includes(x))),
  in: (a, b) => inSet(a, b),
  '!in': (a, b) => negate(inSet(a, b)),

  // ---------- IP 地理 ----------
  locationequals: (a, b, ctx) => {
    const loc = locationResolver(a, (ctx && ctx.level) || 'province');
    return loc === b;
  },
  '!locationequals': (a, b, ctx) => !CONDITIONS.locationequals(a, b, ctx),
  locationcontainsby: (a, b, ctx) => {
    const loc = locationResolver(a, (ctx && ctx.level) || 'province');
    return splitSet(b).includes(loc);
  },
  '!locationcontainsby': (a, b, ctx) => !CONDITIONS.locationcontainsby(a, b, ctx),
};

function looseEq(a, b) {
  if (a === null || a === undefined) return false;
  // 1.x 的常量一律以字符串存储,比较时按左值类型对齐(规格「常量值保持字符串」)
  if (typeof a === 'number') {
    const n = typeof b === 'number' ? b : Number(b);
    return Number.isFinite(n) && a === n;
  }
  if (typeof a === 'boolean') return a === (b === true || b === 'true');
  return String(a) === String(b);
}

function cmpNum(a, b, fn) {
  const x = asNumber(a);
  if (x === null) return false;                 // 类型严格
  const y = typeof b === 'number' ? b : Number(b);
  if (!Number.isFinite(y)) return false;
  return fn(x, y);
}

function strOp(a, b, fn) {
  const x = asString(a);
  if (x === null) return false;                 // 类型严格
  return fn(x, String(b));
}

function negate(v) { return !v; }

function splitSet(b) {
  if (Array.isArray(b)) return b.map(String);
  return String(b).split(',').map((s) => s.trim());
}

function inSet(a, b) {
  if (a === null || a === undefined) return false;
  return splitSet(b).includes(String(a));
}

/**
 * 求值一个过滤条件树(variable-model 的 filter 结构)。
 * 复合条件短路求值:and 遇 false 即返回,or 遇 true 即返回,not 只取第一个。
 */
function evalFilter(filter, getField) {
  if (!filter || Object.keys(filter).length === 0) return true;   // 空对象表示不过滤

  const type = filter.type || 'simple';
  if (type === 'simple') {
    const op = CONDITIONS[filter.operation];
    if (!op) {
      throw new Error(`未实现的条件算子: ${filter.operation}(规格见 docs/reference/operators.md §3)`);
    }
    return !!op(getField(filter.object), filter.value, filter);
  }
  const subs = filter.condition || [];
  if (type === 'and') {
    for (const s of subs) if (!evalFilter(s, getField)) return false;
    return true;
  }
  if (type === 'or') {
    for (const s of subs) if (evalFilter(s, getField)) return true;
    return false;
  }
  if (type === 'not') {
    return !evalFilter(subs[0], getField);
  }
  throw new Error(`未知的条件组合类型: ${type}`);
}

module.exports = { CONDITIONS, evalFilter, setLocationResolver };
