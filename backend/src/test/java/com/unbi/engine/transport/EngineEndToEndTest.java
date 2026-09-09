package com.unbi.engine.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.unbi.engine.registry.NodeRegistry;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the real application the way the editor does: fetch the catalog over HTTP, then run a
 * graph over the WebSocket and read the event stream back.
 *
 * <p>This is the only test that proves the pieces are actually wired together — the codecs, the
 * registry, the socket, the engine and the file nodes all participate. Everything below it can pass
 * while the application refuses to start.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EngineEndToEndTest {

    @LocalServerPort
    int port;

    @Autowired
    NodeRegistry registry;

    @TempDir
    Path workspace;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("every node in the classpath is advertised in the catalog")
    void catalogDescribesRegisteredNodes() {
        var body = RestClient.create()
                .get()
                .uri("http://localhost:" + port + "/api/catalog")
                .retrieve()
                .body(String.class);

        var catalog = mapper.readTree(body);
        var ids = new ArrayList<String>();
        catalog.get("nodes").forEach(node -> ids.add(node.get("id").asString()));

        assertThat(ids)
                .containsExactlyInAnyOrderElementsOf(
                        registry.all().stream().map(descriptor -> descriptor.id()).toList())
                .contains("io.scan_directory", "text.search_in_files", "report.generate");
    }

    @Test
    @DisplayName("the catalog carries enough for the editor to draw a node it has never seen")
    void catalogCarriesWidgetsAndTypes() {
        var catalog = mapper.readTree(RestClient.create()
                .get()
                .uri("http://localhost:" + port + "/api/catalog")
                .retrieve()
                .body(String.class));

        JsonNode scan = null;
        for (var node : catalog.get("nodes")) {
            if ("io.scan_directory".equals(node.get("id").asString())) {
                scan = node;
            }
        }

        assertThat(scan).isNotNull();
        assertThat(scan.get("label").asString()).isEqualTo("Scan Directory");
        assertThat(scan.get("category").asString()).isEqualTo("Files");

        var recursive = field(scan.get("inputs"), "recursive");
        assertThat(recursive.get("widget").get("kind").asString()).isEqualTo("toggle");
        assertThat(recursive.get("connectable").asBoolean()).isFalse();
        assertThat(recursive.get("default").asBoolean()).isTrue();

        var files = field(scan.get("outputs"), "files");
        assertThat(files.get("type").get("kind").asString()).isEqualTo("list");
        assertThat(files.get("type").get("element").get("name").asString()).isEqualTo("FileRef");
    }

    @Test
    @DisplayName("scan, search and report run end to end over the socket")
    void runsTheBatchPipeline() throws Exception {
        Files.writeString(workspace.resolve("one.txt"), "alpha beta\nbeta gamma\n");
        Files.writeString(workspace.resolve("two.txt"), "nothing here\n");
        Files.writeString(workspace.resolve("skip.md"), "beta in markdown\n");

        var frames = new CopyOnWriteArrayList<JsonNode>();
        var session = connect(frames);

        session.sendMessage(new TextMessage(runFrame(workspace)));

        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> hasType(frames, "run.finished"));

        var finished = firstOfType(frames, "run.finished");
        assertThat(finished.get("outcome").asString())
                .describedAs("run message: %s", finished.get("message").asString())
                .isEqualTo("COMPLETED");

        var states = frames.stream()
                .filter(frame -> "node.state".equals(frame.path("type").asString("")))
                .toList();
        assertThat(states).extracting(frame -> frame.get("state").asString()).contains("COMPLETED");
        assertThat(states).noneMatch(frame -> "FAILED".equals(frame.get("state").asString()));

        // The report node writes into the same folder, which is the whole point of the use case.
        var report = workspace.resolve("found.md");
        assertThat(report).exists();
        var content = Files.readString(report);
        assertThat(content).contains("# Search Results").contains("one.txt");
        assertThat(content).doesNotContain("two.txt");

        session.close();
    }

    @Test
    void reportsValidationProblemsInsteadOfRunning() throws Exception {
        var frames = new CopyOnWriteArrayList<JsonNode>();
        var session = connect(frames);

        // A graph whose only node needs an input that nothing supplies.
        session.sendMessage(new TextMessage("""
                {"type":"run","requestId":"r1","graph":{
                  "nodes":[{"id":"n1","type":"text.search_in_files","values":{},"position":{"x":0,"y":0}}],
                  "edges":[]
                }}"""));

        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> hasType(frames, "run.finished"));

        assertThat(firstOfType(frames, "run.finished").get("outcome").asString()).isEqualTo("REJECTED");
        assertThat(firstOfType(frames, "run.rejected").get("problems").toString()).contains("Files");

        session.close();
    }

    @Test
    void answersAValidateRequestWithoutStartingARun() throws Exception {
        var frames = new CopyOnWriteArrayList<JsonNode>();
        var session = connect(frames);

        session.sendMessage(new TextMessage("""
                {"type":"validate","requestId":"v1","graph":{
                  "nodes":[{"id":"n1","type":"io.directory","values":{"path":"."},"position":{"x":0,"y":0}}],
                  "edges":[]
                }}"""));

        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> hasType(frames, "validation"));

        var validation = firstOfType(frames, "validation");
        assertThat(validation.get("requestId").asString()).isEqualTo("v1");
        assertThat(validation.get("valid").asBoolean()).isTrue();
        assertThat(frames).noneMatch(frame -> "run.started".equals(frame.path("type").asString("")));

        session.close();
    }

    @Test
    void rejectsAMalformedGraphWithAMessageNamingTheProblem() throws Exception {
        var frames = new CopyOnWriteArrayList<JsonNode>();
        var session = connect(frames);

        session.sendMessage(new TextMessage(
                """
                {"type":"run","requestId":"r2","graph":{"nodes":[{"id":"n1"}],"edges":[]}}"""));

        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> hasType(frames, "error"));

        assertThat(firstOfType(frames, "error").get("message").asString())
                .contains("type")
                .contains("node n1");

        session.close();
    }

    private String runFrame(Path directory) {
        var escaped = directory.toString().replace("\\", "\\\\");
        return """
            {"type":"run","requestId":"r0","graph":{
              "nodes":[
                {"id":"scan","type":"io.scan_directory","position":{"x":0,"y":0},
                 "values":{"directory":"%s","pattern":"*","recursive":true,"maxDepth":4,"limit":100}},
                {"id":"filter","type":"io.filter_files","position":{"x":1,"y":0},
                 "values":{"extensions":"txt","mode":"keep","minSize":0}},
                {"id":"search","type":"text.search_in_files","position":{"x":2,"y":0},
                 "values":{"query":"beta","regex":false,"caseSensitive":false}},
                {"id":"report","type":"report.generate","position":{"x":3,"y":0},
                 "values":{"title":"Search Results","format":"markdown",
                           "outputDirectory":"%s","fileName":"found.md"}}
              ],
              "edges":[
                {"id":"e1","sourceNode":"scan","sourcePort":"files","targetNode":"filter","targetPort":"files"},
                {"id":"e2","sourceNode":"filter","sourcePort":"files","targetNode":"search","targetPort":"files"},
                {"id":"e3","sourceNode":"search","sourcePort":"matches","targetNode":"report","targetPort":"data"}
              ]
            }}""".formatted(escaped, escaped);
    }

    private WebSocketSession connect(List<JsonNode> frames) throws Exception {
        WebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                frames.add(mapper.readTree(message.getPayload()));
            }
        };
        var session = new StandardWebSocketClient()
                .execute(handler, URI.create("ws://localhost:" + port + "/ws/engine").toString())
                .get();
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(session::isOpen);
        return session;
    }

    private static boolean hasType(List<JsonNode> frames, String type) {
        return frames.stream().anyMatch(frame -> type.equals(frame.path("type").asString("")));
    }

    private static JsonNode firstOfType(List<JsonNode> frames, String type) {
        return frames.stream()
                .filter(frame -> type.equals(frame.path("type").asString("")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No frame of type " + type + " in " + frames));
    }

    private static JsonNode field(JsonNode array, String key) {
        for (var entry : array) {
            if (key.equals(entry.get("key").asString())) {
                return entry;
            }
        }
        throw new AssertionError("No port named " + key);
    }

    @SuppressWarnings("unused")
    private static void closeQuietly(WebSocketSession session) throws IOException {
        session.close(CloseStatus.NORMAL);
    }
}
