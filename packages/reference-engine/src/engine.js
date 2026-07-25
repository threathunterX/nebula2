'use strict';
/**
 * 策略引擎的参考实现。
 *
 * 它直接消费 seeds/ 下经过 schema 校验的 2.0 资产,把事件流跑成风险告警。
 * 目的不是性能,而是:
 *   1. 验证算子语义规格自洽(规格里那些「空窗口返回什么」的规定,只有真的
 *      实现一遍才知道有没有漏洞)
 *   2. 为 golden 回归测试生成基线
 *   3. 给 Java 引擎实现一个可对照的参照物
 */

const { WindowedAggregate } = require('./windows');
const { evalFilter, CONDITIONS } = require('./conditions');
const { evalCel } = require('./cel');
const { VariableGraph } = require('./variables');
const { EventModel } = require('./events');

class Engine {
  /**
   * @param {object} opts
   * @param {Array}  opts.strategies 2.0 结构的策略数组
   * @param {number} opts.dedupWindowMs 告警去重窗口,缺省取策略的 dedup_window
   */
  constructor({ strategies = [], variables = null, events = null, now = () => Date.now() } = {}) {
    // 只有 online 与 test 状态的策略参与计算;test 产出的告警标记 test=true
    this.strategies = strategies.filter((s) => s.status === 'online' || s.status === 'test');
    this.counters = new Map();      // 内联计数器状态:key -> WindowedAggregate
    this.notices = [];
    this.dedup = new Map();         // `${strategy}|${key}` -> 上次告警时间
    this.pendingDelays = [];        // 延迟求值队列
    this.now = now;
    this.watermark = -Infinity;     // 流级水位线
    this.eventModel = events && events.length ? new EventModel(events) : null;

    // 变量计算图:只构建策略实际引用到的变量及其依赖闭包
    this.graph = null;
    if (variables && variables.length) {
      const referenced = new Set();
      const collect = (n) => {
        if (!n || typeof n !== 'object') return;
        if (n.kind === 'variable' && n.variable) referenced.add(n.variable);
        for (const v of Object.values(n)) collect(v);
      };
      for (const st of this.strategies) { collect(st.condition); collect(st.delay); }
      if (referenced.size) this.graph = new VariableGraph(variables, referenced, this.eventModel);
    }
    this.stats = { events: 0, evaluated: 0, hits: 0, deduped: 0, lateDropped: 0, lateAccepted: 0 };
  }

  // ---------------------------------------------------------------- 内联计数器

  counterKey(strategyName, path, counter, event) {
    const groupVals = (counter.groupby || []).map((f) => String(event[f] ?? ''));
    return `${strategyName}|${path}|${groupVals.join('')}`;
  }

  /** 内联计数器:先按 filter 判定是否计入,再取值 */
  updateCounter(strategyName, path, counter, event) {
    const id = this.counterKey(strategyName, path, counter, event);
    let agg = this.counters.get(id);
    if (!agg) {
      agg = new WindowedAggregate({
        method: counter.algorithm,
        period: { type: 'last_n_seconds', value: String(counter.window) },
        config: {},
      });
      this.counters.set(id, agg);
    }

    // 事件是否满足计数器自身的过滤条件
    const passes = evalFilter(counter.filter, (f) => event[f]);
    if (passes) {
      // distinct_count 统计 operand 指定字段的去重基数;count 只需计数
      const operandField = (counter.operand && counter.operand[0]) || null;
      const v = operandField ? event[operandField] : 1;
      const r = agg.add(v === undefined ? null : v,
        { timestamp: event.timestamp, watermark: this.watermark });
      if (r === 'late-dropped') this.stats.lateDropped += 1;
      else if (r === 'late-accepted') this.stats.lateAccepted += 1;
    }
    return agg.value(event.timestamp);
  }

  // ---------------------------------------------------------------- 条件求值

  resolveOperand(operand, ctx) {
    if (!operand) return null;
    switch (operand.kind) {
      case 'constant':
        return operand.value;
      case 'event_field':
        return ctx.event[operand.field];
      case 'counter':
        return this.updateCounter(ctx.strategy.name, ctx.path, operand.counter, ctx.event);
      case 'variable': {
        if (this.graph) return this.graph.valueOf(operand.variable, ctx.event);
        // 未加载变量图时允许由外部注入(便于单测隔离)
        return ctx.variables ? ctx.variables[operand.variable] : null;
      }
      default:
        throw new Error(`未知的操作数类型: ${operand.kind}`);
    }
  }

