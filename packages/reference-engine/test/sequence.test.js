'use strict';
/**
 * 多步序列(A → B → C)的语义。
 *
 * 这些断言就是**规格本身** —— Java 生产引擎要按同一批向量对照,
 * 两边不一致时以 strategy.schema.json 的 sequence.description 与这里为准。
 *
 * 序列检测属于「写错了照样有输出」的那类:漏掉一次匹配不会报错,多产出一条也
 * 不会报错,只有拿具体的事件序列去比对才知道对不对。所以每条语义规定各有一个
 * 用例,并且都配了否定用例 —— 只测「该命中的命中了」的话,一个永远返回 true 的
 * 实现也能全绿。
 */

const test = require('node:test');
const assert = require('node:assert');
const { Engine } = require('../src/engine');

const T0 = 1700000000000;

function strategy(overrides = {}) {
  return {
    app: 'nebula',
    name: '序列测试',
    visible_name: '序列测试',
    status: 'online',
    category: 'ACCOUNT',
    score: 0,
    action: { decision: 'review', check_type: 'IP', check_value: 'c_ip', ttl: 3600 },
    dedup_window: 0,
    sequence: {
      steps: [{ event: 'A' }, { event: 'B' }, { event: 'C' }],
      within_seconds: 60,
      by: ['c_ip'],
    },
    ...overrides,
  };
}

/** ev('A', 0) => 名为 A、时间 T0+0 秒的事件,主体固定为一个 IP。 */
function ev(name, offsetSec, extra = {}) {
  return { name, timestamp: T0 + offsetSec * 1000, c_ip: '198.51.100.1', ...extra };
}

function run(st, events) {
  const e = new Engine({ strategies: [st] });
  for (const x of events) e.process(x);
  return { notices: e.notices, stats: e.stats, engine: e };
}

test('按顺序走完三步 → 命中一次', () => {
  const { notices } = run(strategy(), [ev('A', 0), ev('B', 1), ev('C', 2)]);
  assert.strictEqual(notices.length, 1);
  assert.strictEqual(notices[0].key, '198.51.100.1');
});

test('顺序不对不命中 —— 这是序列与「三个条件都满足」的根本区别', () => {
  const { notices } = run(strategy(), [ev('C', 0), ev('B', 1), ev('A', 2)]);
  assert.strictEqual(notices.length, 0);
});

test('缺一步不命中', () => {
  const { notices } = run(strategy(), [ev('A', 0), ev('C', 1)]);
  assert.strictEqual(notices.length, 0);
});

test('超出 within_seconds 不命中', () => {
  const { notices } = run(strategy(), [ev('A', 0), ev('B', 1), ev('C', 61)]);
  assert.strictEqual(notices.length, 0, '第三步超窗,整次匹配作废');
});

test('恰好卡在窗口边界上算命中 —— 边界是闭区间', () => {
  const { notices } = run(strategy(), [ev('A', 0), ev('B', 1), ev('C', 60)]);
  assert.strictEqual(notices.length, 1);
});

test('不同主体的事件不串成一条序列', () => {
  const { notices } = run(strategy(), [
    { name: 'A', timestamp: T0, c_ip: '198.51.100.1' },
    { name: 'B', timestamp: T0 + 1000, c_ip: '198.51.100.2' },
    { name: 'C', timestamp: T0 + 2000, c_ip: '198.51.100.3' },
  ]);
  assert.strictEqual(notices.length, 0, 'by 分组必须真的隔开不同主体');
});

test('同一毫秒的两条事件不构成先后', () => {
  const { notices } = run(strategy(), [ev('A', 0), ev('B', 0), ev('C', 0)]);
  assert.strictEqual(notices.length, 0, '每一步必须严格晚于前一步');
});

test('一条 B 只推进一个匹配,不会同时推进所有停在 A 的匹配', () => {
  // A A B C:两个 A 各开一个匹配,B 只推进其中一个,所以只可能有一次完整匹配
  const { notices } = run(strategy(), [ev('A', 0), ev('A', 1), ev('B', 2), ev('C', 3)]);
  assert.strictEqual(notices.length, 1, '否则会产出一堆重复告警');
});

