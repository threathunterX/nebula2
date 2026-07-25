'use strict';
/**
 * 变量计算图的参考实现,对应 docs/reference/operators.md §1。
 *
 * 一条事件进入后沿图自根向下传播,节点返回「不通过」即剪枝,下游不再计算。
 * 节点类型与语义:
 *   event     事件解包,图的根
 *   filter    过滤 + 字段派生,无状态
 *   aggregate 窗口内按 key 聚合(核心)
 *   dual      两个上游变量做二元运算
 *   sequence  相邻两次事件求差
 *   top       按值排序取前 N
 */

const { WindowedAggregate } = require('./windows');
const { evalFilter } = require('./conditions');

/** 维度 key 字段 -> 事件字段(1.x 的命名沿用) */
function keyFieldOf(field) {
  return field;
}

class VariableGraph {
  /**
   * @param {Array} variables seeds/variables 下的全部定义
   * @param {Set<string>} [needed] 只构建这些变量及其依赖闭包;省略则全建
   */
  constructor(variables, needed, eventModel = null) {
    this.defs = new Map(variables.map((v) => [v.name, v]));
    this.eventModel = eventModel;
    this.nodes = new Map();      // name -> { def, state }
    this.order = [];             // 拓扑序
    this.watermark = -Infinity;
    this.stats = { propagated: 0, pruned: 0, lateDropped: 0 };

    const scope = needed ? this.closureOf(needed) : new Set(this.defs.keys());
    this.build(scope);
  }

  /** 依赖闭包:变量 -> 其 source 指向的变量/事件,递归 */
  closureOf(names) {
    const out = new Set();
    const stack = [...names];
    while (stack.length) {
      const n = stack.pop();
      if (out.has(n)) continue;
      out.add(n);
      const d = this.defs.get(n);
      if (!d) continue;
      // event 类型的 source 指向同名的原始事件流,不是上游变量,不展开
      if (d.type === 'event') continue;
      for (const s of d.source || []) stack.push(s.name);
    }
    return out;
  }

  /** 拓扑排序:被依赖者在前。存在环时抛错(schema 层也会拒绝,这里是双保险) */
  build(scope) {
    const visiting = new Set();
    const done = new Set();
    const visit = (name, path) => {
      if (done.has(name)) return;
      if (visiting.has(name)) {
        throw new Error(`变量定义存在循环依赖: ${[...path, name].join(' -> ')}`);
      }
      const def = this.defs.get(name);
      if (!def) return;                    // 事件名等外部引用
      visiting.add(name);
      if (def.type !== 'event') {
        for (const s of def.source || []) visit(s.name, [...path, name]);
      }
      visiting.delete(name);
      done.add(name);
      this.order.push(name);
      this.nodes.set(name, { def, state: new Map() });
    };
    for (const n of [...scope].sort()) visit(n, []);
  }

  // ---------------------------------------------------------------- 取值

  /** 由事件求出某变量在当前上下文下的 key */
  keyFor(def, event) {
    const keys = def.groupbykeys || [];
    if (!keys.length) return '__GLOBAL__';
    return keys.map((f) => String(event[keyFieldOf(f)] ?? '')).join('');
  }

  /** 变量当前值;不存在则按算子的空窗口语义返回 */
  valueOf(name, event) {
    const node = this.nodes.get(name);
    if (!node) return null;
    const key = this.keyFor(node.def, event);
    const st = node.state.get(key);
    if (!st) return this.emptyValueOf(node.def, event);
    if (st.agg) return st.agg.value(event.timestamp);
    return st.value === undefined ? null : st.value;
  }

  emptyValueOf(def, event) {
    if (def.type !== 'aggregate') return null;
    const probe = new WindowedAggregate({
      method: (def.function || {}).method,
      period: def.period,
      config: def.function ? def.function.config || {} : {},
    });
    return probe.value(event ? event.timestamp : 0);
  }

  // ---------------------------------------------------------------- 传播

  process(event) {
    this.watermark = Math.max(this.watermark, event.timestamp);
    const produced = new Set();          // 本次事件中产生了值的变量

    for (const name of this.order) {
      const node = this.nodes.get(name);
      const def = node.def;
      try {
        if (this.step(name, node, def, event, produced)) {
          produced.add(name);
          this.stats.propagated += 1;
        } else {
          this.stats.pruned += 1;
        }
      } catch (e) {
        throw new Error(`变量「${name}」计算失败: ${e.message}`);
      }
    }
    return produced;
  }

