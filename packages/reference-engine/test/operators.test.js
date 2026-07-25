'use strict';
/**
 * 算子的规格符合性测试。
 *
 * 每一条断言都对应 docs/reference/operators.md 中的一条明文规定。
 * 测试名里标注了规格出处,便于规格变更时定位需要同步的测试。
 *
 * 规格要求每个算子至少覆盖:正常路径、空窗口、null 输入、类型不符、
 * 窗口边界、迟到数据。前四项在本文件,后两项在 windows.test.js。
 */

const test = require('node:test');
const assert = require('node:assert');
const { createAccumulator } = require('../src/operators');
const { HyperLogLog } = require('../src/hll');

const at = (ts) => ({ timestamp: ts });
const feed = (method, values, cfg = {}, base = 1000) => {
  const acc = createAccumulator(method, cfg);
  values.forEach((v, i) => acc.add(v, { timestamp: base + i, groupValue: cfg._group ? cfg._group[i] : undefined }));
  return acc;
};

// ---------------------------------------------------------------- 计数类

test('count:统计有效输入条数', () => {
  assert.strictEqual(feed('count', [1, 2, 3]).value(), 3);
});

test('count:空窗口返回 0(规格 §2.1)', () => {
  assert.strictEqual(feed('count', []).value(), 0);
});

test('count:null 被跳过而非计入(规格「阅读约定」)', () => {
  assert.strictEqual(feed('count', [1, null, 2, undefined, 3]).value(), 3);
});

// ---------------------------------------------------------------- 数值类

test('sum:空窗口返回 0(规格 §2.2)', () => {
  assert.strictEqual(feed('sum', []).value(), 0);
});

test('max/min:空窗口返回 null,而不是 0(规格 §2.2)', () => {
  assert.strictEqual(feed('max', []).value(), null);
  assert.strictEqual(feed('min', []).value(), null);
});

test('avg:空窗口返回 null(规格 §2.2)', () => {
  assert.strictEqual(feed('avg', []).value(), null);
  assert.strictEqual(feed('avg', [2, 4]).value(), 3);
});

test('variance:样本方差,分母 n-1(规格 §2.2)', () => {
  // [2,4,4,4,5,5,7,9] 的样本方差 = 32/7
  const v = feed('variance', [2, 4, 4, 4, 5, 5, 7, 9]).value();
  assert.ok(Math.abs(v - 32 / 7) < 1e-9, `期望 32/7,实际 ${v}`);
});

test('variance/stddev:n <= 1 时返回 0.0(规格 §2.2)', () => {
  assert.strictEqual(feed('variance', []).value(), 0.0);
  assert.strictEqual(feed('variance', [5]).value(), 0.0);
  assert.strictEqual(feed('stddev', [5]).value(), 0.0);
});

test('stddev 是 variance 的平方根 —— 与 1.x 的关键差异(规格 §2.2)', () => {
  const data = [2, 4, 4, 4, 5, 5, 7, 9];
  const varr = feed('variance', data).value();
  const sd = feed('stddev', data).value();
  assert.ok(Math.abs(sd - Math.sqrt(varr)) < 1e-12);
  // 1.x 的 stddev 返回的是 varr 本身;两者必须可区分,否则迁移映射无意义
  assert.ok(Math.abs(sd - varr) > 1e-6, 'stddev 与 variance 必须不同');
});

test('cv:均值为 0 或 n<=1 时返回 null(规格 §2.2)', () => {
  assert.strictEqual(feed('cv', [5]).value(), null);
  assert.strictEqual(feed('cv', [-1, 1]).value(), null);   // 均值为 0
  assert.ok(feed('cv', [2, 4, 6]).value() > 0);
});

// ---------------------------------------------------------------- 去重计数

test('distinct_count:默认精确模式(规格 §2.5)', () => {
  const acc = feed('distinct_count', ['a', 'b', 'a', 'c', 'b']);
  assert.strictEqual(acc.value(), 3);
  assert.strictEqual(acc.meta().approximate, false);
});

test('distinct_count:空窗口返回 0(规格 §2.1)', () => {
  assert.strictEqual(feed('distinct_count', []).value(), 0);
});

test('distinct_count:基数 <= 20 时必须精确 —— golden 对照的边界约束', () => {
  // tests/golden/README.md 的豁免规则 D1:基数 <= 20 时新旧必须完全相等
  for (let n = 1; n <= 20; n++) {
    const vals = Array.from({ length: n }, (_, i) => `v${i}`);
    assert.strictEqual(feed('distinct_count', vals).value(), n, `基数 ${n} 应精确`);
  }
});

test('distinct_count:超过阈值自动降级并标记 approximate(规格 §2.5)', () => {
  const acc = createAccumulator('distinct_count', { approx_threshold: 50 });
  for (let i = 0; i < 200; i++) acc.add(`v${i}`, at(1000 + i));
  assert.strictEqual(acc.meta().approximate, true);
  assert.strictEqual(acc.meta().degraded, true);
  const err = Math.abs(acc.value() - 200) / 200;
  assert.ok(err < 0.15, `降级后误差应可接受,实际 ${(err * 100).toFixed(1)}%`);
});

