package cn.threathunter.nebula.engine.flink;

import cn.threathunter.nebula.engine.rule.StrategyEngine;
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
        String group = opts.getOrDefault("group", "nebula-engine");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1); // 见类注释:多维度分区尚未实现

        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers(brokers)
                .setTopics(sourceTopic)
                .setGroupId(group)
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        DataStream<StrategyEngine.Notice> notices =
                build(env, source, seeds, "kafka-source");

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
