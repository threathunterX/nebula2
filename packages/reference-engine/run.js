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
const { credentialStuffing } = require('./src/scenario');

const ROOT = path.resolve(__dirname, '..', '..');
const SEEDS = path.join(ROOT, 'seeds', 'strategies');

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

const args = process.argv.slice(2);
const only = args.includes('--strategy') ? args[args.indexOf('--strategy') + 1] : null;
const asJson = args.includes('--json');

const strategies = loadStrategies(only);
const events = credentialStuffing();
const engine = new Engine({ strategies });

const t0 = Date.now();
for (const e of events) engine.process(e);
const notices = engine.finish(events[events.length - 1].timestamp);
const ms = Date.now() - t0;

if (asJson) {
  console.log(JSON.stringify(notices, null, 2));
  process.exit(0);
}

console.log(`加载策略 ${strategies.length} 条,事件 ${events.length} 条,耗时 ${ms}ms`);
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
