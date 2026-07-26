import { describe, expect, it } from 'vitest';
import { deriveOptions, getIn, missing, setIn } from './schema';
import strategySchema from '../../../packages/domain-schema/strategy.schema.json';

/**
 * 表单编辑器的两条性质。两条都属于「坏了也不报错」的那类。
 */

describe('从 schema 派生取值', () => {
  // 读的是真实的 packages/domain-schema/strategy.schema.json,不是手写的样例 ——
  // 这个测试的意义正是「界面能从真 schema 里读到取值」,喂假 schema 就白测了
  const derived = deriveOptions(strategySchema as never);

  it('七类取值全部读得到', () => {
    expect(missing(derived)).toEqual([]);
  });

  it('读到的就是 schema 里写的那些', () => {
    expect(derived.decision).toEqual(['accept', 'review', 'reject']);
    expect(derived.checkType).toEqual(['IP', 'USER', 'DeviceID', 'OrderID']);
    expect(derived.logical).toEqual(['and', 'or', 'not']);
    expect(derived.category).toContain('ORDER');
    expect(derived.comparison).toContain('!regex');
    expect(derived.operandKind).toEqual(
      expect.arrayContaining(['constant', 'event_field', 'variable', 'counter']));
  });

  it('schema 缺字段时报缺,不悄悄用默认值补上', () => {
    // 这是「取不到就让界面显示错误」的依据。若这里返回了兜底取值,
    // 界面会在 schema 拉取失败时照常显示一份过期的选项,而没有任何提示
    expect(missing(deriveOptions({} as never)).sort()).toEqual(
      ['category', 'checkType', 'comparison', 'decision', 'logical', 'operandKind', 'status']);
  });
});

describe('改动不得丢字段', () => {
  /**
   * 一条带着表单不认识的字段的策略。
   *
   * `source_1x` 是 1.x 迁移溯源,`action.handlers` / `action.checkpoints` 在 schema 里
   * 但表单没渲染。表单若按自身状态重建对象,这些会静默消失 —— 而 schema 校验只管
   * 「有的字段合法」,不管「原来有的字段还在」,所以保存会成功。
   */
  const strategy = {
    app: 'nebula',
    name: 'IP下单不支付',
    visible_name: 'IP 下单不支付',
    category: 'ORDER',
    score: 0,
    action: {
      decision: 'review',
      check_type: 'IP',
      check_value: 'c_ip',
      ttl: 3600,
      checkpoints: [],
      handlers: [{ type: 'notify', target: 'ops' }],
    },
    source_1x: { original_id: 4271, note: '阈值按 1.x 原值保留' },
    condition: {
      op: 'and',
      conditions: [
        {
          left: { kind: 'event_field', field: 'page' },
          op: '!regex',
          right: { kind: 'constant', value: '^\\s*$' },
        },
        {
          left: {
            kind: 'counter',
            counter: {
              algorithm: 'distinct_count', event: 'ORDER_SUBMIT',
              groupby: ['c_ip'], operand: ['order_id'], window: 1800,
              filter: { type: 'simple', object: 'page', operation: '!regex', value: '^\\s*$' },
            },
          },
          op: '>',
          right: { kind: 'constant', value: '4' },
        },
      ],
    },
  };

  it('改决策不动其它任何字段', () => {
    const next = setIn(strategy, ['action', 'decision'], 'reject');
    expect(next.action.decision).toBe('reject');
    expect(next.source_1x).toEqual(strategy.source_1x);
    expect(next.action.handlers).toEqual(strategy.action.handlers);
    expect(next.action.checkpoints).toEqual(strategy.action.checkpoints);
    expect(next.action.ttl).toBe(3600);
  });

  it('改嵌套在数组里的阈值,同层其它条件与计数器的过滤条件都还在', () => {
    const next = setIn(strategy, ['condition', 'conditions', 1, 'right', 'value'], '9');
    expect(getIn(next, ['condition', 'conditions', 1, 'right', 'value'])).toBe('9');
    // 同一个数组里的另一条
    expect(getIn(next, ['condition', 'conditions', 0])).toEqual(strategy.condition.conditions[0]);
    // 表单不渲染 filter,它必须原样留着
    expect(getIn(next, ['condition', 'conditions', 1, 'left', 'counter', 'filter']))
      .toEqual({ type: 'simple', object: 'page', operation: '!regex', value: '^\\s*$' });
    // operand 也不渲染
    expect(getIn(next, ['condition', 'conditions', 1, 'left', 'counter', 'operand']))
      .toEqual(['order_id']);
  });

  it('改计数器窗口不影响它的算法与分组', () => {
    const p = ['condition', 'conditions', 1, 'left', 'counter'];
    const next = setIn(strategy, [...p, 'window'], 600);
    expect(getIn(next, [...p, 'window'])).toBe(600);
    expect(getIn(next, [...p, 'algorithm'])).toBe('distinct_count');
    expect(getIn(next, [...p, 'groupby'])).toEqual(['c_ip']);
  });

  it('不改动原对象', () => {
    const before = JSON.stringify(strategy);
    setIn(strategy, ['action', 'decision'], 'reject');
    setIn(strategy, ['condition', 'conditions', 0, 'op'], '==');
    expect(JSON.stringify(strategy)).toBe(before);
  });

  it('连续多次改动逐次叠加,不互相覆盖', () => {
    let s: typeof strategy = strategy;
    s = setIn(s, ['action', 'decision'], 'reject');
    s = setIn(s, ['action', 'ttl'], 60);
    s = setIn(s, ['visible_name'], '改过的名字');
    expect(s.action.decision).toBe('reject');
    expect(s.action.ttl).toBe(60);
    expect(s.visible_name).toBe('改过的名字');
    expect(s.source_1x).toEqual(strategy.source_1x);
  });

  it('走一遍表单能改的全部字段后,未渲染的字段一个不少', () => {
    let s: Record<string, unknown> = strategy as unknown as Record<string, unknown>;
    for (const [path, v] of [
      [['visible_name'], 'x'], [['remark'], 'y'], [['category'], 'ACCOUNT'],
      [['tags'], ['a']], [['score'], 5], [['dedup_window'], 60],
      [['trigger', 'event'], 'ORDER_SUBMIT'], [['trigger', 'keys'], ['c_ip']],
      [['action', 'decision'], 'reject'], [['action', 'check_type'], 'USER'],
      [['action', 'check_value'], 'uid'], [['action', 'ttl'], 60],
      [['condition', 'op'], 'or'],
    ] as [(string | number)[], unknown][]) {
      s = setIn(s, path, v);
    }
    expect(s.source_1x).toEqual(strategy.source_1x);
    expect(getIn(s, ['action', 'handlers'])).toEqual(strategy.action.handlers);
    expect(getIn(s, ['action', 'checkpoints'])).toEqual([]);
    expect(getIn(s, ['condition', 'conditions', 1, 'left', 'counter', 'filter'])).toBeTruthy();
    // app/name 表单不渲染也不该动
    expect(s.app).toBe('nebula');
    expect(s.name).toBe('IP下单不支付');
  });
});
