'use strict';
/**
 * 合成事件生成器。
 *
 * 硬性要求(见 CONTRIBUTING.md 与 tests/golden/README.md):
 *   - 全部数据合成,不含任何真实流量
 *   - 域名用 example.com 系,IP 用 RFC 5737 文档专用段
 *   - 固定随机种子,保证可复现 —— 这是 golden 基线能固化的前提
 */

/** 确定性伪随机数(xorshift32),避免依赖 Math.random */
function makeRandom(seed = 20260725) {
  let x = seed >>> 0;
  return () => {
    x ^= x << 13; x >>>= 0;
    x ^= x >> 17;
    x ^= x << 5; x >>>= 0;
    return x / 0x100000000;
  };
}

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
  + '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

function baseEvent(name, ts, over = {}) {
  return {
    name,
    timestamp: ts,
    id: `evt-${ts}-${Math.floor(ts % 100000)}`,
    host: 'shop.example.com',
    page: '/api/login',
    uri_stem: '/api/login',
    method: 'POST',
    status: 200,
    useragent: UA,
    c_ip: '198.51.100.1',
    s_ip: '203.0.113.1',
    geo_province: '上海市',
    geo_city: '上海市',
    ...over,
  };
}

/**
 * 场景:撞库攻击。
 *
 * 背景流量  —— 正常用户零散登录,基本都成功
 * 攻击流量  —— 少数几个 IP 在短时间内高频尝试大量账号,绝大多数失败
 *
 * 预期:攻击 IP 命中「IP多次登录失败」(10 分钟内失败 > 5 次);
 *      正常用户不应命中(否则就是误报)。
 */
function credentialStuffing({
  seed = 20260725,
  startTs = Date.UTC(2026, 6, 25, 2, 0, 0),   // 当地时间 10:00
  normalUsers = 40,
  attackerIps = ['198.51.100.77', '198.51.100.78'],
  attemptsPerAttacker = 60,
} = {}) {
  const rnd = makeRandom(seed);
  const events = [];

  // ---- 背景:正常用户,10 分钟内零散登录,95% 成功
  for (let i = 0; i < normalUsers; i++) {
    const ts = startTs + Math.floor(rnd() * 600000);
    const ok = rnd() > 0.05;
    events.push(baseEvent('ACCOUNT_LOGIN', ts, {
      c_ip: `198.51.100.${10 + (i % 50)}`,
      uid: `user_${String(1000 + i)}`,
      did: `device_${String(2000 + i)}`,
      result: ok ? 'T' : 'F',
    }));
  }

  // ---- 攻击:每个 IP 高频尝试不同账号,90% 失败
  attackerIps.forEach((ip, idx) => {
    for (let i = 0; i < attemptsPerAttacker; i++) {
      const ts = startTs + idx * 1000 + i * 4000 + Math.floor(rnd() * 500);
      events.push(baseEvent('ACCOUNT_LOGIN', ts, {
        c_ip: ip,
        uid: `victim_${String(5000 + i)}`,
        did: `device_${String(9000 + idx)}`,
        result: rnd() > 0.1 ? 'F' : 'T',
      }));
    }
  });

  // 按事件时间排序,模拟有序到达
  events.sort((a, b) => a.timestamp - b.timestamp);
  return events;
}

/** 场景:少量迟到事件,用于验证 allowedLateness 与侧输出 */
function withLateEvents(events, { lateCount = 5, lagMs = 5 * 60 * 1000 } = {}) {
  const out = events.slice();
  const last = events[events.length - 1];
  for (let i = 0; i < lateCount; i++) {
    out.push(baseEvent('ACCOUNT_LOGIN', last.timestamp - lagMs - i * 1000, {
      c_ip: '198.51.100.99',
      uid: `late_${i}`,
      did: 'device_late',
      result: 'F',
    }));
  }
  return out;   // 故意不排序:迟到事件排在最后到达
}

module.exports = { credentialStuffing, withLateEvents, makeRandom, baseEvent };