  evalCondition(cond, ctx, path = 'c') {
    if (!cond) return true;

    // 逻辑组合
    if (cond.op === 'and' || cond.op === 'or' || cond.op === 'not') {
      const subs = cond.conditions || [];
      if (cond.op === 'and') {
        for (let i = 0; i < subs.length; i++) {
          if (!this.evalCondition(subs[i], ctx, `${path}.${i}`)) return false;
        }
        return true;
      }
      if (cond.op === 'or') {
        for (let i = 0; i < subs.length; i++) {
          if (this.evalCondition(subs[i], ctx, `${path}.${i}`)) return true;
        }
        return false;
      }
      return !this.evalCondition(subs[0], ctx, `${path}.0`);
    }

    // CEL 表达式
    if (cond.cel) {
      return !!evalCel(cond.cel, ctx.event);
    }

    // 二元比较
    const op = CONDITIONS[cond.op];
    if (!op) throw new Error(`未实现的比较算子: ${cond.op}`);
    const left = this.resolveOperand(cond.left, { ...ctx, path });
    const right = cond.right ? this.resolveOperand(cond.right, { ...ctx, path }) : undefined;

    const result = !!op(left, right);
    // 记录用于告警可解释性(规格:2.0 必须落地 variable_values)
    ctx.trace.push({
      path,
      kind: cond.left && cond.left.kind,
      subject: describeOperand(cond.left),
      value: left,
      op: cond.op,
      threshold: right,
      passed: result,
    });
    return result;
  }

  /** 事件名匹配:考虑单继承链(ACCOUNT_LOGIN 也是 HTTP_DYNAMIC) */
  matchesEvent(actual, expected) {
    if (actual === expected) return true;
    return this.eventModel ? this.eventModel.isA(actual, expected) : false;
  }

  // ---------------------------------------------------------------- 主流程

  process(event) {
    this.stats.events += 1;
    // 先按当前水位线判定迟到,再推进水位线
    this.watermark = Math.max(this.watermark, event.timestamp);
    // 变量图先于策略求值 —— 策略读到的是包含本条事件在内的最新值
    if (this.graph) this.graph.process(event);
    this.fireDueDelays(event.timestamp);

    for (const strategy of this.strategies) {
      if (strategy.trigger && strategy.trigger.event && !this.matchesEvent(event.name, strategy.trigger.event)) {
        continue;
      }
      this.stats.evaluated += 1;
      const trace = [];
      const ctx = { event, strategy, trace, variables: event.__variables };

      let ok;
      try {
        ok = this.evalCondition(strategy.condition, ctx);
      } catch (e) {
        throw new Error(`策略「${strategy.name}」求值失败: ${e.message}`);
      }
      if (!ok) continue;

      if (strategy.delay) {
        this.pendingDelays.push({
          fireAt: event.timestamp + strategy.delay.duration_seconds * 1000,
          strategy,
          event,
          trace,
        });
        continue;
      }
      this.emit(strategy, event, trace);
    }
  }

  fireDueDelays(nowTs) {
    const due = this.pendingDelays.filter((d) => d.fireAt <= nowTs);
    if (!due.length) return;
    this.pendingDelays = this.pendingDelays.filter((d) => d.fireAt > nowTs);
    for (const d of due) {
      const trace = d.trace.slice();
      const ctx = { event: d.event, strategy: d.strategy, trace, variables: d.event.__variables };
      // 延迟条件在延迟到期时求值(此时窗口内已积累了这段时间的数据)
      if (this.evalCondition(d.strategy.delay.condition, ctx, 'd')) {
        this.emit(d.strategy, d.event, trace, d.fireAt);
      }
    }
  }

  emit(strategy, event, trace, atTs) {
    const action = strategy.action;
    const key = event[action.check_value];
    if (key === undefined || key === null || key === '') return;   // 无主体则不产出

    const ts = atTs || event.timestamp;
    const dedupKey = `${strategy.name}|${key}`;
    const win = (strategy.dedup_window === undefined ? 300 : strategy.dedup_window) * 1000;
    const last = this.dedup.get(dedupKey);
    if (last !== undefined && ts - last < win) {
      this.stats.deduped += 1;
      return;
    }
    this.dedup.set(dedupKey, ts);
    this.stats.hits += 1;

    const notice = {
      timestamp: ts,
      key: String(key),
      check_type: action.check_type,
      strategy_name: strategy.name,
      scene_name: strategy.category,
      decision: action.decision,
      risk_score: strategy.score || 0,
      expire: ts + action.ttl * 1000,
      remark: strategy.remark || '',
      tags: strategy.tags || [],
      test: strategy.status === 'test',
      geo_province: event.geo_province || 'unknown',
      geo_city: event.geo_city || 'unknown',
      uri_stem: event.page || '',
    };

    // 告警可解释性:1.x 的 variable_values 恒为空,2.0 必须落地
    if (strategy.explain !== false) {
      notice.variable_values = {};
      for (const t of trace) {
        if (t.kind === 'constant') continue;
        notice.variable_values[t.subject] = {
          value: t.value,
          operator: t.op,
          threshold: t.threshold,
        };
      }
    }
    this.notices.push(notice);
    return notice;
  }

  /** 处理完全部事件后调用:让尚未到期的延迟策略按最终时间线结算 */
  finish(finalTs) {
    this.fireDueDelays(finalTs === undefined ? Infinity : finalTs);
    return this.notices;
  }
}

function describeOperand(operand) {
  if (!operand) return '?';
  switch (operand.kind) {
    case 'event_field': return operand.field;
    case 'variable': return operand.variable;
    case 'counter': {
      const c = operand.counter;
      const grp = (c.groupby || []).join(',');
      return `${c.algorithm}(${(c.operand || []).join(',') || '*'}) by ${grp} in ${c.window}s`;
    }
    case 'constant': return 'constant';
    default: return operand.kind;
  }
}

module.exports = { Engine };