test('构成一次匹配的事件不再参与后续匹配', () => {
  // A B C C:第二个 C 没有可推进的匹配了
  const { notices } = run(strategy(), [ev('A', 0), ev('B', 1), ev('C', 2), ev('C', 3)]);
  assert.strictEqual(notices.length, 1);
});

test('两条完整序列产出两次', () => {
  const st = strategy({ dedup_window: 0 });
  const { notices } = run(st, [
    ev('A', 0), ev('B', 1), ev('C', 2),
    ev('A', 10), ev('B', 11), ev('C', 12),
  ]);
  assert.strictEqual(notices.length, 2);
});

test('步骤上的附加条件不满足时不推进', () => {
  const st = strategy({
    sequence: {
      steps: [
        { event: 'A' },
        {
          event: 'B',
          condition: {
            left: { kind: 'event_field', field: 'status' },
            op: '==',
            right: { kind: 'constant', value: '500' },
          },
        },
      ],
      within_seconds: 60,
      by: ['c_ip'],
    },
  });
  const miss = run(st, [ev('A', 0), ev('B', 1, { status: '200' })]);
  assert.strictEqual(miss.notices.length, 0, 'B 的条件不满足,不该推进');
  const hit = run(st, [ev('A', 0), ev('B', 1, { status: '500' })]);
  assert.strictEqual(hit.notices.length, 1);
});

test('晚到的第一步能开启新匹配 —— A A B 里第二个 A 也是起点', () => {
  // within=5:第一个 A 在 t=0,到 t=8 已超窗;第二个 A 在 t=6,B 在 t=8 仍在窗内
  const st = strategy({
    sequence: { steps: [{ event: 'A' }, { event: 'B' }], within_seconds: 5, by: ['c_ip'] },
  });
  const { notices } = run(st, [ev('A', 0), ev('A', 6), ev('B', 8)]);
  assert.strictEqual(notices.length, 1, '只保留最早那个起点的话这里会漏');
});

test('未完成匹配数有上限,超出时计数而不是静默丢弃', () => {
  const st = strategy({
    sequence: {
      steps: [{ event: 'A' }, { event: 'B' }],
      within_seconds: 3600,
      by: ['c_ip'],
      max_partial_per_key: 2,
    },
  });
  const { stats } = run(st, [ev('A', 0), ev('A', 1), ev('A', 2), ev('A', 3)]);
  assert.strictEqual(stats.sequencePartialDropped, 2,
    '第 3、4 个 A 各挤掉一个 —— 漏检必须可观测');
});

test('序列策略不受 trigger.event 影响', () => {
  // trigger 是给条件树用的。序列的每一步各自声明事件,写了 trigger 也不该改变行为
  const st = strategy({ trigger: { event: 'ZZZ' } });
  const { notices } = run(st, [ev('A', 0), ev('B', 1), ev('C', 2)]);
  assert.strictEqual(notices.length, 1);
});

test('by 为空时全局匹配 —— 不同主体会被串起来', () => {
  // schema 里写明了这通常不是想要的。测它是为了把行为钉死,而不是推荐这么用
  const st = strategy({
    sequence: { steps: [{ event: 'A' }, { event: 'B' }], within_seconds: 60, by: [] },
  });
  const { notices } = run(st, [
    { name: 'A', timestamp: T0, c_ip: '198.51.100.1' },
    { name: 'B', timestamp: T0 + 1000, c_ip: '198.51.100.2' },
  ]);
  assert.strictEqual(notices.length, 1);
});

test('告警带每一步的时间戳,能解释「为什么判它命中」', () => {
  const { notices } = run(strategy(), [ev('A', 0), ev('B', 1), ev('C', 2)]);
  const vv = notices[0].variable_values;
  assert.ok(vv, '必须有可解释性快照');
  const keys = Object.keys(vv);
  assert.strictEqual(keys.length, 3, `三步应各留一条痕迹,实际 ${keys.join(', ')}`);
});
