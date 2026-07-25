'use strict';
/**
 * 事件模型与继承链。
 *
 * 事件以 HTTP_DYNAMIC 为根做**单继承**:ACCOUNT_LOGIN 的父事件是
 * HTTP_DYNAMIC,因此一条登录事件同时也是一条动态请求事件。凡是按事件名
 * 匹配的地方(变量的 source、策略的 trigger)都必须考虑整条继承链,否则
 * 定义在父事件上的变量与策略永远不会被触发。
 */

class EventModel {
  constructor(defs) {
    this.defs = new Map(defs.map((d) => [d.name, d]));
  }

  /** 事件自身 + 全部祖先,由近及远 */
  chainOf(name) {
    const out = [];
    let cur = name;
    const guard = new Set();
    while (cur && !guard.has(cur)) {
      guard.add(cur);
      out.push(cur);
      const d = this.defs.get(cur);
      const src = d && d.source && d.source[0];
      // 根事件的 source 指向自身,到此为止
      cur = src && src.name !== cur ? src.name : null;
    }
    return out;
  }

  /** 事件 name 是否可视为 target(自身或其祖先) */
  isA(name, target) {
    return this.chainOf(name).includes(target);
  }

  /** 合并父事件属性后的完整字段集(用于校验事件是否携带必需字段) */
  fieldsOf(name) {
    const fields = new Map();
    for (const n of this.chainOf(name).reverse()) {
      const d = this.defs.get(n);
      for (const p of (d && d.properties) || []) fields.set(p.name, p);
    }
    return fields;
  }
}

module.exports = { EventModel };
