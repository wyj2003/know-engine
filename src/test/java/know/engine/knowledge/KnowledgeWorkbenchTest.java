package know.engine.knowledge;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import know.engine.chat.entity.ChatMessage;
import know.engine.chat.service.ChatService;
import know.engine.document.constant.DocumentStatus;
import know.engine.document.controller.DocumentController;
import know.engine.document.entity.Document;
import know.engine.document.entity.DocumentVersion;
import know.engine.document.entity.KnowledgeSegment;
import know.engine.document.service.DocumentService;
import know.engine.document.service.DocumentVersionService;
import know.engine.document.service.FileStorageService;
import know.engine.document.service.SegmentService;
import know.engine.document.service.DocumentProcessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = KnowledgeWorkbenchTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "spring.config.name=knowledge-workbench-test"})
class KnowledgeWorkbenchTest {
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, ErrorMvcAutoConfiguration.class, JacksonAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class})
    @Import({KnowledgeController.class, KnowledgeSourceService.class, KnowledgeReviewService.class,
            TableQueryService.class, KnowledgeMcpConfiguration.class, DocumentController.class})
    static class TestApplication { }

    @MockitoBean DocumentService documents;
    @MockitoBean DocumentVersionService versions;
    @MockitoBean DocumentProcessService documentProcess;
    @MockitoBean SegmentService segments;
    @MockitoBean FileStorageService storage;
    @MockitoBean ChatService chat;
    @MockitoBean(name = "openAiChatModel") ChatModel model;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void publishFixtures() throws Exception {
        Document policy = document(1, 11, "澄岳观测室");
        Document inventory = document(2, 21, "晴湾备件");
        Document events = document(3, 31, "告警记录");
        Document guide = document(4, 41, "设备指南");
        DocumentVersion baseline = version(1, 11, "1.0.0", "policy-before.md", "policy-before.md");
        DocumentVersion candidate = version(1, 12, "1.0.1", "policy-after.md", "policy-after.md");
        DocumentVersion inventoryVersion = version(2, 21, "1.0.0", "stock.csv", "stock.csv");
        DocumentVersion eventVersion = version(3, 31, "1.0.0", "events.csv", "events.csv");
        DocumentVersion guideVersion = version(4, 41, "1.0.0", "guide.pdf", "guide.md");
        Map<Long, Document> docs = Map.of(1L, policy, 2L, inventory, 3L, events, 4L, guide);
        Map<Long, DocumentVersion> snapshots = Map.of(11L, baseline, 12L, candidate, 21L, inventoryVersion,
                31L, eventVersion, 41L, guideVersion);
        when(documents.getById(any(Long.class))).thenAnswer(call -> docs.get(call.getArgument(0)));
        when(versions.getById(any(Long.class))).thenAnswer(call -> snapshots.get(call.getArgument(0)));
        when(versions.listByDocId(1L)).thenReturn(List.of(candidate, baseline));
        when(versions.listByDocId(2L)).thenReturn(List.of(inventoryVersion));
        when(versions.listByDocId(3L)).thenReturn(List.of(eventVersion));
        when(versions.listByDocId(4L)).thenReturn(List.of(guideVersion));
        var page = new Page<Document>(1, 100, docs.size());
        page.setRecords(docs.values().stream().sorted(java.util.Comparator.comparing(Document::getDocId)).toList());
        when(documents.page(any(), any())).thenReturn(page);
        when(storage.objectNameFromUrl(anyString())).thenAnswer(call ->
                call.<String>getArgument(0).substring("http://storage.test/know-engine/".length()));
        Map<String, String> contents = Map.of(
                "policy-before.md", "# 预约规则\n澄岳观测室至少需要提前 48 小时预约。\n",
                "policy-after.md", "# 预约规则\n澄岳观测室至少需要提前 72 小时预约。\n",
                "stock.csv", "物料,场所,数量\n垫片,甲库,3\n转轴,乙库,12\n\"轴承,大号\",甲库,7\n",
                "events.csv", "事件编号,错误码,资产编号\nEVT-17,ERR-42,DEVICE-7\n",
                "guide.md", "ERR-42 复核步骤：确认设备供电后检查连接器是否松动。\n",
                "guide.md.locations.json", """
                        [
                          {"page_idx": 1, "text": "ERR-42 复核步骤：确认设备供电后检查连接器是否松动。", "bbox": [50, 100, 740, 200]}
                        ]
                        """);
        when(storage.downloadFile(anyString())).thenAnswer(call -> {
            String key = call.getArgument(0);
            if ("guide.pdf".equals(key)) {
                return getClass().getResourceAsStream("/testFile/04_曙光智造园区故障排查指南.pdf");
            }
            String text = contents.get(key);
            if (text == null) throw new IllegalArgumentException("Missing fixture");
            return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        });
        KnowledgeSegment citation = new KnowledgeSegment();
        citation.setDocumentVersion(41L);
        citation.setChunkId("123");
        citation.setText(contents.get("guide.md"));
        when(segments.getOne(any())).thenReturn(citation);
        when(chat.searchKnowledge("错误码")).thenReturn(List.of(ChatMessage.RagReference.builder()
                .documentId("4").documentVersionId("41").documentTitle("设备指南").chunkId("123")
                .chunkContent(contents.get("guide.md")).build()));
        when(model.chat(anyString())).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            if (prompt.contains("下一次") || prompt.contains("第二份资料")) return "ERR-42 DEVICE-7";
            if (prompt.contains("48 小时")) return "48 小时。[1]";
            if (prompt.contains("72 小时")) return "72 小时。[1]";
            return "请检查连接器是否松动。[2]";
        });
    }

    @Test
    void comparesHistoricalAndDraftVersionsWithTheirOwnEvidence() throws Exception {
        JsonNode report = post("/api/knowledge/version-review", """
                {"documentId":"1","baselineId":"11","candidateId":"12","questions":["澄岳观测室提前多久预约？"]}
                """);
        assertThat(report.at("/changes/0/removed/0").asText()).contains("48 小时");
        assertThat(report.at("/changes/0/added/0").asText()).contains("72 小时");
        assertThat(report.at("/questions/0/baseline/text").asText()).contains("48 小时");
        assertThat(report.at("/questions/0/candidate/text").asText()).contains("72 小时");
        assertThat(report.at("/questions/0/candidate/evidence/0/versionId").asText()).isEqualTo("12");
        assertThat(report.at("/questions/0/candidate/evidence/0/line").asInt()).isEqualTo(2);
    }

    @Test
    void calculatesWithDuckDbAndReturnsTheActualSourceRows() throws Exception {
        JsonNode result = post("/api/knowledge/table/query", """
                {"documentId":"2","versionId":"21","sheet":"CSV",
                 "query":{"operation":"SUM","column":"数量","groupBy":"场所","limit":10}}
                """);
        assertThat(result.at("/rows/0/values/0").asText()).isEqualTo("乙库");
        assertThat(new java.math.BigDecimal(result.at("/rows/0/values/1").asText())).isEqualByComparingTo("12");
        assertThat(result.at("/rows/1/sourceRowCount").asInt()).isEqualTo(2);
        assertThat(result.at("/rows/1/sourceRows/1").asInt()).isEqualTo(4);
        JsonNode sources = get("/api/knowledge/table/source-rows?documentId=2&versionId=21&sheet=CSV&rows=2&rows=4");
        assertThat(sources.at("/rows/1/cells/0").asText()).isEqualTo("轴承,大号");
    }

    @Test
    void naturalLanguageTableQuestionInvokesTheBoundedCalculationTool() throws Exception {
        var tool = ToolExecutionRequest.builder().id("1").name("calculate").arguments("""
                {"query":{"operation":"TOP_LOWEST","column":"数量","filters":[],"limit":1}}
                """).build();
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(tool)).build());
        JsonNode result = post("/api/knowledge/table/query", """
                {"documentId":"2","versionId":"21","question":"数量最低的是哪种备件？"}
                """);
        assertThat(result.at("/rows/0/values/0").asText()).isEqualTo("垫片");
        assertThat(result.at("/rows/0/sourceRows/0").asInt()).isEqualTo(2);
    }

    @Test
    void displaysPdfPageAndBoundingBoxAndJumpsToMarkdownLines() throws Exception {
        JsonNode preview = get("/api/knowledge/source?documentId=4&chunkId=123");
        assertThat(preview.at("/citation/versionId").asText()).isEqualTo("41");
        assertThat(preview.path("page").asInt()).isEqualTo(2);
        assertThat(preview.at("/box/0").asInt()).isEqualTo(50);
        HttpResponse<String> pdf = send("/api/knowledge/pdf/4/41", null, false);
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(pdf.headers().firstValue("Content-Type")).contains("application/pdf");
        assertThat(pdf.body()).startsWith("%PDF");
        JsonNode markdown = get("/api/knowledge/source?documentId=1&versionId=12&line=2");
        assertThat(markdown.at("/citation/excerpt").asText()).contains("72 小时");
        assertThat(markdown.at("/citation/line").asInt()).isEqualTo(2);
    }

    @Test
    void carriesFirstDocumentCluesIntoSecondDocumentSearch() throws Exception {
        JsonNode result = post("/api/knowledge/research", """
                {"firstDocument":"3","firstVersion":"31","secondDocument":"4","secondVersion":"41",
                 "question":"EVT-17 对应设备的排查步骤是什么？"}
                """);
        assertThat(result.at("/initialEvidence/0/excerpt").asText()).contains("DEVICE-7");
        assertThat(result.path("secondQuery").asText()).contains("ERR-42");
        assertThat(result.at("/answer/evidence/1/documentId").asText()).isEqualTo("4");
        assertThat(result.at("/answer/text").asText()).contains("连接器");
    }

    @Test
    void servesStaticWorkbenchAndOfficialMcpTransport() throws Exception {
        assertThat(send("/knowledge.html", null, false).body()).contains("版本试问", "表格计算", "跨资料问答");
        assertThat(send("/webjars/pdfjs-dist/6.5.438/build/pdf.mjs", null, false).statusCode()).isEqualTo(200);
        JsonNode initialize = jsonResponse(send("/mcp", """
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
                """, true));
        assertThat(initialize.at("/result/serverInfo/name").asText()).isEqualTo("know-engine");
        JsonNode tools = jsonResponse(send("/mcp", """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """, true));
        assertThat(tools.at("/result/tools")).hasSize(3);
        assertThat(tools.at("/result/tools/0/annotations/readOnlyHint").asBoolean()).isTrue();
        JsonNode result = jsonResponse(send("/mcp", """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_knowledge","arguments":{"query":"错误码"}}}
                """, true));
        JsonNode citations = json.readTree(result.at("/result/content/0/text").asText());
        assertThat(citations.at("/0/versionId").asText()).isEqualTo("41");
        verify(chat).searchKnowledge("错误码");
        JsonNode citation = jsonResponse(send("/mcp", """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_citation",
                 "arguments":{"documentId":"4","versionId":"41","chunkId":"123"}}}
                """, true));
        assertThat(json.readTree(citation.at("/result/content/0/text").asText()).path("page").asInt()).isEqualTo(2);
        JsonNode changes = jsonResponse(send("/mcp", """
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"compare_versions",
                 "arguments":{"documentId":"1","baselineId":"11","candidateId":"12"}}}
                """, true));
        assertThat(json.readTree(changes.at("/result/content/0/text").asText()).at("/changes/0/added/0").asText())
                .contains("72 小时");
        var remoteOrigin = HttpRequest.newBuilder(uri("/mcp")).header("Origin", "https://example.test").GET().build();
        assertThat(client.send(remoteOrigin, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
    }

    private JsonNode get(String path) throws Exception {
        return jsonResponse(send(path, null, false));
    }

    private JsonNode post(String path, String payload) throws Exception {
        return jsonResponse(send(path, payload, false));
    }

    private JsonNode jsonResponse(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> send(String path, String payload, boolean mcp) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20));
        if (mcp) {
            request.header("Accept", "application/json, text/event-stream");
            request.header("MCP-Protocol-Version", "2025-11-25");
        }
        if (payload != null) {
            request.header("Content-Type", "application/json");
            request.POST(HttpRequest.BodyPublishers.ofString(payload));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static Document document(long docId, long current, String title) {
        Document document = new Document();
        document.setDocId(docId);
        document.setCurrentVersionId(current);
        document.setDocTitle(title);
        document.setStatus(DocumentStatus.VECTOR_STORED);
        return document;
    }

    private static DocumentVersion version(long docId, long id, String label, String original, String converted) {
        DocumentVersion version = new DocumentVersion();
        version.setDocId(docId);
        version.setVersionId(id);
        version.setVersion(label);
        version.setDocUrl("http://storage.test/know-engine/" + original);
        version.setConvertedDocUrl("http://storage.test/know-engine/" + converted);
        version.setStatus(DocumentStatus.CONVERTED);
        return version;
    }
}
