#!/usr/bin/env node
'use strict';
/**
 * 导出全量策略的告警快照,供跨语言端到端对照。
 *
 * 加载全部 170 条策略与 253 个变量,用共享场景的事件序列驱动,把产出的告警
 * 归一化后导出。Java 侧跑同一批数据比对这份快照 —— 这是最高层级的对照:
 * 覆盖变量图、条件求值、内联计数器、告警去重与可解释性快照的全链路。
 */
const fs = require('fs');
const path = require('path');
const { Engine } = require('../src/engine');

const ROOT = path.resolve(__dirname, '..', '..', '..');
const load = (sub) => fs.readdirSync(path.join(ROOT, 'seeds', sub))
  .filter((f) => f.endsWith('.json') && f !== 'index.json')
  .map((f) => JSON.parse(fs.readFileSync(path.join(ROOT, 'seeds', sub, f), 'utf8')));

const spec = JSON.parse(fs.readFileSync(
  path.join(ROOT, 'tests', 'golden', 'vectors', 'notice-scenario.json'), 'utf8'));

const engine = new Engine({
  strategies: load('strategies'),
  variables: load('variables'),
  events: load('events'),
});
for (const e of spec.events) engine.process(e);
const notices = engine.finish(spec.events[spec.events.length - 1].timestamp);

// 归一化:只保留跨语言可比的字段,并按稳定顺序排序
const rows = notices.map((n) => ({
  strategy: n.strategy_name,
  key: n.key,
  check_type: n.check_type,
  scene: n.scene_name,
  decision: n.decision,
  test: n.test,
  ttl_ms: n.expire - n.timestamp,
})).sort((a, b) => (a.strategy + a.key).localeCompare(b.strategy + b.key));

process.stdout.write(JSON.stringify({ count: rows.length, notices: rows }, null, 2) + '\n');
