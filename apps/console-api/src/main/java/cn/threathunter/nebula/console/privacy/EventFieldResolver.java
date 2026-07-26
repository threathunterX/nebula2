package cn.threathunter.nebula.console.privacy;

import cn.threathunter.nebula.console.store.ClickHouseClient;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 把领域模型里的事件字段名解析成一段可用于 WHERE 的 SQL 表达式。
 *
 * <p>事件表只给高频字段单独建了列,其余进 {@code attrs} 这个 Map —— 17 类事件字段
 * 各异,不为每类堆稀疏列。所以 {@code uid} 是列,而 {@code order_id} 是
 * {@code attrs['order_id']};按列去查后者会直接报「未知标识符」。
 *
 * <p><b>物理列清单向 ClickHouse 本身要,不在这里抄一份。</b>引擎里有一份
 * {@code ClickHouseRows.PROMOTED},控制面再抄一份就会漂移:以后把某个字段提成
 * 真列,漏改这边就变成查 {@code attrs} 里一个永远不存在的键 —— 返回空,不报错。
 * 问库拿到的答案永远是当下正确的。
 *
 * <p>结果缓存到进程内。表结构不会在运行期变,而每次导出都去查一次 system.columns
 * 是没必要的往返;真变了(比如升级加列),重启后即刻生效。
 */
@Component
public class EventFieldResolver {

    private final ClickHouseClient clickhouse;
    private final Map<String, Set<String>> cache = new ConcurrentHashMap<>();

    public EventFieldResolver(ClickHouseClient clickhouse) {
        this.clickhouse = clickhouse;
    }

    /** 某张表的物理列。 */
    public Set<String> columnsOf(String table) throws IOException {
        Set<String> cached = cache.get(table);
        if (cached != null) {
            return cached;
        }
        Set<String> found = new HashSet<>();
        for (Map<String, Object> row : clickhouse.query(
                "SELECT name FROM system.columns WHERE database = 'nebula' "
                        + "AND table = {t:String}", Map.of("t", table))) {
            found.add(String.valueOf(row.get("name")));
        }
        if (found.isEmpty()) {
            // 空结果只可能是库不对或表没建 —— 不缓存,也不当成「没有列」
            // 继续走 attrs 分支,那会让每次导出都静默返回空
            throw new IOException("读不到 nebula." + table + " 的列定义");
        }
        cache.put(table, found);
        return found;
    }

    private Set<String> columns() throws IOException {
        return columnsOf("events");
    }

    /**
     * 该事件字段在 WHERE 中的写法。
     *
     * <p>字段名来自领域 schema 而非请求,不存在注入 —— 但仍然做一次白名单校验,
     * 免得将来有人把用户输入接到这里。
     */
    public String expression(String field) throws IOException {
        if (!field.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("非法的字段名: " + field);
        }
        return columns().contains(field) ? field : "attrs['" + field + "']";
    }

    /** 该字段是否为物理列 —— 只有物理列才可能被引擎做过 HMAC 保护。 */
    public boolean isColumn(String field) throws IOException {
        return columns().contains(field);
    }
}
