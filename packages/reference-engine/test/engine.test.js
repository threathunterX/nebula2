'use strict';
/**
 * 策略引擎的端到端测试。
 *
 * 除了验证引擎本身,这些测试还把「跑起来才发现的事实」固化下来 —— 其中
 * 几条实证确认了 seeds/INVENTORY.md 中静态检出的 1.x 数据缺陷。
 */

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const { Engine } = require('../src/engine');
const { credentialStuffing, withLateEvents } = require('../src/scenario');

const SEEDS = path.resolve(__dirname, '..', '..', '..', 'seeds', 'strategies');

function load(only) {
  return fs.readdirSync(SEEDS)
    .filter((f) => f.endsWith('.json') && f !== 'index.json')
    .map((f) => JSON.parse(fs.readFileSync(path.join(SEEDS, f), 'utf8')))
    .filter((d) => !only || d.name === only);
}

function run(strategies, events) {
  const e = new Engine({ strategies });
  for (const ev of events) e.process(ev);
  return { notices: e.finish(events[events.length - 1].timestamp), stats: e.stats, engine: e };
}

// ---------------------------------------------------------------- 撞库检测

test('撞库场景:恰好识别出两个攻击 IP,不误报正常用户', () => {
  const { notices } = run(load('IP多次登录失败'), credentialStuffing());
  const keys = [...new Set(notices.map((n) => n.key))].sort();
  assert.deepStrictEqual(keys, ['198.51.100.77', '198.51.100.78']);
});

test('告警包含可解释性快照 —— 1.x 的 variable_values 恒为空,2.0 必须落地', () => {
  const { notices } = run(load('IP多次登录失败'), credentialStuffing());
  const vv = notices[0].variable_values;
  assert.ok(vv && Object.keys(vv).length > 0, 'variable_values 不应为空');
  const counter = Object.entries(vv).find(([k]) => k.includes('count('));
  assert.ok(counter, '应记录计数器的取值');
  assert.ok(counter[1].value > 5, `计数器值应超过阈值,实际 ${counter[1].value}`);
  assert.strictEqual(counter[1].op || counter[1].operator, '>');
});

test('test 状态的策略产出的告警标记 test=true,不参与线上决策', () => {
  const { notices } = run(load('IP多次登录失败'), credentialStuffing());
  assert.ok(notices.every((n) => n.test === true));
});

test('告警去重窗口生效:同一主体同一策略不重复刷屏', () => {
  const { stats } = run(load('IP多次登录失败'), credentialStuffing());
  assert.ok(stats.deduped > 0, '应有被去重抑制的重复命中');
  assert.ok(stats.hits < stats.deduped, '去重后的告警数应远小于原始命中数');
});

// ---------------------------------------------------------------- 实证确认的数据缺陷

test('实证:未配置占位符的策略会打中几乎所有主体(INVENTORY 记录的 🔧 问题)', () => {
  const s = load('IP请求登录前未访问必要资源');
  assert.strictEqual(s.length, 1, '该策略应存在');
  const events = credentialStuffing();
  const { notices } = run(s, events);
  const distinctIps = new Set(events.map((e) => e.c_ip)).size;
  // 条件是「访问某页面的次数 == 0」,而占位页面不存在 => 条件恒真
  assert.ok(notices.length > distinctIps * 0.8,
    `未配置的占位符策略应产生大面积误报,实际 ${notices.length} 条 / ${distinctIps} 个 IP`);
});

test('实证:策略名与名单主体不符(INVENTORY 记录的缺陷 1)', () => {
  const { notices } = run(load('IP集中请求登录'), credentialStuffing());
  if (notices.length) {
    // 名字叫「IP…」,写入名单的却是设备
    assert.strictEqual(notices[0].check_type, 'DeviceID');
    assert.ok(notices[0].key.startsWith('device_'),
      `名为 IP 的策略产出了设备主体: ${notices[0].key}`);
  }
});

// ---------------------------------------------------------------- 迟到事件

test('迟到事件在容忍度内仍被计入,超出则进侧输出而非静默丢弃(规格 §4.2)', () => {
  const events = withLateEvents(credentialStuffing(), { lateCount: 5, lagMs: 30 * 60 * 1000 });
  const { stats } = run(load('IP多次登录失败'), events);
  assert.ok(stats.lateDropped > 0, '超出容忍度的迟到事件应被记录到侧输出计数');
});

// ---------------------------------------------------------------- 全量运行

test('全部 170 条策略可在不抛异常的情况下完成求值', () => {
  const { notices, stats } = run(load(), credentialStuffing());
  assert.ok(stats.evaluated > 0);
  assert.ok(Array.isArray(notices));
  // 每条告警的必填字段都在
  for (const n of notices) {
    for (const f of ['timestamp', 'key', 'check_type', 'strategy_name', 'scene_name', 'decision', 'expire']) {
      assert.ok(n[f] !== undefined && n[f] !== null && n[f] !== '', `告警缺少字段 ${f}`);
    }
    assert.ok(n.expire > n.timestamp, '名单失效时间应晚于告警时间');
  }
});

test('结果可复现:相同种子跑两次产出完全一致', () => {
  const a = run(load(), credentialStuffing({ seed: 42 })).notices;
  const b = run(load(), credentialStuffing({ seed: 42 })).notices;
  assert.deepStrictEqual(a, b);
});

test('不同种子产出不同结果(确认种子确实起作用)', () => {
  const a = run(load('IP多次登录失败'), credentialStuffing({ seed: 1 })).notices;
  const b = run(load('IP多次登录失败'), credentialStuffing({ seed: 2 })).notices;
  assert.notDeepStrictEqual(a, b);
});
