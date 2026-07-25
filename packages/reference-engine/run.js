#!/usr/bin/env node
'use strict';
/**
 * 参考引擎的运行入口。
 *
 *   node run.js                     跑撞库场景,输出告警摘要
 *   node run.js --strategy <名称>   只加载指定策略
 *   node run.js --json              输出完整告警 JSON
 */
const fs = require('fs');
const path = require('path');
const { Engine } = require('./src/engine');
const { credentialStuffing, crawler } = require('./src/scenario');

const ROOT = path.resolve(__dirname, '..', '..');
const SEEDS = path.join(ROOT, 'seeds', 'strategies');
const VARS = path.join(ROOT, 'seeds', 'variables');
const EVTS = path.join(ROOT, 'seeds', 'events');

function loadStrategies(only) {
  const out = [];
  for (const f of fs.readdirSync(SEEDS)) {
    if (!f.endsWith('.json') || f === 'index.json') continue;
    const d = JSON.parse(fs.readFileSync(path.join(SEEDS, f), 'utf8'));
    if (only && d.name !== only) continue;
    out.push(d);
  }
  return out;
}

const SCENARIOS = { 'credential-stuffing': credentialStuffing, crawler };

const args = process.argv.slice(2);
if (args.includes('--help') || args.includes('-h')) {
  console.log(`用法: node run.js [选项]

  --scenario <名称>   场景: ${Object.keys(SCENARIOS).join(' | ')}  (默认 credential-stuffing)
  --strategy <名称>   只加载指定策略
  --json              输出完整告警 JSON
  --help              显示本帮助`);
  process.exit(0);
}
const only = args.includes('--strategy') ? args[args.indexOf('--strategy') + 1] : null;
const asJson = args.includes('--json');

const strategies = loadStrategies(only);
const scenarioName = args.includes('--scenario') ? args[args.indexOf('--scenario') + 1] : 'credential-stuffing';
const scenario = SCENARIOS[scenarioName];
if (!scenario) {
  console.error(`未知场景: ${scenarioName}(可选: ${Object.keys(SCENARIOS).join(', ')})`);
  process.exit(1);
}
const events = scenario();
const variables = fs.readdirSync(VARS)
  .filter((f) => f.endsWith('.json') && f !== 'index.json')
  .map((f) => JSON.parse(fs.readFileSync(path.join(VARS, f), 'utf8')));
const eventDefs = fs.readdirSync(EVTS)
  .filter((f) => f.endsWith('.json') && f !== 'index.json')
  .map((f) => JSON.parse(fs.readFileSync(path.join(EVTS, f), 'utf8')));
const engine = new Engine({ strategies, variables, events: eventDefs });

const t0 = Date.now();
for (const e of events) engine.process(e);
const notices = engine.finish(events[events.length - 1].timestamp);
const ms = Date.now() - t0;

if (asJson) {
  console.log(JSON.stringify(notices, null, 2));
  process.exit(0);
}

console.log(`场景 ${scenarioName}:策略 ${strategies.length} 条、变量 ${variables.length} 个、事件 ${events.length} 条,耗时 ${ms}ms`);
if (engine.graph) console.log(`变量图节点 ${engine.graph.order.length} 个(按策略引用的依赖闭包构建)`);
console.log(`求值 ${engine.stats.evaluated} 次,命中 ${engine.stats.hits} 条,去重抑制 ${engine.stats.deduped} 条`);
console.log('');
const byStrategy = new Map();
for (const n of notices) {
  const k = n.strategy_name;
  if (!byStrategy.has(k)) byStrategy.set(k, []);
  byStrategy.get(k).push(n);
}
if (!byStrategy.size) { console.log('未产生告警'); process.exit(0); }
console.log('告警分布:');
for (const [name, list] of [...byStrategy.entries()].sort((a, b) => b[1].length - a[1].length)) {
  const keys = [...new Set(list.map((n) => n.key))];
  console.log(`  ${String(list.length).padStart(3)} 条  ${name}`);
  console.log(`         主体: ${keys.slice(0, 5).join(', ')}${keys.length > 5 ? ` 等 ${keys.length} 个` : ''}`);
}
console.log('');
console.log('告警样例(含可解释性快照):');
console.log(JSON.stringify(notices[0], null, 2));