test('HLL(log2m=14) 的误差在 1% 量级(规格 §2.5)', () => {
  const hll = new HyperLogLog(14);
  const N = 50000;
  for (let i = 0; i < N; i++) hll.add(`item-${i}`);
  const err = Math.abs(hll.count() - N) / N;
  assert.ok(err < 0.02, `期望误差 < 2%,实际 ${(err * 100).toFixed(2)}%`);
});

// ---------------------------------------------------------------- 取值类

test('first/last:依据事件时间而非到达顺序(规格 §2.3)', () => {
  const acc = createAccumulator('first');
  acc.add('late', at(3000));
  acc.add('early', at(1000));   // 后到达但事件时间更早
  acc.add('mid', at(2000));
  assert.strictEqual(acc.value(), 'early');

  const l = createAccumulator('last');
  l.add('late', at(3000));
  l.add('early', at(1000));
  assert.strictEqual(l.value(), 'late');
});

test('first/last:空窗口返回 null(规格 §2.3)', () => {
  assert.strictEqual(feed('first', []).value(), null);
  assert.strictEqual(feed('last', []).value(), null);
});

test('lastn:按时间倒序,最新在前(规格 §2.3)', () => {
  const acc = createAccumulator('lastn', { param: '3' });
  ['a', 'b', 'c', 'd', 'e'].forEach((v, i) => acc.add(v, at(1000 + i * 10)));
  assert.deepStrictEqual(acc.value(), ['e', 'd', 'c']);
});

test('lastn:N 超过实际条数时返回全部,不补位(规格 §2.3)', () => {
  const acc = createAccumulator('lastn', { param: '10' });
  ['a', 'b'].forEach((v, i) => acc.add(v, at(1000 + i)));
  assert.deepStrictEqual(acc.value(), ['b', 'a']);
});

test('lastn:乱序到达仍按事件时间排序(规格 §2.3)', () => {
  const acc = createAccumulator('lastn', { param: '2' });
  acc.add('t1', at(1000));
  acc.add('t3', at(3000));
  acc.add('t2', at(2000));
  assert.deepStrictEqual(acc.value(), ['t3', 't2']);
});

test('distinct:按首次出现顺序去重(规格 §2.3)', () => {
  assert.deepStrictEqual(feed('distinct', ['b', 'a', 'b', 'c']).value(), ['b', 'a', 'c']);
});

test('collection:保持到达顺序,不去重(规格 §2.3)', () => {
  assert.deepStrictEqual(feed('collection', ['b', 'a', 'b']).value(), ['b', 'a', 'b']);
});

test('取值类算子空窗口返回空列表(规格 §2.3)', () => {
  assert.deepStrictEqual(feed('lastn', []).value(), []);
  assert.deepStrictEqual(feed('distinct', []).value(), []);
  assert.deepStrictEqual(feed('collection', []).value(), []);
});

// ---------------------------------------------------------------- 分组与 Top

test('group_count:按分组字段计数,空窗口返回空 map(规格 §2.1)', () => {
  const acc = createAccumulator('group_count');
  [['x'], ['y'], ['x']].forEach(([g], i) => acc.add(1, { timestamp: 1000 + i, groupValue: g }));
  assert.deepStrictEqual(acc.value(), { x: 2, y: 1 });
  assert.deepStrictEqual(createAccumulator('group_count').value(), {});
});

test('group_count:分组值为 null 时跳过', () => {
  const acc = createAccumulator('group_count');
  acc.add(1, { timestamp: 1, groupValue: 'x' });
  acc.add(1, { timestamp: 2, groupValue: null });
  assert.deepStrictEqual(acc.value(), { x: 1 });
});

test('top:按值降序;值相等时按 key 字典序升序(规格 §1.6)', () => {
  const acc = createAccumulator('top', { param: '3' });
  const rows = [['b', 5], ['a', 5], ['c', 9], ['d', 1]];
  rows.forEach(([g, v], i) => acc.add(v, { timestamp: 1000 + i, groupValue: g }));
  assert.deepStrictEqual(acc.value(), [
    { key: 'c', value: 9 },
    { key: 'a', value: 5 },   // 与 b 同值,字典序在前
    { key: 'b', value: 5 },
  ]);
});

test('top:默认 N 为 100(规格 §1.6)', () => {
  const acc = createAccumulator('top');
  for (let i = 0; i < 150; i++) acc.add(i, { timestamp: 1000 + i, groupValue: `k${i}` });
  assert.strictEqual(acc.value().length, 100);
});

// ---------------------------------------------------------------- 合并类

test('merge:键冲突取较新的值(规格 §2.4)', () => {
  const acc = createAccumulator('merge');
  acc.add({ a: 1, b: 2 }, at(1000));
  acc.add({ a: 9 }, at(2000));
  assert.deepStrictEqual(acc.value(), { a: 9, b: 2 });
});

test('merge_value:键冲突对值求和(规格 §2.4)', () => {
  const acc = createAccumulator('merge_value');
  acc.add({ a: 1, b: 2 }, at(1000));
  acc.add({ a: 9 }, at(2000));
  assert.deepStrictEqual(acc.value(), { a: 10, b: 2 });
});

// ---------------------------------------------------------------- 未实现算子

test('未实现的算子必须立即报错,而不是静默返回 null', () => {
  assert.throws(() => createAccumulator('no_such_operator'), /未实现的聚合算子/);
});
