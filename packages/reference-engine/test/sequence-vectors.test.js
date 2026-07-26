'use strict';
/**
 * 多步序列的跨引擎对照向量 —— 参考引擎这一侧。
 *
 * Java 生产引擎读**同一个文件**跑同一批场景
 * (`SequenceVectorTest`)。两边不一致时说明有一侧的语义偏了 ——
 * 这正是 golden 向量存在的理由,#44 修的就是这类跨引擎不一致。
 */

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const { Engine } = require('../src/engine');

const VECTORS = path.resolve(__dirname, '..', '..', '..',
  'tests', 'golden', 'vectors', 'sequences.json');
const doc = JSON.parse(fs.readFileSync(VECTORS, 'utf8'));

for (const c of doc.cases) {
  test(`序列向量 ${c.name}:${c.note}`, () => {
    const strategy = {
      app: 'nebula',
      name: `向量-${c.name}`,
      visible_name: c.name,
      status: 'online',
      category: 'ACCOUNT',
      score: 0,
      dedup_window: 0,
      action: { decision: 'review', check_type: 'IP', check_value: 'c_ip', ttl: 3600 },
      sequence: c.sequence,
    };
    const engine = new Engine({ strategies: [strategy] });
    for (const raw of c.events) {
      const { name, offset_seconds: off, ...rest } = raw;
      engine.process({ name, timestamp: doc.base_timestamp + off * 1000, ...rest });
    }
    assert.strictEqual(engine.notices.length, c.expect_hits,
      `命中 ${engine.notices.length} 次,向量要求 ${c.expect_hits} 次`);
  });
}
