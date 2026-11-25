package know.engine.knowledge;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {
    private final KnowledgeSourceService sources;
    private final KnowledgeReviewService review;
    private final TableQueryService tables;

    public record ReviewRequest(Long documentId, Long baselineId, Long candidateId, List<String> questions) { }

    public record ResearchRequest(Long firstDocument, Long firstVersion, Long secondDocument, Long secondVersion,
                                  String question) { }

    public record TableRequest(Long documentId, Long versionId, String sheet, String question, TableQueryService.Query query) { }

    @PostMapping("/version-review")
    public KnowledgeReviewService.Review review(@RequestBody ReviewRequest request) {
        return review.compare(request.documentId(), request.baselineId(), request.candidateId(), request.questions());
    }

    @PostMapping("/research")
    public KnowledgeReviewService.Research research(@RequestBody ResearchRequest request) {
        return review.research(request.firstDocument(), request.firstVersion(), request.secondDocument(),
                request.secondVersion(), request.question());
    }

    @GetMapping("/source")
    public KnowledgeSourceService.Preview preview(@RequestParam Long documentId,
                                                  @RequestParam(required = false) Long versionId,
                                                  @RequestParam(required = false) String chunkId,
                                                  @RequestParam(required = false) Integer line) {
        return sources.preview(documentId, versionId, chunkId, line);
    }

    @GetMapping(value = "/pdf/{documentId}/{versionId}", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<InputStreamResource> pdf(@PathVariable Long documentId, @PathVariable Long versionId) {
        var input = sources.openPdf(documentId, versionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PDF)
                .header("X-Content-Type-Options", "nosniff").body(new InputStreamResource(input));
    }

    @GetMapping("/table/schema")
    public TableQueryService.Schema schema(@RequestParam Long documentId, @RequestParam Long versionId,
                                           @RequestParam(required = false) String sheet) {
        return tables.schema(documentId, versionId, sheet);
    }

    @GetMapping("/table/source-rows")
    public TableQueryService.SourceRows sourceRows(@RequestParam Long documentId, @RequestParam Long versionId,
                                                    @RequestParam(required = false) String sheet,
                                                    @RequestParam List<Integer> rows) {
        return tables.sourceRows(documentId, versionId, sheet, rows);
    }

    @PostMapping("/table/query")
    public TableQueryService.Result table(@RequestBody TableRequest request) {
        if (request.versionId() == null) {
            throw new IllegalArgumentException("请选择表格版本");
        }
        if (request.query() != null) {
            return tables.query(request.documentId(), request.versionId(), request.sheet(), request.query());
        }
        return tables.ask(request.documentId(), request.versionId(), request.sheet(), request.question());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidInput(IllegalArgumentException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail serviceError(ResponseStatusException error) {
        return error.getBody();
    }
}
