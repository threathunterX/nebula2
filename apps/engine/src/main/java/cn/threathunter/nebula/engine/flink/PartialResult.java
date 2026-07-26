package cn.threathunter.nebula.engine.flink;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个维度算出的中间结果,等待按事件 ID 汇聚。
 *
 * @param eventId   事件唯一标识,汇聚的分组键
 * @param dimension 该结果来自哪个维度(c_ip / uid / did)
 * @param event     原始事件。各维度携带的是同一份内容,汇聚时取任意一份即可。
 * @param eventName 事件类型名
 * @param timestamp 事件时间
 * @param expected  这条事件总共要等多少个维度的结果
 * @param variables 该维度算出的变量值
 * @param counters  该维度算出的内联计数器值,键为「策略名|路径」
 */
public record PartialResult(
        String eventId,
        String dimension,
        Map<String, Object> event,
        String eventName,
        long timestamp,
        int expected,
        Map<String, Object> variables,
        Map<String, Object> counters) implements Serializable {

    private static final long serialVersionUID = 1L;

    public static PartialResult of(String eventId, String dimension,
                                   Map<String, Object> event, String eventName,
                                   long timestamp, int expected) {
        return new PartialResult(eventId, dimension, event, eventName, timestamp, expected,
                new LinkedHashMap<>(), new LinkedHashMap<>());
    }
}
