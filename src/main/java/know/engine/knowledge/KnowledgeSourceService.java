package know.engine.knowledge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.errors.ErrorResponseException;
import know.engine.document.constant.FileType;
import know.engine.document.entity.Document;
import know.engine.document.entity.DocumentVersion;
import know.engine.document.entity.KnowledgeSegment;
import know.engine.document.service.DocumentService;
import know.engine.document.service.DocumentVersionService;
import know.engine.document.service.FileStorageService;
import know.engine.document.service.SegmentService;
import know.engine.document.util.FileTypeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeSourceService {
    private static final int TEXT_LIMIT = 2_000_000;
    private static final int TABLE_LIMIT = 16 * 1024 * 1024;
    private static final int LOCATION_LIMIT = 8 * 1024 * 1024;
    private static final Pattern NON_SEARCH_TEXT = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final DocumentService documents;
    private final DocumentVersionService versions;
    private final SegmentService segments;
    private final FileStorageService storage;
    private final ObjectMapper json;

    record VersionSource(Document document, DocumentVersion version, FileType fileType, String objectName) {
        Citation cite(String chunkId, Integer line, String excerpt) {
            return new Citation(document.getDocId().toString(), version.getVersionId().toString(),
                    document.getDocTitle(), version.getVersion(), chunkId, line, excerpt);
        }
    }

    public record Citation(String documentId, String versionId, String title, String version,
                           String chunkId, Integer line, String excerpt) { }

    public record Preview(Citation citation, String fileType, int firstLine, List<String> lines,
                          Integer page, List<Integer> box, String pdfUrl) { }

    record LocatedPage(int page, List<Integer> box) { }

    VersionSource resolve(Long documentId, Long versionId) {
        if (documentId == null || documentId <= 0) {
            throw new IllegalArgumentException("请选择文档");
        }
        Document document = documents.getById(documentId);
        if (document == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        }
        Long selectedVersion = versionId == null ? document.getCurrentVersionId() : versionId;
        DocumentVersion version = selectedVersion == null ? null : versions.getById(selectedVersion);
        if (version == null || !documentId.equals(version.getDocId()) || version.getDocUrl() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到所选文档版本");
        }
        String objectName = storage.objectNameFromUrl(version.getDocUrl());
        return new VersionSource(document, version, FileTypeUtil.getFileType(objectName), objectName);
    }

    byte[] readTable(VersionSource source) {
        if (source.fileType() != FileType.EXCEL && source.fileType() != FileType.CSV) {
            throw new IllegalArgumentException("请选择 CSV、XLS 或 XLSX 文档");
        }
        return readBytes(source.objectName(), TABLE_LIMIT);
    }

    List<String> readText(VersionSource source) {
        if (source.fileType() == FileType.EXCEL) {
            try {
                var table = TableDataReader.read(readTable(source), FileType.EXCEL, null);
                List<String> lines = new ArrayList<>();
                lines.add("工作表 " + table.sheet());
                lines.add(String.join(" | ", table.columns()));
                for (var row : table.rows()) {
                    lines.add(String.join(" | ", row.cells()));
                }
                return lines;
            } catch (java.io.IOException e) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "工作簿无法读取", e);
            }
        }
        String objectName = source.objectName();
        if (source.fileType() == FileType.PDF || source.fileType() == FileType.DOC) {
            if (source.version().getConvertedDocUrl() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "文档仍在解析");
            }
            objectName = storage.objectNameFromUrl(source.version().getConvertedDocUrl());
        }
        return Arrays.asList(TableDataReader.decode(readBytes(objectName, TEXT_LIMIT)).split("\\R", -1));
    }

    public Preview preview(Long documentId, Long versionId, String chunkId, Integer line) {
        if (chunkId != null && (chunkId.isBlank() || chunkId.length() > 128)) {
            throw new IllegalArgumentException("片段编号格式不正确");
        }
        if (line != null && line < 1) {
            throw new IllegalArgumentException("行号必须大于 0");
        }
        KnowledgeSegment segment = chunkId == null || documentId == null ? null
                : segments.getOne(Wrappers.<KnowledgeSegment>lambdaQuery()
                .eq(KnowledgeSegment::getDocumentId, documentId)
                .eq(KnowledgeSegment::getChunkId, chunkId));
        if (chunkId != null && versionId == null && segment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "这条历史引用的片段已不存在");
        }
        if (segment != null && versionId != null && !versionId.equals(segment.getDocumentVersion())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "片段不属于所选版本");
        }
        Long selectedVersion = segment == null ? versionId : segment.getDocumentVersion();
        VersionSource source = resolve(documentId, selectedVersion);
        List<String> lines;
        try {
            lines = readText(source);
        } catch (ResponseStatusException e) {
            if (source.fileType() != FileType.PDF || (e.getStatusCode() != HttpStatus.PAYLOAD_TOO_LARGE
                    && e.getStatusCode() != HttpStatus.CONFLICT)) {
                throw e;
            }
            lines = List.of();
        }
        if (line != null && line > lines.size() && !lines.isEmpty()) {
            throw new IllegalArgumentException("行号超出原文范围");
        }
        String excerpt = segment != null ? shorten(segment.getText(), 2500) : "";
        if (excerpt.isBlank() && line != null && line <= lines.size()) {
            excerpt = shorten(lines.get(line - 1), 2500);
        }
        Integer matchedLine = line != null ? line : locateLine(lines, excerpt);
        LocatedPage page = source.fileType() == FileType.PDF ? locatePage(source, excerpt) : null;
        int first = lines.isEmpty() || matchedLine == null ? 0 : Math.max(0, matchedLine - 16);
        int end = Math.min(lines.size(), first + 40);
        String pdfUrl = source.fileType() == FileType.PDF
                ? "/api/knowledge/pdf/" + documentId + "/" + source.version().getVersionId() : null;
        return new Preview(source.cite(chunkId, matchedLine, excerpt), source.fileType().name(), first + 1,
                lines.subList(first, end).stream().map(value -> shorten(value, 1600)).toList(),
                page == null ? null : page.page(), page == null ? List.of() : page.box(), pdfUrl);
    }

    public InputStream openPdf(Long documentId, Long versionId) {
        VersionSource source = resolve(documentId, versionId);
        if (source.fileType() != FileType.PDF) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "所选版本不是 PDF");
        }
        try {
            return storage.downloadFile(source.objectName());
        } catch (Exception e) {
            throw storageError(e);
        }
    }

    private byte[] readBytes(String objectName, int limit) {
        try (InputStream input = storage.downloadFile(objectName)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "所选文件超过此功能的读取上限");
            }
            return bytes;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw storageError(e);
        }
    }

    private static ResponseStatusException storageError(Exception error) {
        if (error instanceof ErrorResponseException minio && "NoSuchKey".equals(minio.errorResponse().code())) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, "版本文件已不存在", error);
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "暂时无法读取版本文件", error);
    }

    private LocatedPage locatePage(VersionSource source, String excerpt) {
        if (excerpt.isBlank() || source.version().getConvertedDocUrl() == null) {
            return null;
        }
        String objectName = storage.objectNameFromUrl(source.version().getConvertedDocUrl()) + ".locations.json";
        try (InputStream input = storage.downloadFile(objectName)) {
            byte[] bytes = input.readNBytes(LOCATION_LIMIT + 1);
            return bytes.length > LOCATION_LIMIT ? null : locatePage(json.readTree(bytes), excerpt);
        } catch (Exception e) {
            log.debug("PDF 结构化定位不可用，versionId={}", source.version().getVersionId());
            return null;
        }
    }

    static LocatedPage locatePage(JsonNode blocks, String excerpt) {
        if (blocks == null || !blocks.isArray()) {
            return null;
        }
        String needle = normalize(excerpt);
        int best = 0;
        LocatedPage location = null;
        boolean ambiguous = false;
        for (JsonNode block : blocks) {
            int pageIndex = block.path("page_idx").asInt(-1);
            int score = overlap(needle, normalize(blockText(block)));
            if (pageIndex < 0 || score == 0) {
                continue;
            }
            if (score > best) {
                best = score;
                location = new LocatedPage(pageIndex + 1, readBox(block.path("bbox")));
                ambiguous = false;
            } else if (score == best && location.page() != pageIndex + 1) {
                ambiguous = true;
            }
        }
        return ambiguous ? null : location;
    }

    private static String blockText(JsonNode block) {
        StringBuilder text = new StringBuilder();
        for (String field : List.of("text", "table_body", "table_caption", "img_caption")) {
            JsonNode value = block.path(field);
            if (value.isArray()) {
                value.forEach(item -> text.append(item.asText()).append(' '));
            } else {
                text.append(value.asText()).append(' ');
            }
        }
        return text.toString().replaceAll("<[^>]*>", " ");
    }

    static Integer locateLine(List<String> lines, String excerpt) {
        String needle = normalize(excerpt);
        int best = 0;
        Integer line = null;
        for (int index = 0; index < lines.size(); index++) {
            int score = overlap(needle, normalize(lines.get(index)));
            if (score > best) {
                best = score;
                line = index + 1;
            } else if (score > 0 && score == best) {
                line = null;
            }
        }
        return line;
    }

    private static int overlap(String first, String second) {
        int shortest = Math.min(first.length(), second.length());
        if (shortest >= 8 && (first.contains(second) || second.contains(first))) {
            return shortest;
        }
        if (shortest >= 24 && (first.contains(second.substring(0, 24)) || second.contains(first.substring(0, 24)))) {
            return 24;
        }
        return 0;
    }

    private static List<Integer> readBox(JsonNode node) {
        if (!node.isArray() || node.size() != 4) {
            return List.of();
        }
        List<Integer> box = new ArrayList<>(4);
        for (JsonNode coordinate : node) {
            if (!coordinate.isNumber() || coordinate.asInt() < 0 || coordinate.asInt() > 1000) {
                return List.of();
            }
            box.add(coordinate.asInt());
        }
        return box.get(2) > box.get(0) && box.get(3) > box.get(1) ? List.copyOf(box) : List.of();
    }

    static String normalize(String value) {
        return value == null ? "" : NON_SEARCH_TEXT.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    static String shorten(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }
}
