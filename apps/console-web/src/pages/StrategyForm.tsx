import type { StrategySchema, Options } from '../schema';
import { describe, getIn, setIn } from '../schema';

/**
 * 策略的表单编辑器。
 *
 * <h2>只改动、不重建</h2>
 *
 * 每次编辑都是 `setIn(原对象, 路径, 新值)` —— 在原对象上打一个补丁,其余原样保留。
 * **不按表单状态重建对象。** 重建会静默丢掉表单不认识的字段(`source_1x`、
 * `action.handlers`、`action.checkpoints`,以及将来 schema 新增而界面还没跟上的),
 * 而 schema 校验只管「有的字段合法」,不管「原来有的字段还在」—— 丢了不会报错。
 *
 * <h2>表达不了的东西不假装能表达</h2>
 *
 * 条件是任意嵌套的 and/or/not 树。表单把**一层**逻辑组下的比较条件铺开可编辑,
 * 更深的嵌套与 CEL 表达式条件<b>只读展示</b>,并明确指向 JSON 视图。
 *
 * 这不是偷懒:路线图里写明了不做拖拽编排 —— 风控条件的表达力来自嵌套与算子语义,
 * 界面要么表达不了复杂逻辑,要么复杂到不如直接写。一个诚实的「这里请用 JSON」
 * 比一个会把嵌套压平的表单好得多,后者会静默改变策略语义。
 */
export default function StrategyForm({
  value, schema, options, onChange,
}: {
  value: Record<string, unknown>;
  schema: StrategySchema;
  options: Options;
  onChange: (next: Record<string, unknown>) => void;
}) {
  const set = (path: (string | number)[], v: unknown) => onChange(setIn(value, path, v));

  return (
    <div className="strategy-form">
      <Section title="基本">
        <Row label="展示名">
          <input value={str(value.visible_name)} onChange={(e) => set(['visible_name'], e.target.value)} />
        </Row>
        <Row label="说明" hint={describe(schema, 'remark')}>
          <input value={str(value.remark)} onChange={(e) => set(['remark'], e.target.value)} />
        </Row>
        <Row label="风险场景" hint={describe(schema, 'category')}>
          <Select value={str(value.category)} options={options.category}
                  onChange={(v) => set(['category'], v)} />
        </Row>
        <Row label="风险标签" hint="逗号分隔">
          <input
            value={arr(value.tags).join(', ')}
            onChange={(e) => set(['tags'],
              e.target.value.split(',').map((t) => t.trim()).filter(Boolean))}
          />
        </Row>
        <Row label="权重" hint={describe(schema, 'score')}>
          <input type="number" value={num(value.score)}
                 onChange={(e) => set(['score'], intOr(e.target.value, 0))} />
        </Row>
        <Row label="去重窗口(秒)" hint={describe(schema, 'dedup_window')}>
          <input type="number" value={num(value.dedup_window)}
                 onChange={(e) => set(['dedup_window'], intOr(e.target.value, 0))} />
        </Row>
      </Section>

      <Section title="触发">
        <Row label="事件" hint={describe(schema, 'trigger', 'event')}>
          <input value={str(getIn(value, ['trigger', 'event']))}
                 onChange={(e) => set(['trigger', 'event'], e.target.value)} />
        </Row>
        <Row label="触发键" hint="逗号分隔,需与被引用变量的分组键一一对应">
          <input
            value={arr(getIn(value, ['trigger', 'keys'])).join(', ')}
            onChange={(e) => set(['trigger', 'keys'],
              e.target.value.split(',').map((t) => t.trim()).filter(Boolean))}
          />
        </Row>
      </Section>

      <Section title="处置">
        <Row label="决策" hint={describe(schema, 'action', 'decision')}>
          <Select value={str(getIn(value, ['action', 'decision']))} options={options.decision}
                  onChange={(v) => set(['action', 'decision'], v)} />
        </Row>
        <Row label="名单类型" hint={describe(schema, 'action', 'check_type')}>
          <Select value={str(getIn(value, ['action', 'check_type']))} options={options.checkType}
                  onChange={(v) => set(['action', 'check_type'], v)} />
        </Row>
        <Row label="主体取值字段" hint={describe(schema, 'action', 'check_value')}>
          <input value={str(getIn(value, ['action', 'check_value']))}
                 onChange={(e) => set(['action', 'check_value'], e.target.value)} />
        </Row>
        <Row label="名单有效期(秒)" hint={describe(schema, 'action', 'ttl')}>
          <input type="number" value={num(getIn(value, ['action', 'ttl']))}
                 onChange={(e) => set(['action', 'ttl'], intOr(e.target.value, 1))} />
        </Row>
      </Section>

      <Section title="条件">
        <Condition node={value.condition} path={['condition']} options={options} set={set} />
      </Section>
    </div>
  );
}

// ---------------------------------------------------------------- 条件

