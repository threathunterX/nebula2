package cn.threathunter.nebula.engine.operator;

import java.nio.charset.StandardCharsets;

/**
 * HyperLogLog —— 去重计数的近似模式。
 *
 * <p>参数按 {@code docs/reference/operators.md} §2.5:log2m = 14,即 16384 个
 * register,标准误差约 1.04/sqrt(16384) ≈ 0.8%。
 *
 * <p>1.x 用的是 log2m = 9(512 register,误差约 4.6%),且与「前 20 个精确」的
 * 哈希集合混用、两段用不同哈希函数。2.0 不兼容该行为,这是已知的语义变更。
 *
 * <p>算法出自 Flajolet 等人 2007 年的论文,MurmurHash3 由 Austin Appleby 置于
 * 公有领域。出处声明见仓库根目录的 NOTICE。
 */
public final class HyperLogLog {

    private final int log2m;
    private final int m;
    private final byte[] registers;
    private final double alpha;

    public HyperLogLog(int log2m) {
        if (log2m < 4 || log2m > 20) {
            throw new IllegalArgumentException("log2m 应在 [4, 20] 之间");
        }
        this.log2m = log2m;
        this.m = 1 << log2m;
        this.registers = new byte[m];
        this.alpha = alphaFor(m);
    }

    private static double alphaFor(int m) {
        return switch (m) {
            case 16 -> 0.673;
            case 32 -> 0.697;
            case 64 -> 0.709;
            default -> 0.7213 / (1 + 1.079 / m);
        };
    }

    /** MurmurHash3 x86 32-bit。与 JS 参考实现逐位一致。 */
    static int hash(String key) {
        byte[] data = key.getBytes(StandardCharsets.UTF_8);
        int h1 = 0;
        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;
        int len = data.length;
        int nblocks = len >> 2;

        for (int i = 0; i < nblocks; i++) {
            int k1 = (data[i * 4] & 0xff)
                    | ((data[i * 4 + 1] & 0xff) << 8)
                    | ((data[i * 4 + 2] & 0xff) << 16)
                    | ((data[i * 4 + 3] & 0xff) << 24);
            k1 *= c1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= c2;
            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int k1 = 0;
        int tail = nblocks * 4;
        switch (len & 3) {
            case 3:
                k1 ^= (data[tail + 2] & 0xff) << 16;
                // fall through
            case 2:
                k1 ^= (data[tail + 1] & 0xff) << 8;
                // fall through
            case 1:
                k1 ^= (data[tail] & 0xff);
                k1 *= c1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= c2;
                h1 ^= k1;
                break;
            default:
                break;
        }

        h1 ^= len;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        return h1;
    }

    public void add(String key) {
        int x = hash(key);
        int idx = x >>> (32 - log2m);
        int w = x << log2m;
        int rank = w == 0 ? (32 - log2m) + 1 : Integer.numberOfLeadingZeros(w) + 1;
        if (rank > registers[idx]) {
            registers[idx] = (byte) rank;
        }
    }

    public long count() {
        double sum = 0;
        int zeros = 0;
        for (byte r : registers) {
            sum += Math.pow(2, -r);
            if (r == 0) {
                zeros++;
            }
        }
        double est = (alpha * m * m) / sum;
        // 小基数修正:线性计数
        if (est <= 2.5 * m && zeros > 0) {
            est = m * Math.log((double) m / zeros);
        }
        return Math.round(est);
    }

    /** 导出 register 数组,供 Checkpoint 使用。 */
    public java.util.ArrayList<Number> registersCopy() {
        java.util.ArrayList<Number> out = new java.util.ArrayList<>(registers.length + 1);
        out.add(log2m);
        for (byte b : registers) {
            out.add(b);
        }
        return out;
    }

    /** 从 register 数组恢复。恢复后的基数估计与快照时完全一致。 */
    public static HyperLogLog fromRegisters(java.util.List<Number> data) {
        int log2m = data.get(0).intValue();
        HyperLogLog h = new HyperLogLog(log2m);
        for (int i = 1; i < data.size(); i++) {
            h.registers[i - 1] = data.get(i).byteValue();
        }
        return h;
    }

    public HyperLogLog merge(HyperLogLog other) {
        if (other.log2m != this.log2m) {
            throw new IllegalArgumentException("log2m 不同的 HLL 不能合并");
        }
        for (int i = 0; i < m; i++) {
            if (other.registers[i] > registers[i]) {
                registers[i] = other.registers[i];
            }
        }
        return this;
    }
}
