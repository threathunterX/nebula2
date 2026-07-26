package cn.threathunter.nebula.engine.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 元数据拉取。
 *
 * <p>关注的不是「能不能拉到」,而是<b>拉不到的时候会怎样</b>。这条路径上最坏的
 * 结果不是报错,是悄悄拿到一份不对的元数据然后照常跑 —— 运营改了策略以为生效了,
 * 线上判定却是旧的,而且没有任何迹象。所以每种失败都要能被区分出来并抛异常。
 */
class MetadataClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final List<String> paths = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void respond(String path, int code, String body) {
        server.createContext(path, (HttpExchange ex) -> {
            paths.add(ex.getRequestURI().getPath());
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
    }

    @Test
    @DisplayName("正常拉取 bundle,并带上 Bearer 令牌")
    void fetchesBundle() throws IOException {
        respond("/api/v2/metadata/bundle", 200, """
                {"version": 42,
                 "events": [{"name": "HTTP_DYNAMIC"}],
                 "variables": [{"name": "v1"}, {"name": "v2"}],
                 "strategies": [{"name": "s1"}]}
                """);
        MetadataClient.Bundle b = new MetadataClient(baseUrl, "svc_x.y", 3000).bundle();

        assertEquals(42L, b.version());
        assertEquals(1, b.events().size());
        assertEquals(2, b.variables().size());
        assertEquals(1, b.strategies().size());
        assertEquals("Bearer svc_x.y", lastAuth.get());
    }

    @Test
    @DisplayName("轮询只取版本号,不拉全量")
    void versionEndpointIsSeparate() throws IOException {
        respond("/api/v2/metadata/version", 200, "{\"version\": 7}");
        assertEquals(7L, new MetadataClient(baseUrl, "t", 3000).version());
        assertEquals(List.of("/api/v2/metadata/version"), paths);
    }

    @Test
    @DisplayName("401 / 403 要能和其他错误区分开 —— 提示的是令牌问题,不是网络问题")
    void authFailureIsDistinct() {
        respond("/api/v2/metadata/bundle", 403, "{}");
        IOException e = assertThrows(IOException.class,
                () -> new MetadataClient(baseUrl, "t", 3000).bundle());
        assertTrue(e.getMessage().contains("metadata:read"),
                "错误信息应指出令牌需要的作用域: " + e.getMessage());
    }

    @Test
    @DisplayName("服务端错误抛异常,不返回空 bundle")
    void serverErrorThrows() {
        respond("/api/v2/metadata/bundle", 500, "boom");
        assertThrows(IOException.class, () -> new MetadataClient(baseUrl, "t", 3000).bundle());
    }

    @Test
    @DisplayName("控制面不可达抛异常 —— 空策略集和「拉不到」不是一回事")
    void unreachableThrows() {
        // 端口 9(discard)在本机上不监听
        assertThrows(IOException.class,
                () -> new MetadataClient("http://127.0.0.1:9", "t", 500).bundle());
    }

    @Test
    @DisplayName("缺少令牌时构造即失败,不发出匿名请求")
    void missingTokenFailsFast() {
        // fromEnv 读 NEBULA_CONSOLE_TOKEN;测试环境里没有这个变量
        if (System.getenv("NEBULA_CONSOLE_TOKEN") == null) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> MetadataClient.fromEnv(baseUrl));
            assertTrue(e.getMessage().contains("NEBULA_CONSOLE_TOKEN"));
        }
    }

    @Test
    @DisplayName("baseUrl 末尾多余的斜杠不应拼出 //api")
    void trailingSlashIsNormalized() throws IOException {
        respond("/api/v2/metadata/version", 200, "{\"version\": 1}");
        new MetadataClient(baseUrl + "///", "t", 3000).version();
        assertEquals(List.of("/api/v2/metadata/version"), paths);
    }
}
