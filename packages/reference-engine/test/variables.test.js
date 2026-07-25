'use strict';
/**
 * 变量计算图的测试,对应 docs/reference/operators.md §1。
 *
 * 重点覆盖:事件继承链、依赖闭包构建、各类节点语义、以及策略通过变量
 * 引用取值的完整链路。
 */

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const { VariableGraph } = require('../src/variables');
const { EventModel } = require('../src/events');
const { Engine } = require('../src/engine');
const { crawler, credentialStuffing } = require('../src/scenario');

const SEEDS = path.resolve(__dirname, '..', '..', '..', 'seeds');
const load = (sub) => fs.readdirSync(path.join(SEEDS, sub))
  .filter((f) => f.endsWith('.json') && f !== 'index.json')
  .map((f) => JSON.parse(fs.readFileSync(path.join(SEEDS, sub, f), 'utf8')));

const VARIABLES = load('variables');
const EVENTS = load('events');
const STRATEGIES = load('strategies');
const EM = new EventModel(EVENTS);

// ---------------------------------------------------------------- 事件继承

test('事件继承:ACCOUNT_LOGIN 的祖先链包含 HTTP_DYNAMIC', () => {
  assert.deepStrictEqual(EM.chainOf('ACCOUNT_LOGIN'), ['ACCOUNT_LOGIN', 'HTTP_DYNAMIC']);
  assert.ok(EM.isA('ACCOUNT_LOGIN', 'HTTP_DYNAMIC'));
  assert.ok(!EM.isA('HTTP_DYNAMIC', 'ACCOUNT_LOGIN'), '继承是单向的');
});

test('事件继承:根事件的 source 指向自身,不构成环', () => {
  assert.deepStrictEqual(EM.chainOf('HTTP_DYNAMIC'), ['HTTP_DYNAMIC']);
});

test('事件继承:子事件合并父事件的全部字段', () => {
  const f = EM.fieldsOf('ACCOUNT_LOGIN');
  assert.ok(f.has('c_ip'), '应继承 HTTP_DYNAMIC 的基础字段');
  assert.ok(f.has('result'), '应含自身的增量字段');
});

// ---------------------------------------------------------------- 图构建

test('依赖闭包:只构建被引用变量及其上游', () => {
  const g = new VariableGraph(VARIABLES, new Set(['ip__visit_count__5m__rt']), EM);
  assert.ok(g.order.length > 1 && g.order.length < 10,
    `闭包应远小于全量 253,实际 ${g.order.length}`);
  assert.ok(g.order.includes('ip__visit_dynamic_count__5m__rt'), '应包含上游变量');
});

test('拓扑序:上游一定排在下游之前', () => {
  const g = new VariableGraph(VARIABLES, new Set(['ip__visit_count__5m__rt']), EM);
  const pos = new Map(g.order.map((n, i) => [n, i]));
  for (const [name, node] of g.nodes) {
    if (node.def.type === 'event') continue;
    for (const s of node.def.source || []) {
      if (!pos.has(s.name)) continue;
      assert.ok(pos.get(s.name) < pos.get(name), `${s.name} 应排在 ${name} 之前`);
    }
  }
});

test('全部 253 个变量可以构成无环图', () => {
  const g = new VariableGraph(VARIABLES, undefined, EM);
  assert.strictEqual(g.order.length, VARIABLES.length);
});

// ---------------------------------------------------------------- 节点语义

test('aggregate 节点:滑动窗口内按 key 累计', () => {
  const g = new VariableGraph(VARIABLES, new Set(['ip__visit_dynamic_count__5m__rt']), EM);
  const base = Date.UTC(2026, 6, 25, 2, 0, 0);
  for (let i = 0; i < 10; i++) {
    g.process({ name: 'HTTP_DYNAMIC', timestamp: base + i * 1000, c_ip: '198.51.100.5' });
  }
  const probe = { name: 'HTTP_DYNAMIC', timestamp: base + 10000, c_ip: '198.51.100.5' };
  assert.strictEqual(g.valueOf('ip__visit_dynamic_count__5m__rt', probe), 10);
  // 不同 key 互不影响
  assert.strictEqual(
    g.valueOf('ip__visit_dynamic_count__5m__rt', { ...probe, c_ip: '198.51.100.6' }), 0);
});

test('dual 节点:两个上游求和,任一为 null 则不产出', () => {
  const g = new VariableGraph(VARIABLES, new Set(['ip__visit_count__5m__rt']), EM);
  const base = Date.UTC(2026, 6, 25, 2, 0, 0);
  for (let i = 0; i < 5; i++) {
    g.process({ name: 'HTTP_DYNAMIC', timestamp: base + i * 1000, c_ip: '198.51.100.7' });
  }
  const probe = { name: 'HTTP_DYNAMIC', timestamp: base + 5000, c_ip: '198.51.100.7' };
  // 动态 5 次 + 静态 0 次
  assert.strictEqual(g.valueOf('ip__visit_count__5m__rt', probe), 5);
});

// ---------------------------------------------------------------- 端到端

test('端到端:引用变量的策略能被触发(爬虫场景)', () => {
  const s = STRATEGIES.filter((d) => d.name === 'IP大量访问');
  assert.strictEqual(s.length, 1);
  const events = crawler();
  const e = new Engine({ strategies: s, variables: VARIABLES, events: EVENTS });
  for (const ev of events) e.process(ev);
  const notices = e.finish(events[events.length - 1].timestamp);

  assert.strictEqual(notices.length, 1, '应恰好命中一次');
  assert.strictEqual(notices[0].key, '198.51.100.66', '应命中爬虫 IP');
  const vv = notices[0].variable_values['ip__visit_count__5m__rt'];
  assert.ok(vv, '告警应记录变量取值');
  assert.ok(vv.value > 300, `变量值应超过阈值,实际 ${vv.value}`);
});

test('端到端:事件继承使定义在父事件上的策略也能被登录事件触发', () => {
  // IP大量访问 触发于 HTTP_DYNAMIC;撞库场景只发 ACCOUNT_LOGIN
  const s = STRATEGIES.filter((d) => d.name === 'IP大量访问');
  const e = new Engine({ strategies: s, variables: VARIABLES, events: EVENTS });
  for (const ev of credentialStuffing()) e.process(ev);
  assert.ok(e.stats.evaluated > 0,
    'ACCOUNT_LOGIN 是 HTTP_DYNAMIC 的子事件,策略应被求值(阈值未达故不命中)');
});

test('全部 170 条策略 + 全部变量可完成一轮完整求值', () => {
  const events = crawler({ requests: 120 }).concat(credentialStuffing())
    .sort((a, b) => a.timestamp - b.timestamp);
  const e = new Engine({ strategies: STRATEGIES, variables: VARIABLES, events: EVENTS });
  for (const ev of events) e.process(ev);
  const notices = e.finish(events[events.length - 1].timestamp);
  assert.ok(Array.isArray(notices));
  assert.ok(e.graph, '应构建了变量图');
  assert.ok(e.graph.order.length > 20, `变量图应覆盖被引用的闭包,实际 ${e.graph.order.length}`);
});
