/**
 * 从领域 schema 派生界面需要的取值,而不是在前端另抄一份。
 *
 * schema 由 `GET /api/v2/schema/strategy` 与 `/enums` 原样下发,与服务端校验用的是
 * 同一份文件。前端抄一份的后果不是编译错误,而是**界面允许的和服务端接受的不一样**:
 * schema 加了个算子界面选不到,删了个算子界面还能选中然后保存时 400。两种都表现为
 * 「这个功能好像坏了」,而没有任何地方会报警。
 *
 * 取不到时**不回退到硬编码的默认值** —— 那等于偷偷把抄的那份又放回来了,而且只在
 * schema 拉取失败时生效,是最难发现的一种不一致。取不到就让界面显示错误。
 */

export interface StrategySchema {
  properties?: Record<string, SchemaNode>;
  $defs?: Record<string, SchemaNode>;
}

interface SchemaNode {
  type?: string | string[];
  enum?: string[];
  const?: string;
  description?: string;
  properties?: Record<string, SchemaNode>;
  required?: string[];
  items?: SchemaNode;
  oneOf?: SchemaNode[];
  minimum?: number;
}

/** 沿 `properties` 逐层取节点。任一段缺失返回 undefined,不抛。 */
function prop(root: StrategySchema, ...path: string[]): SchemaNode | undefined {
  let node: SchemaNode | undefined = root as unknown as SchemaNode;
  for (const seg of path) {
    node = node?.properties?.[seg];
  }
  return node;
}

export interface Options {
  /** 风险场景 */
  category: string[];
  /** 策略生命周期 */
  status: string[];
  /** 处置决策 */
  decision: string[];
  /** 名单主体类型 */
  checkType: string[];
  /** 二元比较算子 */
  comparison: string[];
  /** 逻辑算子 */
  logical: string[];
  /** 操作数种类 */
  operandKind: string[];
}

/** 哪些取值没能从 schema 里读到。非空即表示界面不该按猜测继续。 */
export function missing(o: Partial<Options>): string[] {
  const need: (keyof Options)[] = [
    'category', 'status', 'decision', 'checkType', 'comparison', 'logical', 'operandKind',
  ];
  return need.filter((k) => !o[k] || o[k]!.length === 0);
}

export function deriveOptions(s: StrategySchema): Partial<Options> {
  const defs = s.$defs ?? {};
  const operandKinds = (defs.operand?.oneOf ?? [])
    .map((v) => v.properties?.kind?.const)
    .filter((v): v is string => typeof v === 'string');

  return {
    category: prop(s, 'category')?.enum,
    status: prop(s, 'status')?.enum,
    decision: prop(s, 'action', 'decision')?.enum,
    checkType: prop(s, 'action', 'check_type')?.enum,
    comparison: defs.comparison?.properties?.op?.enum,
    logical: defs.logical?.properties?.op?.enum,
    operandKind: operandKinds.length > 0 ? operandKinds : undefined,
  };
}

/** 字段说明:直接用 schema 里写的 description,不在界面上另写一句。 */
export function describe(s: StrategySchema, ...path: string[]): string {
  return prop(s, ...path)?.description ?? '';
}

// ---------------------------------------------------------------- 不丢字段的改写

/**
 * 在**不触碰其它任何字段**的前提下改一个路径上的值。
 *
 * <p>这是整个表单编辑器最要紧的一条:表单只渲染它认识的字段,但保存的必须是
 * 「原对象 + 这次改动」,而不是「按表单状态重建一个对象」。后者会静默丢掉表单
 * 不认识的字段 —— 比如 `source_1x`(1.x 迁移溯源)、`action.handlers`、
 * `action.checkpoints`,以及将来 schema 新增而界面还没跟上的任何字段。
 *
 * <p>丢字段不会报错:schema 校验只管「有的字段合法」,不管「原来有的字段还在」。
 * 表现是某天有人发现迁移溯源信息不见了,而没人知道是哪次编辑弄的。
 */
export function setIn<T>(obj: T, path: (string | number)[], value: unknown): T {
  if (path.length === 0) return value as T;
  const [head, ...rest] = path;
  if (Array.isArray(obj)) {
    const copy = obj.slice();
    copy[head as number] = setIn(copy[head as number], rest, value);
    return copy as unknown as T;
  }
  const src = (obj ?? {}) as Record<string, unknown>;
  return {
    ...src,
    [head as string]: setIn(src[head as string], rest, value),
  } as unknown as T;
}

/** 读一个路径上的值,任一段缺失返回 undefined。 */
export function getIn(obj: unknown, path: (string | number)[]): unknown {
  let cur: unknown = obj;
  for (const seg of path) {
    if (cur === null || cur === undefined) return undefined;
    cur = (cur as Record<string | number, unknown>)[seg];
  }
  return cur;
}