function Condition({
  node, path, options, set, depth = 0,
}: {
  node: unknown;
  path: (string | number)[];
  options: Options;
  set: (p: (string | number)[], v: unknown) => void;
  depth?: number;
}) {
  if (!node || typeof node !== 'object') {
    return <p className="note">(没有条件)</p>;
  }
  const n = node as Record<string, unknown>;

  // 逻辑组:and / or / not
  if (Array.isArray(n.conditions)) {
    const op = str(n.op);
    // 只在最外层允许改 and/or —— 深层嵌套的语义改动风险大,交给 JSON 视图
    return (
      <div className={depth === 0 ? '' : 'nested'}>
        <div className="cond-op">
          {depth === 0 ? (
            <Select value={op} options={options.logical} onChange={(v) => set([...path, 'op'], v)} />
          ) : (
            <code>{op}</code>
          )}
          <span className="note">
            {op === 'and' ? '以下全部满足' : op === 'or' ? '以下任一满足' : '以下不满足'}
          </span>
        </div>
        <ol className="cond-list">
          {(n.conditions as unknown[]).map((c, i) => (
            <li key={i}>
              <Condition node={c} path={[...path, 'conditions', i]}
                         options={options} set={set} depth={depth + 1} />
            </li>
          ))}
        </ol>
      </div>
    );
  }

  // CEL 表达式条件:表单不解析表达式,只让人改字符串
  if (typeof n.cel === 'string') {
    return (
      <div className="cond-leaf">
        <span className="note">CEL 表达式</span>
        <input className="mono" value={n.cel} onChange={(e) => set([...path, 'cel'], e.target.value)} />
      </div>
    );
  }

  // 二元比较
  if (n.left !== undefined && typeof n.op === 'string') {
    return (
      <div className="cond-leaf">
        <Operand node={n.left} path={[...path, 'left']} set={set} />
        <Select value={n.op} options={options.comparison} onChange={(v) => set([...path, 'op'], v)} />
        <Operand node={n.right} path={[...path, 'right']} set={set} />
      </div>
    );
  }

  // 认不出来的形态:原样展示,不猜
  return (
    <div className="cond-leaf">
      <span className="note">此条件的形态表单无法编辑,请用 JSON 视图:</span>
      <code className="mono">{JSON.stringify(node)}</code>
    </div>
  );
}

/**
 * 操作数。
 *
 * 常量与事件字段可以直接改;变量引用与内联计数器只暴露**最常改的那一两个数字**
 * (窗口、算子的操作对象),其余只读展示 —— 调阈值和调窗口是日常操作,重新定义
 * 一个计数器不是。
 */
function Operand({
  node, path, set,
}: {
  node: unknown;
  path: (string | number)[];
  set: (p: (string | number)[], v: unknown) => void;
}) {
  if (node === undefined || node === null) {
    return <span className="note">—</span>;
  }
  const n = node as Record<string, unknown>;

  if (n.kind === 'constant') {
    return (
      <input className="operand" value={str(n.value)}
             onChange={(e) => set([...path, 'value'], e.target.value)} />
    );
  }
  if (n.kind === 'event_field') {
    return (
      <span className="operand-ro">
        事件字段
        <input className="mono operand" value={str(n.field)}
               onChange={(e) => set([...path, 'field'], e.target.value)} />
      </span>
    );
  }
  if (n.kind === 'variable') {
    return (
      <span className="operand-ro">
        变量 <code className="mono">{str(n.variable)}</code>
        {arr(n.keys).length > 0 && <span className="note"> by {arr(n.keys).join(', ')}</span>}
      </span>
    );
  }
  if (n.kind === 'counter') {
    const c = (n.counter ?? {}) as Record<string, unknown>;
    return (
      <span className="operand-ro">
        <code className="mono">{str(c.algorithm)}</code>
        <span className="note"> of </span>
        <code className="mono">{str(c.event)}</code>
        <span className="note"> by {arr(c.groupby).join(', ') || '—'}</span>
        <label className="inline">
          窗口
          <input type="number" className="operand-num" value={num(c.window)}
                 onChange={(e) => set([...path, 'counter', 'window'], intOr(e.target.value, 1))} />
          秒
        </label>
        {/* 过滤条件与操作对象不在这里改 —— 那是重新定义这个计数器,不是调参数 */}
        {c.filter !== undefined && <span className="note"> ·(带过滤条件,详见 JSON)</span>}
      </span>
    );
  }
  return <code className="mono">{JSON.stringify(node)}</code>;
}

// ---------------------------------------------------------------- 小组件

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <fieldset className="form-section">
      <legend>{title}</legend>
      {children}
    </fieldset>
  );
}

function Row({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <div className="form-row">
      <label>{label}</label>
      <div className="form-field">
        {children}
        {hint && <p className="note">{hint}</p>}
      </div>
    </div>
  );
}

function Select({
  value, options, onChange,
}: { value: string; options: string[]; onChange: (v: string) => void }) {
  // 当前值不在 schema 的取值里时也要能显示出来 —— 否则 select 会静默跳到第一项,
  // 把一个「数据与 schema 不一致」的问题变成一次「谁把决策改了」的事故
  const opts = options.includes(value) || !value ? options : [value, ...options];
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)}>
      {opts.map((o) => (
        <option key={o} value={o}>
          {o}{options.includes(o) ? '' : ' (不在 schema 取值内)'}
        </option>
      ))}
    </select>
  );
}

const str = (v: unknown) => (v === null || v === undefined ? '' : String(v));
const num = (v: unknown) => (typeof v === 'number' ? String(v) : str(v));
const arr = (v: unknown): string[] => (Array.isArray(v) ? v.map(String) : []);
const intOr = (s: string, fallback: number) => {
  const n = parseInt(s, 10);
  return Number.isFinite(n) ? n : fallback;
};
