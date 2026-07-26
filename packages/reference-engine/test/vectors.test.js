'use strict';
/**
 * 共享测试向量 —— 跨语言语义一致性的核心机制。
 *
 * 参考引擎(JS)与生产引擎(Java)读**同一份** tests/golden/vectors/operators.json
 * 跑同一批用例。两套实现之间的语义漂移因此在结构上不可能发生:任何一方改了
 * 算子行为,共享向量立刻会在另一方失败。
 *
 * 这是 tests/golden/README.md 描述的对照机制在算子粒度上的落地。
 */

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const { createAccumulator } = require('../src/operators');
const { HyperLogLog } = require('../src/hll');
const { CONDITIONS, setLocationResolver } = require('../src/conditions');
const { WindowedAggregate, parsePeriod } = require('../src/windows');

const VECTORS = path.resolve(__dirname, '..', '..', '..', 'tests', 'golden', 'vectors', 'operators.json');
const suite = JSON.parse(fs.readFileSync(VECTORS, 'utf8'));

test('共享向量文件结构完整', () => {
  assert.ok(Array.isArray(suite.cases) && suite.cases.length > 0);
  const ids = suite.cases.map((c) => c.id);
  assert.strictEqual(new Set(ids).size, ids.length, '用例 id 必须唯一');
  for (const c of suite.cases) {
    assert.ok(c.operator, `${c.id} 缺少 operator`);
    assert.ok(c.spec, `${c.id} 缺少 spec 出处 —— 每个用例都必须能追溯到规格条款`);
    assert.ok(Array.isArray(c.inputs), `${c.id} 缺少 inputs`);
    assert.ok('expect' in c, `${c.id} 缺少 expect`);
  }
});

for (const c of suite.cases) {
  test(`向量 ${c.id}(规格 ${c.spec}):${c.note || c.operator}`, () => {
    const acc = createAccumulator(c.operator, c.param ? { param: c.param } : {});
    c.inputs.forEach((row, i) => {
      acc.add(row.v, {
        timestamp: row.ts === undefined ? 1000 + i : row.ts,
        groupValue: row.g,
      });
    });
    const got = acc.value();

    if (c.tolerance !== undefined && typeof c.expect === 'number') {
      assert.ok(Math.abs(got - c.expect) <= c.tolerance,
        `期望 ${c.expect} ± ${c.tolerance},实际 ${got}`);
    } else {
      assert.deepStrictEqual(got, c.expect);
    }
  });
}

// ---------------------------------------------------------------- 哈希一致性

const HASH_VECTORS = path.resolve(__dirname, '..', '..', '..', 'tests', 'golden', 'vectors', 'murmur3.json');
const hashSuite = JSON.parse(fs.readFileSync(HASH_VECTORS, 'utf8'));

test('MurmurHash3 与共享向量一致 —— HLL 的跨语言可对照性依赖于此', () => {
  for (const c of hashSuite.vectors) {
    assert.strictEqual(HyperLogLog.hash(c.input), c.expect,
      `输入 ${JSON.stringify(c.input)}${c.note ? `(${c.note})` : ''}`);
  }
});

// ---------------------------------------------------------------- 条件算子

const COND_VECTORS = path.resolve(__dirname, '..', '..', '..', 'tests', 'golden', 'vectors', 'conditions.json');
const condSuite = JSON.parse(fs.readFileSync(COND_VECTORS, 'utf8'));

for (const c of condSuite.cases) {
  test(`条件向量 ${c.id}(规格 ${c.spec}):${c.note || c.op}`, () => {
    const fn = CONDITIONS[c.op];
    assert.ok(fn, `未实现的条件算子: ${c.op}`);
    assert.strictEqual(!!fn(c.left, c.right), c.expect);
  });
}

test('条件向量覆盖了全部已实现的算子', () => {
  const covered = new Set(condSuite.cases.map((c) => c.op));
  const missing = Object.keys(CONDITIONS).filter((op) => !covered.has(op));
  assert.deepStrictEqual(missing, [],
    `以下条件算子没有向量覆盖,请补充 tests/golden/vectors/conditions.json: ${missing}`);
});

// ---------------------------------------------------------------- 窗口模型

const WIN_VECTORS = path.resolve(__dirname, '..', '..', '..', 'tests', 'golden', 'vectors', 'windows.json');
const winSuite = JSON.parse(fs.readFileSync(WIN_VECTORS, 'utf8'));

for (const c of winSuite.cases) {
  test(`窗口向量 ${c.id}(规格 ${c.spec}):${c.note || ''}`, () => {
    const agg = new WindowedAggregate({
      method: c.operator,
      period: c.period,
      config: {},
      allowedLatenessMs: c.allowedLatenessMs === undefined ? 60000 : c.allowedLatenessMs,
    });
    const outcomes = [];
    for (const e of c.events) {
      const r = agg.add(e.v, {
        timestamp: e.ts,
        watermark: e.wm === undefined ? -Infinity : e.wm,
      });
      outcomes.push(r === 'accepted' ? 'ACCEPTED'
        : r === 'late-accepted' ? 'LATE_ACCEPTED' : 'LATE_DROPPED');
    }
    assert.strictEqual(agg.value(c.probeTs), c.expect, '窗口聚合值');
    if (c.expectOutcomes) {
      assert.deepStrictEqual(outcomes, c.expectOutcomes, '迟到处置结果');
    }
  });
}

// ---------------------------------------------------------------- 变量图快照

test('变量图计算结果与固化快照一致(跨语言对照的 JS 侧)', () => {
  const { execFileSync } = require('node:child_process');
  const script = path.resolve(__dirname, '..', 'tools', 'export-graph-snapshot.js');
  const got = JSON.parse(execFileSync(process.execPath, [script], { encoding: 'utf8' }));
  const expectPath = path.resolve(__dirname, '..', '..', '..', 'tests', 'golden', 'vectors', 'graph-expected.json');
  const want = JSON.parse(fs.readFileSync(expectPath, 'utf8'));
  assert.deepStrictEqual(got.values, want.values);
});

test('全量策略告警与固化快照一致(端到端对照的 JS 侧)', () => {
  const { execFileSync } = require('node:child_process');
  const script = path.resolve(__dirname, '..', 'tools', 'export-notice-snapshot.js');
  const got = JSON.parse(execFileSync(process.execPath, [script], { encoding: 'utf8' }));
  const expectPath = path.resolve(__dirname, '..', '..', '..', 'tests', 'golden', 'vectors', 'notice-expected.json');
  const want = JSON.parse(fs.readFileSync(expectPath, 'utf8'));
  assert.strictEqual(got.count, want.count, '告警条数');
  assert.deepStrictEqual(got.notices, want.notices);
});
