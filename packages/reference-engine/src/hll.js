'use strict';
/**
 * HyperLogLog —— 去重计数的近似模式实现。
 *
 * 参数按 docs/reference/operators.md:log2m = 14,即 16384 个 register,
 * 标准误差约 1.04/sqrt(16384) ≈ 0.8%。
 *
 * 注意:1.x 用的是 log2m = 9(512 register,误差约 4.6%)且与「前 20 个精确」
 * 的哈希集合混用,两者哈希函数还不同。2.0 不兼容该行为,这是已知的语义变更。
 */

class HyperLogLog {
  constructor(log2m = 14) {
    if (log2m < 4 || log2m > 20) throw new Error('log2m 应在 [4, 20] 之间');
    this.log2m = log2m;
    this.m = 1 << log2m;
    this.registers = new Uint8Array(this.m);
    this.alpha = HyperLogLog.alphaFor(this.m);
  }

  static alphaFor(m) {
    if (m === 16) return 0.673;
    if (m === 32) return 0.697;
    if (m === 64) return 0.709;
    return 0.7213 / (1 + 1.079 / m);
  }

  /** MurmurHash3 x86 32-bit —— 与规格中约定的哈希族一致 */
  static hash(key) {
    const data = Buffer.from(String(key), 'utf8');
    let h1 = 0;
    const c1 = 0xcc9e2d51; const c2 = 0x1b873593;
    const len = data.length;
    const nblocks = len >> 2;

    for (let i = 0; i < nblocks; i++) {
      let k1 = data.readUInt32LE(i * 4);
      k1 = Math.imul(k1, c1);
      k1 = (k1 << 15) | (k1 >>> 17);
      k1 = Math.imul(k1, c2);
      h1 ^= k1;
      h1 = (h1 << 13) | (h1 >>> 19);
      h1 = (Math.imul(h1, 5) + 0xe6546b64) | 0;
    }

    let k1 = 0;
    const tail = nblocks * 4;
    switch (len & 3) {
      case 3: k1 ^= data[tail + 2] << 16;              // falls through
      case 2: k1 ^= data[tail + 1] << 8;               // falls through
      case 1:
        k1 ^= data[tail];
        k1 = Math.imul(k1, c1);
        k1 = (k1 << 15) | (k1 >>> 17);
        k1 = Math.imul(k1, c2);
        h1 ^= k1;
    }

    h1 ^= len;
    h1 ^= h1 >>> 16;
    h1 = Math.imul(h1, 0x85ebca6b);
    h1 ^= h1 >>> 13;
    h1 = Math.imul(h1, 0xc2b2ae35);
    h1 ^= h1 >>> 16;
    return h1 >>> 0;
  }

  add(key) {
    const x = HyperLogLog.hash(key);
    const idx = x >>> (32 - this.log2m);
    const w = (x << this.log2m) >>> 0;
    const rank = w === 0 ? (32 - this.log2m) + 1 : Math.clz32(w) + 1;
    if (rank > this.registers[idx]) this.registers[idx] = rank;
  }

  count() {
    let sum = 0; let zeros = 0;
    for (let i = 0; i < this.m; i++) {
      const r = this.registers[i];
      sum += 2 ** -r;
      if (r === 0) zeros++;
    }
    let est = (this.alpha * this.m * this.m) / sum;

    // 小基数修正:线性计数
    if (est <= 2.5 * this.m && zeros > 0) {
      est = this.m * Math.log(this.m / zeros);
    }
    return Math.round(est);
  }

  merge(other) {
    if (other.log2m !== this.log2m) throw new Error('log2m 不同的 HLL 不能合并');
    for (let i = 0; i < this.m; i++) {
      if (other.registers[i] > this.registers[i]) this.registers[i] = other.registers[i];
    }
    return this;
  }
}

module.exports = { HyperLogLog };
