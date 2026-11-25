package know.engine.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import know.engine.chat.service.ChatService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static know.engine.knowledge.KnowledgeSourceService.shorten;

@Configuration(proxyBeanMethods = false)
public class KnowledgeMcpConfiguration {
    private static final String PATH = "/mcp";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
    private static final Map<String, Object> IDENTIFIER = Map.of("type", "string", "pattern", "^[1-9][0-9]{0,18}$");

    @Bean(destroyMethod = "")
    HttpServletStatelessServerTransport knowledgeMcpTransport(ObjectMapper json) {
        return HttpServletStatelessServerTransport.builder()
                .messageEndpoint(PATH).jsonMapper(new JacksonMcpJsonMapper(json)).maxRequestSize(16_384).build();
    }

    @Bean(destroyMethod = "closeGracefully")
    McpStatelessSyncServer knowledgeMcpServer(HttpServletStatelessServerTransport knowledgeMcpTransport,
                                            ObjectMapper json, ChatService chat,
                                            KnowledgeSourceService sources, KnowledgeReviewService reviews) {
        var search = tool(json, "search_knowledge", "搜索当前可检索的知识，返回带版本和片段编号的证据。",
                Map.of("query", Map.of("type", "string", "minLength", 1, "maxLength", 500)), List.of("query"),
                args -> chat.searchKnowledge(KnowledgeReviewService.requireQuestion(string(args, "query"))).stream()
                        .map(hit -> new KnowledgeSourceService.Citation(hit.getDocumentId(), hit.getDocumentVersionId(),
                                hit.getDocumentTitle(), null, hit.getChunkId(), null, shorten(hit.getChunkContent(), 1600)))
                        .toList());
        var citation = tool(json, "get_citation", "按文档、版本和片段编号读取引用的原文及位置。",
                Map.of("documentId", IDENTIFIER, "versionId", IDENTIFIER,
                        "chunkId", Map.of("type", "string", "minLength", 1, "maxLength", 128)),
                List.of("documentId", "versionId"),
                args -> sources.preview(id(args, "documentId"), id(args, "versionId"), string(args, "chunkId"), null));
        var changes = tool(json, "compare_versions", "查看同一文档两个指定版本的原文差异。",
                Map.of("documentId", IDENTIFIER, "baselineId", IDENTIFIER, "candidateId", IDENTIFIER),
                List.of("documentId", "baselineId", "candidateId"),
                args -> reviews.compare(id(args, "documentId"), id(args, "baselineId"), id(args, "candidateId"), List.of()));
        return McpServer.sync(knowledgeMcpTransport).serverInfo("know-engine", "1.0.0")
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .instructions("从 KnowEngine 本地知识库读取证据和文档版本差异。")
                .tools(search, citation, changes).build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> knowledgeMcpServlet(
            HttpServletStatelessServerTransport knowledgeMcpTransport, McpStatelessSyncServer knowledgeMcpServer) {
        return new ServletRegistrationBean<>(knowledgeMcpTransport, PATH);
    }

    @Bean
    FilterRegistrationBean<jakarta.servlet.Filter> localKnowledgeMcp() {
        FilterRegistrationBean<jakarta.servlet.Filter> bean = new FilterRegistrationBean<>();
        bean.addUrlPatterns(PATH);
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        bean.setFilter((request, response, chain) -> {
            HttpServletRequest http = (HttpServletRequest) request;
            if (!isLocal(http) || !isSameOrigin(http)) {
                ((HttpServletResponse) response).sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            chain.doFilter(request, response);
        });
        return bean;
    }

    private static boolean isLocal(HttpServletRequest request) {
        if (!LOCAL_HOSTS.contains(request.getServerName().toLowerCase(Locale.ROOT))) {
            return false;
        }
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean isSameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null) {
            return true;
        }
        try {
            URI uri = URI.create(origin);
            int port = uri.getPort() != -1 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
            return request.getScheme().equals(uri.getScheme()) && request.getServerName().equalsIgnoreCase(uri.getHost())
                    && request.getServerPort() == port && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null && "".equals(uri.getRawPath());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static SyncToolSpecification tool(ObjectMapper json, String name, String description,
                                              Map<String, Object> properties, List<String> required,
                                              Function<Map<String, Object>, Object> operation) {
        Tool tool = Tool.builder().name(name).description(description)
                .annotations(ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build())
                .inputSchema(Map.of("type", "object", "properties", properties, "required", required,
                        "additionalProperties", false)).build();
        return new SyncToolSpecification(tool, (context, request) -> {
            try {
                return CallToolResult.builder().addTextContent(json.writeValueAsString(operation.apply(request.arguments()))).build();
            } catch (ResponseStatusException e) {
                return CallToolResult.builder().isError(true).addTextContent(e.getReason()).build();
            } catch (IllegalArgumentException e) {
                return CallToolResult.builder().isError(true).addTextContent(e.getMessage()).build();
            } catch (Exception e) {
                return CallToolResult.builder().isError(true).addTextContent("暂时无法读取知识库。").build();
            }
        });
    }

    private static Long id(Map<String, Object> args, String key) {
        String value = string(args, key);
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("无效的文档或版本编号");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无效的文档或版本编号", e);
        }
    }

    private static String string(Map<String, Object> args, String key) {
        return args != null && args.get(key) instanceof String value ? value : null;
    }
}