  matchesEvent(actual, expected) {
    if (actual === expected) return true;
    return this.eventModel ? this.eventModel.isA(actual, expected) : false;
  }

  upstreamReady(srcNames, produced, event) {
    return srcNames.some((s) => produced.has(s) || this.matchesEvent(event.name, s));
  }

  step(name, node, def, event, produced) {
    const srcNames = (def.source || []).map((s) => s.name);

    switch (def.type) {
      case 'event':
        // 事件解包:名称匹配即通过
        return this.matchesEvent(event.name, name)
          || srcNames.some((s) => this.matchesEvent(event.name, s));

      case 'filter': {
        // 上游必须先通过
        if (!this.upstreamReady(srcNames, produced, event)) return false;
        return evalFilter(def.filter, (f) => event[f]);
      }

      case 'aggregate': {
        if (!this.upstreamReady(srcNames, produced, event)) return false;
        if (!evalFilter(def.filter, (f) => event[f])) return false;

        const fn = def.function || {};
        const key = this.keyFor(def, event);
        let st = node.state.get(key);
        if (!st) {
          st = {
            agg: new WindowedAggregate({
              method: fn.method,
              period: def.period,
              config: fn.config || {},
            }),
          };
          node.state.set(key, st);
        }

        // 被聚合的值:object 指向事件字段,或上游变量的 value
        let v;
        if (!fn.object || fn.object === 'value') {
          v = srcNames.length && this.nodes.has(srcNames[0])
            ? this.valueOf(srcNames[0], event)
            : 1;
        } else {
          v = event[fn.object];
        }

        // group_count / group_sum / top 需要分组字段的值
        const groupField = fn.param || null;
        const meta = {
          timestamp: event.timestamp,
          watermark: this.watermark,
          groupValue: groupField ? event[groupField] : undefined,
        };
        const r = st.agg.add(v === undefined ? null : v, meta);
        if (r === 'late-dropped') this.stats.lateDropped += 1;
        return true;
      }

      case 'dual': {
        if (srcNames.length !== 2) throw new Error('dual 变量必须恰好有两个上游');
        const a = this.valueOf(srcNames[0], event);
        const b = this.valueOf(srcNames[1], event);
        if (a === null || b === null) return false;          // 规格:任一为 null 则不通过

        const op = (def.function || {}).method;
        let out;
        if (op === '/') {
          if (b === 0) return false;                          // 规格:除零返回 null(不通过)
          out = a / b;
        } else if (op === '+') out = a + b;
        else if (op === '-') out = a - b;
        else if (op === '*') out = a * b;
        else throw new Error(`dual 不支持的运算符: ${op}`);

        // key 取自第二个上游(规格 §1.5)
        const key = this.keyFor(this.nodes.get(srcNames[1]).def, event);
        node.state.set(key, { value: out });
        return true;
      }

      case 'sequence': {
        if (!this.upstreamReady(srcNames, produced, event)) return false;
        const fn = def.function || {};
        const cur = event[fn.object || 'timestamp'];
        if (cur === null || cur === undefined) return false;

        const key = this.keyFor(def, event);
        const st = node.state.get(key) || {};
        const prev = st.prev;
        st.prev = cur;
        if (prev === undefined) {                             // 规格:首条事件不通过
          node.state.set(key, st);
          return false;
        }
        st.value = cur - prev;                                // 规格:当前值 − 上一次值
        node.state.set(key, st);
        return true;
      }

      case 'top': {
        if (!srcNames.some((s) => produced.has(s))) return false;
        const parent = this.nodes.get(srcNames[0]);
        if (!parent) return false;
        const key = this.keyFor(def, event);
        const pkey = this.keyFor(parent.def, event);
        const pst = parent.state.get(pkey);
        const raw = pst && pst.agg ? pst.agg.value(event.timestamp) : null;
        if (!raw || typeof raw !== 'object') return false;
        const n = parseInt((def.function || {}).param, 10) || 100;
        const rows = Object.entries(raw)
          .sort((x, y) => (y[1] - x[1]) || (x[0] < y[0] ? -1 : x[0] > y[0] ? 1 : 0))
          .slice(0, n)
          .map(([k, v]) => ({ key: k, value: v }));
        node.state.set(key, { value: rows });
        return true;
      }

      default:
        throw new Error(`未实现的变量类型: ${def.type}`);
    }
  }
}

module.exports = { VariableGraph };
