package cn.threathunter.nebula.console.api;

import cn.threathunter.nebula.console.store.MetadataStore;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 元数据下发 —— 引擎从这里取事件、变量与策略,不再读本地 {@code seeds/} 目录。
 *
 * <p>此前控制面把策略写进 PostgreSQL,引擎从文件加载:<b>同一份领域模型有两个
 * 事实来源</b>。改完策略引擎毫无察觉,而两边的分歧不会有任何报错 —— 这正是 1.x
 * 走过的路(Python 侧的 nebula_meta 与 Java 侧的 com.threathunter.variable 各写
 * 一份,逐渐分歧到谁也说不清哪个才算数)。数据库是唯一事实来源,文件退回它本来
 * 的角色:初始导入的种子数据。
 *
 * <p>响应带 {@code version}(即 {@code metadata_version})。引擎轮询这个数字,
 * 变了才重新拉取完整内容 —— 一个整数的对比,不必每次传输全量策略。
 */
@RestController
@RequestMapping("/api/v2/metadata")
public class BundleController {

    private final MetadataStore store;

    public BundleController(MetadataStore store) {
        this.store = store;
    }

    /** 只取版本号。引擎的轮询走这个接口,代价与传输量都可以忽略。 */
    @GetMapping("/version")
    public ResponseEntity<Map<String, Object>> version() {
        return ResponseEntity.ok(Map.of("version", store.metadataVersion()));
    }

    /**
     * 完整元数据。
     *
     * @param status 只下发处于这些状态的策略,逗号分隔。默认 {@code online,test} ——
     *               {@code inedit} 是没写完的草稿,{@code outline} 是已下线的,
     *               把它们下发给引擎就是让草稿直接影响线上判定。
     */
    @GetMapping("/bundle")
    public ResponseEntity<Map<String, Object>> bundle(
            @RequestParam(defaultValue = "online,test") String status) {

        // 先读版本再读内容:期间若有人改了策略,引擎拿到的是「偏旧的版本号 +
        // 较新的内容」,下一轮轮询会再拉一次。反过来(先内容后版本)会得到
        // 「较新的版本号 + 偏旧的内容」,引擎认为自己是最新的,改动永远不生效。
        long version = store.metadataVersion();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("events", store.allEventDefinitions());
        body.put("variables", store.allVariableDefinitions());
        body.put("strategies", store.strategyDefinitionsByStatus(status));
        return ResponseEntity.ok(body);
    }
}
