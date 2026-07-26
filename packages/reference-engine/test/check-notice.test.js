'use strict';
/**
 * checkNotice(策略级联)的跨引擎对照向量 —— 参考引擎这一侧。
 *
 * Java 生产引擎读**同一个文件**跑同一批场景(`CheckNoticeVectorTest`)。
 *
 * 每个场景两条策略:setup 命中后产出告警,probe 用 checkNotice 查它。断言的是
 * probe 的命中次数 —— 这样测的是「查得到 / 查不到」,而不是内部实现。
 */

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const { Engine } = require('../src/engine');

const VECTORS = path.resolve(__dirname, '..', '..', '..',
  'tests', 'golden', 'vectors', 'check-notice.json');
const doc = JSON.parse(fs.readFileSync(VECTORS, 'utf8'));

function base(name, checkType, checkValue) {
  return {
    app: 'nebula',
    name,
    visible_name: name,
    status: 'online',
    category: 'ACCOUNT',
    score: 0,
    dedup_window: 0,
    action: { decision: 'review', check_type: checkType, check_value: checkValue, ttl: 3600 },
  };
}

for (const c of doc.cases) {
  test(`checkNotice 向量 ${c.name}:${c.note}`, () => {
    const s = c.setup_strategy;
    const p = c.probe_strategy;
    const strategies = [
      { ...base(s.name, s.check_type, s.check_value), condition: s.condition },
      { ...base(p.name, p.check_type, p.check_value), condition: { cel: p.cel } },
    ];
    const engine = new Engine({ strategies });
    for (const raw of c.events) {
      const { name, offset_seconds: off, ...rest } = raw;
      engine.process({ name, timestamp: doc.base_timestamp + off * 1000, ...rest });
    }
    const probeHits = engine.notices.filter((n) => n.strategy_name === p.name).length;
    assert.strictEqual(probeHits, c.expect_hits,
      `probe 命中 ${probeHits} 次,向量要求 ${c.expect_hits} 次`);
  });
}
