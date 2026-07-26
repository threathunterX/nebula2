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
