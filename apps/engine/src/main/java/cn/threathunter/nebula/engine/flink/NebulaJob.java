package cn.threathunter.nebula.engine.flink;

import cn.threathunter.nebula.engine.meta.MetadataClient;
import cn.threathunter.nebula.engine.rule.StrategyEngine;
import cn.threathunter.nebula.engine.sink.ClickHouseRows;
import cn.threathunter.nebula.engine.sink.PiiHmac;
import cn.threathunter.nebula.engine.sink.ClickHouseSink;
import cn.threathunter.nebula.engine.sink.RedisNoticeSink;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.connector.source.Source;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * 星云计算作业 —— 从 Kafka 消费事件,产出风险告警写回 Kafka。
 *
 * <p>用法:
 * <pre>
 *   flink run nebula-engine.jar \
 *     --brokers localhost:9092 \
 *     --source-topic nebula.events \
 *     --sink-topic nebula.notice \
 *     --seeds /etc/nebula/seeds
 * </pre>
 *
 * <p>元数据来源:给了 {@code --console-url} 就从控制面拉(数据库是唯一事实来源),
 * 否则读本地 {@code --seeds} 目录。凭据从环境变量 {@code NEBULA_CONSOLE_TOKEN} 取。
 *
 * <p><b>当前限制:并行度必须为 1。</b>变量按不同维度分组(ip / uid / did / page),
 * 一次 keyBy 无法同时满足。按维度拆链路再汇聚是下一步的工作,见
 * {@link RiskDetectionFunction} 的说明。
 */
