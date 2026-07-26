#!/usr/bin/env node
'use strict';
/**
 * 导出变量图的计算快照,供跨语言对照。
 *
 * 用同一批合成事件驱动变量图,把指定变量在各 key 上的最终值导出为 JSON。
 * Java 侧读同一批事件、跑同一批变量,比对这份快照 —— 两套实现的变量计算语义
 * 因此可以逐值核对,而不只是逐算子。这覆盖了图的传播、剪枝、按 key 分槽与
 * 事件继承链匹配。
 */
const fs = require('fs');
const path = require('path');
const { VariableGraph } = require('../src/variables');
const { EventModel } = require('../src/events');

const ROOT = path.resolve(__dirname, '..', '..', '..');
const load = (sub) => fs.readdirSync(path.join(ROOT, 'seeds', sub))
  .filter((f) => f.endsWith('.json') && f !== 'index.json')
  .map((f) => JSON.parse(fs.readFileSync(path.join(ROOT, 'seeds', sub, f), 'utf8')));

const spec = JSON.parse(fs.readFileSync(
  path.join(ROOT, 'tests', 'golden', 'vectors', 'graph-scenario.json'), 'utf8'));

const em = new EventModel(load('events'));
const graph = new VariableGraph(load('variables'), new Set(spec.variables), em);

for (const e of spec.events) {
  graph.process(e);
}

const out = {};
for (const v of spec.variables) {
  out[v] = {};
  for (const probe of spec.probes) {
    out[v][probe.__label] = graph.valueOf(v, { ...probe, timestamp: spec.probeTs });
  }
}
process.stdout.write(JSON.stringify({ values: out }, null, 2) + '\n');
