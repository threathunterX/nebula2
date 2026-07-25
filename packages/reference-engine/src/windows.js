'use strict';
/**
 * 时间窗口模型的参考实现,对应 docs/reference/operators.md §4。
 *
 * 三条关键规定:
 *   1. 窗口按**事件时间**划分,不是处理时间
 *   2. allowedLateness 之内的迟到事件仍会更新对应窗口;超出则进侧输出,
 *      **不静默丢弃**(这正是 1.x 需要离线重算的根因之一)
 *   3. 聚合结果在窗口内**持续可见**,不等窗口关闭才输出 —— 风控要在攻击
 *      进行中就能判定
 */

const { createAccumulator } = require('./operators');

const DEFAULT_ALLOWED_LATENESS_MS = 60 * 1000;

/** 把 period 定义解析为窗口参数 */
function parsePeriod(period) {
  const type = (period && period.type) || '';
  const raw = period && period.value;
  const n = raw === '' || raw === undefined || raw === null ? 1 : parseInt(raw, 10);

  switch (type) {
    case 'last_n_seconds': return { kind: 'sliding', sizeMs: n * 1000 };
    case 'last_n_hours': return { kind: 'sliding', sizeMs: n * 3600 * 1000 };
    case 'last_n_days': return { kind: 'sliding', sizeMs: n * 86400 * 1000 };
    case 'hourly': return { kind: 'tumbling', sizeMs: n * 3600 * 1000 };
    case 'today': return { kind: 'daily' };
    case 'ever': return { kind: 'unbounded' };
    case 'self': return { kind: 'none' };
    case '': return { kind: 'none' };
    default: throw new Error(`未实现的窗口类型: ${type}(规格见 docs/reference/operators.md §4.1)`);
  }
}

/**
 * 单个 key 上的窗口聚合状态。
 * 滑动窗口保留原始事件以便过期时重算;滚动/无界窗口只保留累加器。
 */
class WindowedAggregate {
  constructor({ method, period, config = {}, allowedLatenessMs = DEFAULT_ALLOWED_LATENESS_MS }) {
    this.method = method;
    this.config = config;
    this.win = parsePeriod(period);
    this.allowedLatenessMs = allowedLatenessMs;
    this.events = [];            // 滑动窗口用
    this.acc = null;             // 滚动/无界窗口用
    this.currentWindowStart = null;
    this.watermark = -Infinity;
    this.lateDropped = 0;        // 超出容忍度的迟到事件计数(对应侧输出)
    this.lateAccepted = 0;
  }

  windowStartFor(ts) {
    if (this.win.kind === 'tumbling') return Math.floor(ts / this.win.sizeMs) * this.win.sizeMs;
    if (this.win.kind === 'daily') return Math.floor(ts / 86400000) * 86400000;
    return null;
  }

  /**
   * 返回 'accepted' | 'late-accepted' | 'late-dropped'
   *
   * 水位线是**流级**属性而非按 key 维护 —— 否则一个新 key 的首个事件永远
   * 不会被判为迟到。调用方负责维护并通过 meta.watermark 传入。
   */
  add(value, meta) {
    const ts = meta.timestamp;
    const wm = meta.watermark === undefined ? -Infinity : meta.watermark;

    if (ts < wm - this.allowedLatenessMs) {
      this.lateDropped += 1;
      return 'late-dropped';                   // 进侧输出,不静默丢弃
    }
    const isLate = ts < wm;
    if (isLate) this.lateAccepted += 1;
    this.watermark = Math.max(this.watermark, ts);

    if (this.win.kind === 'sliding') {
      this.events.push({ value, meta });
      return isLate ? 'late-accepted' : 'accepted';
    }

    if (this.win.kind === 'tumbling' || this.win.kind === 'daily') {
      const start = this.windowStartFor(ts);
      if (this.currentWindowStart === null) {
        this.currentWindowStart = start;
        this.acc = createAccumulator(this.method, this.config);
      } else if (start > this.currentWindowStart) {
        // 窗口切换:重置状态(规格 §4.1 滚动窗口不重叠)
        this.currentWindowStart = start;
        this.acc = createAccumulator(this.method, this.config);
      } else if (start < this.currentWindowStart) {
        // 属于已关闭的历史窗口 —— 在容忍度内也无法回填到当前状态,
        // 参考实现按侧输出处理并记录
        this.lateDropped += 1;
        return 'late-dropped';
      }
      this.acc.add(value, meta);
      return isLate ? 'late-accepted' : 'accepted';
    }

    // unbounded / none
    if (!this.acc) this.acc = createAccumulator(this.method, this.config);
    this.acc.add(value, meta);
    return isLate ? 'late-accepted' : 'accepted';
  }

  /** 当前累计值。滑动窗口在此处按水位线裁剪过期事件后重算。 */
  value(now = this.watermark) {
    if (this.win.kind === 'sliding') {
      const cutoff = now - this.win.sizeMs;
      // 规格:窗口内 = 尚未过期的事件
      this.events = this.events.filter((e) => e.meta.timestamp > cutoff);
      const acc = createAccumulator(this.method, this.config);
      for (const e of this.events) acc.add(e.value, e.meta);
      return acc.value();
    }
    return this.acc ? this.acc.value() : createAccumulator(this.method, this.config).value();
  }

  stats() {
    return { lateAccepted: this.lateAccepted, lateDropped: this.lateDropped };
  }
}

module.exports = { WindowedAggregate, parsePeriod, DEFAULT_ALLOWED_LATENESS_MS };
