package cn.threathunter.nebula.console.privacy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 把主体标识换算成它在事件明细表里的存储形态。
 *
 * <p>引擎写库前对配置的列做了 HMAC,库里没有原值。要按主体查询或删除,就得用<b>同一把
 * 密钥</b>算出同一个哈希 —— HMAC 的确定性让这件事可行。
 *
 * <p><b>配置必须与引擎一致。</b>控制面与引擎读的是同一组环境变量
 * ({@code NEBULA_HMAC_KEY} / {@code NEBULA_PII_HMAC_COLUMNS}),两边不一致时算出的
 * 哈希对不上,表现为「导出为空、删除删不掉」而不会报错 —— 所以启动时把生效的列打出来。
 *
 * <p><b>密钥轮换之后就删不掉轮换前的数据了</b>,因为算不出当时的哈希。这是 HMAC 方案
 * 的固有代价,轮换前必须想清楚。
 */
@Component
public class SubjectHasher {

    /**
     * 默认保护列,必须与引擎的 {@code PiiHmac.DEFAULT_COLUMNS} 一致。
     *
     * <p>注解里的默认值没法引用这个常量(注解要求编译期常量字面量),所以测试用反射
     * 读注解字符串来比对 —— 两处不一致时引擎把 did 存成哈希、控制面按明文查,
     * 结果是「这个设备没有任何数据」,而不是任何报错。
     */
    static final String DEFAULT_COLUMNS = "uid,did,sid";

    private final byte[] key;
    private final Set<String> columns;

    public SubjectHasher(
            @Value("${nebula.pii.hmac-key:${NEBULA_HMAC_KEY:}}") String key,
            @Value("${nebula.pii.hmac-columns:${NEBULA_PII_HMAC_COLUMNS:uid,did,sid}}")
            String columns) {
        this.key = key == null || key.isBlank()
                ? null : key.getBytes(StandardCharsets.UTF_8);
        Set<String> set = new LinkedHashSet<>();
        for (String c : columns == null ? new String[0] : columns.split(",")) {
            String t = c.trim();
            if (!t.isEmpty()) {
                set.add(t);
            }
        }
        this.columns = set;
    }

    /** 该列在库里的存储形态:受保护则返回 HMAC,否则原值。 */
    public String storedValue(String column, String value) {
        if (key == null || value == null || value.isEmpty() || !columns.contains(column)) {
            return value;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    public Set<String> protectedColumns() {
        return Set.copyOf(columns);
    }

    public boolean keyConfigured() {
        return key != null;
    }
}