public final class NebulaJob {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NebulaJob() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        String brokers = opts.getOrDefault("brokers", "localhost:9092");
        String sourceTopic = opts.getOrDefault("source-topic", "nebula.events");
        String sinkTopic = opts.getOrDefault("sink-topic", "nebula.notice");
        String seeds = opts.getOrDefault("seeds", "seeds");
        // 元数据优先从控制面拉。给了 --console-url 就以数据库为唯一事实来源,
        // 本地 seeds 目录退回它本来的角色:首次导入的种子数据。
        String consoleUrl = opts.get("console-url");
        String group = opts.getOrDefault("group", "nebula-engine");
        // ClickHouse 凭据只从环境变量取,不接受命令行传入 —— 命令行会进程列表可见
        String chUrl = System.getenv().getOrDefault("CLICKHOUSE_URL", "http://127.0.0.1:8123");
        String chUser = System.getenv("CLICKHOUSE_USER");
        String chPassword = System.getenv("CLICKHOUSE_PASSWORD");
        boolean toClickHouse = chUser != null && !chUser.isBlank()
                && chPassword != null && !chPassword.isBlank();

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1); // 见类注释:多维度分区尚未实现

        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers(brokers)
                .setTopics(sourceTopic)
                .setGroupId(group)
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        DataStream<String> raw = env.fromSource(
                source, WatermarkStrategy.noWatermarks(), "kafka-source");
        DataStream<Map<String, Object>> events = raw
                .map(NebulaJob::parse)
                .returns(TypeInformation.of(new org.apache.flink.api.common.typeinfo.TypeHint<>() {
                }))
                .filter(e -> e != null && e.get("name") != null && e.get("timestamp") != null);

        if (toClickHouse) {
            // 个人标识列在写库前做 HMAC。变量计算与策略判定用的仍是原值 ——
            // c_ip 一旦在采集端哈希,地理定位与跨维度关联就废了,所以保护放在这一层。
            PiiHmac hmac = PiiHmac.fromEnv();
            System.out.println(hmac.enabled()
                    ? "事件明细的 HMAC 保护列: " + hmac.columns()
                    : "事件明细未启用 HMAC 保护(NEBULA_PII_HMAC_COLUMNS 为空)");

            // 事件明细落库。小时聚合由 ClickHouse 的物化视图自动维护,不需要批任务。
            events.addSink(new ClickHouseSink<Map<String, Object>>(
                    "nebula.events", e -> ClickHouseRows.event(e, hmac), 500, 2000,
                    chUrl, chUser, chPassword)).name("clickhouse-events");
        }

        Metadata meta = loadMetadata(consoleUrl, seeds);
        System.out.println("元数据来源: " + meta.origin()
                + "(事件 " + meta.events().size()
                + " / 变量 " + meta.variables().size()
                + " / 策略 " + meta.strategies().size() + ")");

        DataStream<StrategyEngine.Notice> notices = events.process(
                new RiskDetectionFunction(
                        meta.strategies(), meta.variables(), meta.events()));

        if (toClickHouse) {
            notices.addSink(new ClickHouseSink<StrategyEngine.Notice>(
                    "nebula.notices", ClickHouseRows::notice, 200, 2000,
                    chUrl, chUser, chPassword)).name("clickhouse-notices");
        }

        // 名单写 Redis,供控制面的 /checkRisk 同步查询
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1");
        String redisPassword = System.getenv("REDIS_PASSWORD");
        if (redisPassword != null && !redisPassword.isBlank()) {
            int redisPort = Integer.parseInt(
                    System.getenv().getOrDefault("REDIS_PORT", "6379"));
            notices.addSink(new RedisNoticeSink(redisHost, redisPort, redisPassword,
                    "nebula:notice:")).name("redis-notices");
        }

        KafkaSink<String> sink = KafkaSink.<String>builder()
                .setBootstrapServers(brokers)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic(sinkTopic)
                        .setValueSerializationSchema(new SimpleStringSchema())
                        .build())
                .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                .build();

        notices.map(NebulaJob::toJson).sinkTo(sink);
        env.execute("nebula-risk-detection");
    }

    /** 构建计算链路。抽出来是为了让测试可以换用别的 source。 */
    public static DataStream<StrategyEngine.Notice> build(
            StreamExecutionEnvironment env,
            Source<String, ?, ?> source,
            String seedsDir,
            String sourceName) {

        DataStream<String> raw = env.fromSource(
                source, WatermarkStrategy.noWatermarks(), sourceName);

        DataStream<Map<String, Object>> events = raw
                .map(NebulaJob::parse)
                .returns(TypeInformation.of(new org.apache.flink.api.common.typeinfo.TypeHint<>() {
                }))
                .filter(e -> e != null && e.get("name") != null && e.get("timestamp") != null);

        return events.process(new RiskDetectionFunction(
                loadDir(seedsDir, "strategies"),
                loadDir(seedsDir, "variables"),
                loadDir(seedsDir, "events")));
    }

    private static Map<String, Object> parse(String line) {
        try {
            return MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return null; // 坏行跳过,不中断整条流水线
        }
    }

    private static String toJson(StrategyEngine.Notice n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", n.timestamp());
        m.put("key", n.key());
        m.put("check_type", n.checkType());
        m.put("strategy_name", n.strategyName());
        m.put("scene_name", n.sceneName());
        m.put("decision", n.decision());
        m.put("risk_score", n.riskScore());
        m.put("expire", n.expire());
        m.put("remark", n.remark());
        m.put("tags", n.tags());
        m.put("test", n.test());
        m.put("variable_values", n.variableValues());
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("序列化告警失败", e);
        }
    }

    record Metadata(String origin,
                    List<Map<String, Object>> strategies,
                    List<Map<String, Object>> variables,
                    List<Map<String, Object>> events) {
    }

    /**
     * 加载元数据。
     *
     * <p>给了 {@code --console-url} 就从控制面拉,<b>失败即启动失败</b>,不回落到
     * 本地文件。回落看起来「更健壮」,实际是最糟的结果:作业带着一份不知多旧的
     * 策略跑起来,而且没有任何迹象表明它没连上控制面 —— 运营改了策略以为生效了,
     * 线上判定却还是旧的。宁可起不来。
     */
    static Metadata loadMetadata(String consoleUrl, String seedsDir) {
        if (consoleUrl == null || consoleUrl.isBlank()) {
            return new Metadata("本地 seeds 目录 " + seedsDir,
                    loadDir(seedsDir, "strategies"),
                    loadDir(seedsDir, "variables"),
                    loadDir(seedsDir, "events"));
        }
        try {
            MetadataClient.Bundle b = MetadataClient.fromEnv(consoleUrl).bundle();
            return new Metadata("控制面 " + consoleUrl + " v" + b.version(),
                    b.strategies(), b.variables(), b.events());
        } catch (IOException | RuntimeException e) {
            // 网络不通、令牌缺失、令牌作用域不对 —— 原因不同,但对运维是同一件事:
            // 元数据没拿到,作业不该起来。统一包装,别让「缺环境变量」这类异常
            // 以另一种面貌冒出去,看着像是代码 bug 而不是配置问题。
            throw new IllegalStateException(
                    "从控制面加载元数据失败,作业不启动: " + e.getMessage(), e);
        }
    }

    static List<Map<String, Object>> loadDir(String seedsDir, String sub) {
        List<Map<String, Object>> out = new ArrayList<>();
        Path dir = Path.of(seedsDir, sub);
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.sorted().toList()) {
                String f = p.getFileName().toString();
                if (!f.endsWith(".json") || f.equals("index.json")) {
                    continue;
                }
                out.add(MAPPER.readValue(Files.readString(p),
                        new TypeReference<Map<String, Object>>() {
                        }));
            }
        } catch (IOException e) {
            throw new IllegalStateException("加载资产目录失败: " + dir, e);
        }
        return out;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].startsWith("--")) {
                out.put(args[i].substring(2), args[i + 1]);
                i++;
            }
        }
        return out;
    }
}
