'use strict';
/**
 * 聚合算子的参考实现。
 *
 * 本文件是 docs/reference/operators.md 的可执行版本。规格与实现不一致时,
 * 以规格为准并修正此处;若发现规格本身有歧义或漏洞,先修规格再改这里。
 *
 * 关键约定(全部来自规格):
 *   - null 输入一律跳过,不当作 0 或空串
 *   - 空窗口的返回值逐算子规定,见每个算子的 empty()
 *   - first/last 依据**事件时间**,不是到达顺序
 *   - variance/stddev 是**样本**统计量,分母 n-1
 *   - stddev = sqrt(variance)(1.x 的 stddev 实际返回方差,这是已知的语义变更)
 */

const { HyperLogLog } = require('./hll');

/** 每个算子:init() 建状态;add(state, value, meta) 累加;value(state) 取当前累计值 */
const OPERATORS = {
  // ---------- 计数类 ----------
  count: {
    outputType: () => 'long',
    init: () => ({ n: 0 }),
    add: (s) => { s.n += 1; },
    value: (s) => s.n,
  },

  group_count: {
    outputType: () => 'map<long>',
    init: () => ({ m: new Map() }),
    // param 指定分组字段,值由调用方从事件中取出并作为 groupValue 传入
    add: (s, _v, meta) => {
      const k = meta && meta.groupValue;
      if (k === null || k === undefined) return;
      s.m.set(k, (s.m.get(k) || 0) + 1);
    },
    value: (s) => Object.fromEntries([...s.m.entries()].sort(cmpKey)),
  },

  // ---------- 数值类 ----------
  sum: {
    outputType: (t) => t,
    init: () => ({ v: 0, seen: false }),
    add: (s, v) => { s.v += v; s.seen = true; },
    value: (s) => s.v,          // 规格:空窗口返回 0
  },

  group_sum: {
    outputType: (t) => `map<${t}>`,
    init: () => ({ m: new Map() }),
    add: (s, v, meta) => {
      const k = meta && meta.groupValue;
      if (k === null || k === undefined) return;
      s.m.set(k, (s.m.get(k) || 0) + v);
    },
    value: (s) => Object.fromEntries([...s.m.entries()].sort(cmpKey)),
  },

  max: {
    outputType: (t) => t,
    init: () => ({ v: null }),
    add: (s, v) => { if (s.v === null || v > s.v) s.v = v; },
    value: (s) => s.v,          // 规格:空窗口返回 null
  },

  min: {
    outputType: (t) => t,
    init: () => ({ v: null }),
    add: (s, v) => { if (s.v === null || v < s.v) s.v = v; },
    value: (s) => s.v,
  },

  avg: {
    outputType: () => 'double',
    init: () => ({ n: 0, sum: 0 }),
    add: (s, v) => { s.n += 1; s.sum += v; },
    value: (s) => (s.n === 0 ? null : s.sum / s.n),
  },

  variance: {
    outputType: () => 'double',
    init: () => ({ n: 0, sum: 0, sq: 0 }),
    add: (s, v) => { s.n += 1; s.sum += v; s.sq += v * v; },
    value: (s) => sampleVariance(s),
  },

  stddev: {
    outputType: () => 'double',
    init: () => ({ n: 0, sum: 0, sq: 0 }),
    add: (s, v) => { s.n += 1; s.sum += v; s.sq += v * v; },
    // 与 1.x 的差异:1.x 的 stddev 返回方差(未开方)
    value: (s) => Math.sqrt(sampleVariance(s)),
  },

  cv: {
    outputType: () => 'double',
    init: () => ({ n: 0, sum: 0, sq: 0 }),
    add: (s, v) => { s.n += 1; s.sum += v; s.sq += v * v; },
    value: (s) => {
      if (s.n <= 1) return null;
      const mean = s.sum / s.n;
      if (mean === 0) return null;
      return Math.sqrt(sampleVariance(s)) / mean;
    },
  },

  // ---------- 去重计数 ----------
  distinct_count: {
    outputType: () => 'long',
    // mode: 'exact'(默认)| 'approx';exact 超过阈值自动降级并标记
    init: (cfg = {}) => ({
      mode: cfg.distinct_mode || 'exact',
      threshold: cfg.approx_threshold === undefined ? 100000 : cfg.approx_threshold,
      set: new Set(),
      hll: null,
      degraded: false,
    }),
    add: (s, v) => {
      const k = String(v);
      if (s.hll) { s.hll.add(k); return; }
      s.set.add(k);
      if (s.mode === 'approx' || s.set.size > s.threshold) {
        // 降级:把已有精确集合灌入 HLL
        s.hll = new HyperLogLog(14);
        for (const x of s.set) s.hll.add(x);
        s.set.clear();
        s.degraded = s.mode !== 'approx';
      }
    },
    value: (s) => (s.hll ? s.hll.count() : s.set.size),
    meta: (s) => ({ approximate: !!s.hll, degraded: s.degraded }),
  },

  // ---------- 取值类 ----------
  first: {
    outputType: (t) => t,
    init: () => ({ v: null, ts: null }),
    add: (s, v, meta) => {
      const ts = meta.timestamp;
      if (s.ts === null || ts < s.ts) { s.ts = ts; s.v = v; }
    },
    value: (s) => s.v,
  },

  last: {
    outputType: (t) => t,
    init: () => ({ v: null, ts: null }),
    add: (s, v, meta) => {
      const ts = meta.timestamp;
      if (s.ts === null || ts >= s.ts) { s.ts = ts; s.v = v; }
    },
    value: (s) => s.v,
  },

  last_value: {
    outputType: (t) => t,
    init: () => ({ v: null, ts: null }),
    add: (s, v, meta) => {
      const ts = meta.timestamp;
      if (s.ts === null || ts >= s.ts) { s.ts = ts; s.v = v; }
    },
    value: (s) => s.v,
  },

  global_latest: {
    outputType: (t) => t,
    init: () => ({ v: null, ts: null }),
    add: (s, v, meta) => {
      const ts = meta.timestamp;
      if (s.ts === null || ts >= s.ts) { s.ts = ts; s.v = v; }
    },
    value: (s) => s.v,
  },

  lastn: {
    outputType: (t) => `list<${t}>`,
    init: (cfg = {}) => ({ n: parseInt(cfg.param, 10) || 10, items: [] }),
    add: (s, v, meta) => { s.items.push({ v, ts: meta.timestamp }); },
    // 规格:按时间倒序(最新在前),不足 N 条返回全部,不补位
    value: (s) => s.items
      .slice()
      .sort((a, b) => b.ts - a.ts)
      .slice(0, s.n)
      .map((x) => x.v),
  },

  distinct: {
    outputType: (t) => `list<${t}>`,
    init: () => ({ seen: new Set(), order: [] }),
    add: (s, v) => {
      const k = String(v);
      if (!s.seen.has(k)) { s.seen.add(k); s.order.push(v); }
    },
    value: (s) => s.order.slice(),   // 规格:按首次出现顺序
  },

  collection: {
    outputType: (t) => `list<${t}>`,
    init: () => ({ items: [] }),
    add: (s, v) => { s.items.push(v); },
    value: (s) => s.items.slice(),   // 规格:保持到达顺序
  },

  // ---------- 合并类 ----------
  merge: {
    outputType: (t) => t,
    init: () => ({ m: new Map(), ts: new Map() }),
    add: (s, v, meta) => {
      if (!v || typeof v !== 'object') return;
      for (const [k, val] of Object.entries(v)) {
        const prev = s.ts.get(k);
        if (prev === undefined || meta.timestamp >= prev) {
          s.m.set(k, val); s.ts.set(k, meta.timestamp);   // 键冲突取较新
        }
      }
    },
    value: (s) => Object.fromEntries([...s.m.entries()].sort(cmpKey)),
  },

  merge_value: {
    outputType: (t) => t,
    init: () => ({ m: new Map() }),
    add: (s, v) => {
      if (!v || typeof v !== 'object') return;
      for (const [k, val] of Object.entries(v)) {
        s.m.set(k, (s.m.get(k) || 0) + val);              // 键冲突求和
      }
    },
    value: (s) => Object.fromEntries([...s.m.entries()].sort(cmpKey)),
  },

  // ---------- TopN ----------
  top: {
    outputType: () => 'list<{key,value}>',
    init: (cfg = {}) => ({ n: parseInt(cfg.param, 10) || 100, m: new Map() }),
    add: (s, v, meta) => {
      const k = meta && meta.groupValue;
      if (k === null || k === undefined) return;
      s.m.set(k, (s.m.get(k) || 0) + (typeof v === 'number' ? v : 1));
    },
    // 规格:按值降序;值相等时按 key 字典序升序,保证结果稳定
    value: (s) => [...s.m.entries()]
      .sort((a, b) => (b[1] - a[1]) || cmpKey(a, b))
      .slice(0, s.n)
      .map(([key, value]) => ({ key, value })),
  },
};

OPERATORS.topn = OPERATORS.top;

function sampleVariance(s) {
  if (s.n <= 1) return 0.0;            // 规格:n <= 1 返回 0.0
  const mean = s.sum / s.n;
  return (s.sq - s.sum * mean) / (s.n - 1);
}

function cmpKey(a, b) {
  const x = String(a[0]); const y = String(b[0]);
  return x < y ? -1 : x > y ? 1 : 0;
}

/** 创建一个算子状态机实例 */
function createAccumulator(method, config = {}) {
  const op = OPERATORS[method];
  if (!op) throw new Error(`未实现的聚合算子: ${method}(规格见 docs/reference/operators.md)`);
  const state = op.init(config);
  return {
    method,
    add(value, meta = {}) {
      if (value === null || value === undefined) return;   // 规格:null 一律跳过
      op.add(state, value, meta);
    },
    value() { return op.value(state); },
    meta() { return op.meta ? op.meta(state) : {}; },
  };
}

module.exports = { OPERATORS, createAccumulator, sampleVariance };
