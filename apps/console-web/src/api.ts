/**
 * 控制面 API 客户端。
 *
 * 认证走 HTTP Basic —— 控制面就是这么设计的(无状态、不签发会话 cookie)。
 *
 * **凭据只保存在内存里,不写 localStorage / sessionStorage。** 存进去意味着任何
 * 拿到 XSS 的人都能直接读走一个管理员口令,而刷新页面重新登录的代价远小于此。
 * 代价是刷新即登出,这是刻意的取舍。
 */

let auth: string | null = null;

export function setCredentials(username: string, password: string) {
  // btoa 只处理 latin1,口令含非 ASCII 时要先转 UTF-8 字节
  auth = 'Basic ' + btoa(String.fromCharCode(...new TextEncoder().encode(`${username}:${password}`)));
}

export function clearCredentials() {
  auth = null;
}

export function isAuthenticated() {
  return auth !== null;
}

export class ApiError extends Error {
  constructor(readonly status: number, message: string, readonly detail?: unknown) {
    super(message);
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (auth) headers.set('Authorization', auth);
  if (init.body) headers.set('Content-Type', 'application/json');

  const res = await fetch(path, { ...init, headers });
  const text = await res.text();
  const body = text ? safeJson(text) : null;

  if (!res.ok) {
    // 401 与 403 是两件不同的事,界面上要能区分:前者是没登录/口令错,
    // 后者是登录了但这个角色不该做这件事。混为一谈会让人反复重登。
    const serverMsg =
      body && typeof body === 'object' && 'error' in body
        ? String((body as Record<string, unknown>).error)
        : '';
    // 校验失败时服务端会一次给出全部问题,原样展示比只显示一句概述有用得多
    const problems =
      body && typeof body === 'object' && Array.isArray((body as Record<string, unknown>).problems)
        ? '\n· ' + ((body as Record<string, unknown>).problems as unknown[]).join('\n· ')
        : '';
    const fallback =
      res.status === 401
        ? '未认证或口令错误'
        : res.status === 403
          ? '当前角色无权执行此操作'
          : `请求失败(HTTP ${res.status})`;
    throw new ApiError(res.status, (serverMsg || fallback) + problems, body);
  }
  return body as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function qs(params: Record<string, string | number | boolean | undefined>) {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') u.set(k, String(v));
  }
  return u.toString();
}

// ---------------------------------------------------------------- 类型

export interface Stats {
  events: number;
  variables: number;
  strategies: number;
  tags: number;
  strategies_requiring_config: number;
  pii_variables: number;
  metadata_version: number;
}

export interface StrategySummary {
  name: string;
  visible_name: string;
  category: string;
  status: string;
  score: number;
  tags: string[];
  requires_config: boolean;
  version: number;
}

export interface Alert {
  notice_time: string;
  subject_key: string;
  check_type: string;
  strategy_name: string;
  scene_name: string;
  decision: string;
  risk_score: number;
  tags: string[];
  is_test: number;
  remark: string;
  variable_values: Record<string, string>;
}

export interface AlertPage {
  total: string | number;
  page: number;
  size: number;
  subject_masked: boolean;
  items: Alert[];
}

export interface TrendBucket {
  hour: string;
  scene_name: string;
  strategy_name: string;
  decision: string;
  notices: string | number;
  subjects: string | number;
}

export interface VariableSummary {
  name: string;
  module: string;
  dimension: string;
  status: string;
  sensitivity: string;
}

export interface StrategyDetail {
  name: string;
  /** 行版本,用于乐观并发。与 definition.version(领域模型版本)不是一回事。 */
  version: number;
  status: string;
  requires_config: boolean;
  definition: Record<string, unknown>;
}

export interface Me {
  username: string;
  roles: string[];
}

export interface UserRow {
  username: string;
  display_name: string;
  roles: string[];
  enabled: boolean;
  created_at: string;
}

export interface TokenRow {
  token_id: string;
  description: string;
  scopes: string[];
  allowed_cidrs: string[];
  enabled: boolean;
  expires_at: string;
  last_used_at: string;
  created_at: string;
}

/** 建账号与签发令牌的响应。明文字段只在这一次出现,之后服务端也拿不到。 */
export interface CreatedUser {
  username: string;
  roles: string[];
  password: string;
}

export interface IssuedToken {
  token_id: string;
  token: string;
  scopes: string[];
  allowed_cidrs: string[];
}

export interface Revision {
  version: number;
  status: string;
  changed_by: string;
  change_note: string;
  changed_at: string;
}

// ---------------------------------------------------------------- 接口

export const api = {
  stats: () => request<Stats>('/api/v2/stats'),

  strategies: (p: { category?: string; status?: string; limit?: number } = {}) =>
    request<StrategySummary[]>(`/api/v2/strategies?${qs(p)}`),

  strategy: (name: string) => request<StrategyDetail>(`/api/v2/strategies/${encodeURIComponent(name)}`),

  saveStrategy: (name: string, definition: unknown, expectedVersion: number, changeNote: string) =>
    request<{ name: string; version: number }>(`/api/v2/strategies/${encodeURIComponent(name)}`, {
      method: 'PUT',
      body: JSON.stringify({ definition, expected_version: expectedVersion, change_note: changeNote }),
    }),

  setStatus: (name: string, status: string) =>
    request<unknown>(`/api/v2/strategies/${encodeURIComponent(name)}/status`, {
      method: 'PUT',
      body: JSON.stringify({ status }),
    }),

  revisions: (name: string) =>
    request<{ revisions: Revision[] }>(`/api/v2/strategies/${encodeURIComponent(name)}/revisions`),

  variables: (p: { module?: string; sensitivity?: string; limit?: number } = {}) =>
    request<VariableSummary[]>(`/api/v2/variables?${qs(p)}`),

  alerts: (p: {
    from: string;
    to: string;
    scene?: string;
    strategy?: string;
    decision?: string;
    check_type?: string;
    subject?: string;
    include_test?: boolean;
    page?: number;
    size?: number;
  }) => request<AlertPage>(`/api/v2/alerts?${qs(p)}`),

  trend: (p: { from: string; to: string; strategy?: string; include_test?: boolean }) =>
    request<{ buckets: TrendBucket[] }>(`/api/v2/alerts/trend?${qs(p)}`),

  metadataVersion: () => request<{ version: number }>('/api/v2/metadata/version'),

  me: () => request<Me>('/api/v2/users/me'),

  /** 领域 schema,原样下发。界面从它派生取值,不在前端另抄一份。 */
  schema: (which: string) => request<Record<string, unknown>>(`/api/v2/schema/${which}`),

  users: () => request<{ users: UserRow[] }>('/api/v2/users'),

  createUser: (username: string, displayName: string, roles: string[]) =>
    request<CreatedUser>('/api/v2/users', {
      method: 'POST',
      body: JSON.stringify({ username, display_name: displayName, roles }),
    }),

  setUserEnabled: (username: string, enabled: boolean) =>
    request<unknown>(`/api/v2/users/${encodeURIComponent(username)}/enabled`, {
      method: 'PUT',
      body: JSON.stringify({ enabled }),
    }),

  resetPassword: (username: string) =>
    request<CreatedUser>(`/api/v2/users/${encodeURIComponent(username)}/password`, {
      method: 'POST',
      body: '{}',
    }),

  tokens: () => request<{ tokens: TokenRow[] }>('/api/v2/tokens'),

  issueToken: (description: string, scopes: string[], allowedCidrs: string[]) =>
    request<IssuedToken>('/api/v2/tokens', {
      method: 'POST',
      body: JSON.stringify({ description, scopes, allowed_cidrs: allowedCidrs }),
    }),

  revokeToken: (tokenId: string) =>
    request<unknown>(`/api/v2/tokens/${encodeURIComponent(tokenId)}`, { method: 'DELETE' }),
};
