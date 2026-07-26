package cn.threathunter.nebula.engine.sink;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 事件明细落库前对个人标识列做 HMAC。
 *
 * <h2>为什么在这一层做</h2>
 *
 * 隐私设计的两条界限:{@code sensitive} 在采集端就地脱敏(原文不出客户网络边界),
 * {@code pii} <b>保持原值进入计算</b>、由存储层保护。
 *
 * <p>{@code pii} 不在采集端哈希是有原因的:{@code c_ip} 一旦被哈希,地理定位、
 * IP 信誉、跨维度关联全部失效,风控直接废掉。所以保护必须发生在**算完之后、写库之前**
 * —— 这一层。变量计算、策略判定拿到的仍是原值,落到磁盘上的不是。
 *
 * <h2>保护的是什么,不保护什么</h2>
 *
 * 威胁模型是<b>只读数据库权限的泄露</b>(拖库、误配的只读账号、备份外流),不是拿到
 * 运行中进程的内存。
 *
 * <p><b>只覆盖事件明细表。</b>告警表的 {@code subject_key} 不做 HMAC —— 运营处置告警时
 * 必须知道是哪个账号,还原不了就没法处置。告警表靠读取时的
 * <a href="../../../../../../../../docs/reference/api.md">分级脱敏</a>按角色控制。
 *
 * <p>这个划分是有意义的:事件明细是大表(全量流量、保留 30 天),一次导出是数百万行
 * 用户活动记录;告警表只含已被判定为风险的主体,量级小得多。保护大表大幅缩小暴露面,
 * 但<b>不等于「拖库什么都拿不到」</b>,不应这样宣称。
 *
 * <h2>默认只覆盖纯标识符</h2>
 *
 * 默认列是 {@code uid,did,sid} —— 它们只参与等值比较与分组,HMAC 后这些用法完全不受影响。
 *
 * <p>{@code c_ip} 与 {@code useragent} 虽然也标注为 {@code pii},<b>默认不做</b>:
 * <ul>
 *   <li>{@code c_ip} 的网段聚合是真实的风控手段(同一 C 段大量请求),HMAC 之后做不了;
 *       它还是 {@code LowCardinality} 列,HMAC 会让基数爆炸、压缩率大幅下降</li>
 *   <li>{@code useragent} 的分布分析同理</li>
 * </ul>
 * 需要更严的部署可以把它们加进配置 —— 代价是上述分析能力,这个取舍由部署方判断,
 * 不由本项目替它决定。
 */
public final class PiiHmac implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 纯标识符,只参与等值比较与分组,HMAC 后用法不受影响。 */
    public static final String DEFAULT_COLUMNS = "uid,did,sid";

    private final byte[] key;
    private final Set<String> columns;

    private PiiHmac(byte[] key, Set<String> columns) {
        this.key = key;
        this.columns = columns;
    }

    /** 不做任何 HMAC。 */
    public static PiiHmac disabled() {
        return new PiiHmac(null, Collections.emptySet());
    }

    /**
     * 从环境变量构建。
     *
     * <p>{@code NEBULA_PII_HMAC_COLUMNS} 为空字符串表示显式关闭;未设置时用默认列。
     *
     * <p><b>配置了列却没有密钥时启动失败,不静默降级为明文。</b>静默降级意味着运维
     * 以为数据受保护、实际没有 —— 这比明确的失败危险得多。
     */
    public static PiiHmac fromEnv() {
        String cols = System.getenv("NEBULA_PII_HMAC_COLUMNS");
        if (cols == null) {
            cols = DEFAULT_COLUMNS;
        }
        Set<String> set = new LinkedHashSet<>();
        for (String c : cols.split(",")) {
            String t = c.trim();
            if (!t.isEmpty()) {
                set.add(t);
            }
        }
        if (set.isEmpty()) {
            return disabled();
        }
        String secret = System.getenv("NEBULA_HMAC_KEY");
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "需要对 " + set + " 做 HMAC,但缺少 NEBULA_HMAC_KEY。"
                            + "设置该变量(见 deploy/compose/gen-env.sh),"
                            + "或把 NEBULA_PII_HMAC_COLUMNS 显式设为空串以关闭。");
        }
        return new PiiHmac(secret.getBytes(StandardCharsets.UTF_8), set);
    }

    public static PiiHmac of(String secret, Set<String> columns) {
        if (columns.isEmpty()) {
            return disabled();
        }
        // 与 fromEnv 一样,缺密钥必须明确失败 —— 否则 NPE 会在第一条事件写库时才出现,
        // 而那时错误信息与「忘了配密钥」看不出关系
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "需要对 " + columns + " 做 HMAC,但没有提供密钥");
        }
        return new PiiHmac(secret.getBytes(StandardCharsets.UTF_8), new LinkedHashSet<>(columns));
    }

    public boolean enabled() {
        return key != null && !columns.isEmpty();
    }

    public Set<String> columns() {
        return Collections.unmodifiableSet(columns);
    }

    /**
     * 若该列在保护范围内则返回 HMAC,否则原样返回。
     *
     * <p><b>空值原样返回。</b>对空串做 HMAC 会得到一个固定的非空值,让「这个字段没有值」
     * 和「这个字段的值恰好是空」在库里无法区分,并且给出一个可以被计数的假标识。
     */
    public String apply(String column, String value) {
        if (!enabled() || value == null || value.isEmpty() || !columns.contains(column)) {
            return value;
        }
        return hex(value);
    }

    private String hex(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // 这两个异常在 HmacSHA256 + 非空密钥下不可能发生;真发生了说明运行环境有问题,
            // 此时绝不能回退到明文
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }
}
