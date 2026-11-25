package know.engine.document;

import know.engine.document.constant.DocumentStatus;
import know.engine.document.entity.Document;
import know.engine.document.entity.DocumentVersion;
import know.engine.document.service.DocumentService;
import know.engine.document.service.DocumentVersionService;
import know.engine.document.service.FileProcessServiceFactory;
import know.engine.document.service.FileStorageService;
import know.engine.document.service.impl.DocumentProcessServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentDraftTest {
    @Mock DocumentService documents;
    @Mock DocumentVersionService versions;
    @Mock FileStorageService storage;
    @Mock FileProcessServiceFactory parsers;
    @InjectMocks DocumentProcessServiceImpl process;

    @Test
    void savesTheDraftSnapshotWithoutMovingTheActiveVersionPointer() throws Exception {
        var file = new MockMultipartFile("file", "draft.txt", "text/plain",
                "至少提前 72 小时".getBytes(StandardCharsets.UTF_8));
        String url = "http://storage.test/know-engine/original/8/draft.txt";
        var draft = new AtomicReference<DocumentVersion>();
        when(documents.getById(8L)).thenAnswer(lookup -> {
            Document active = new Document();
            active.setDocId(8L);
            active.setCurrentVersionId(17L);
            active.setStatus(DocumentStatus.VECTOR_STORED);
            return active;
        });
        when(versions.getLatestVersion(8L)).thenReturn("1.0.0");
        when(storage.uploadFile(eq(file), anyString())).thenReturn(url);
        when(versions.save(any(DocumentVersion.class))).thenAnswer(write -> {
            DocumentVersion version = write.getArgument(0);
            version.setVersionId(18L);
            draft.set(version);
            return true;
        });
        when(versions.getById(18L)).thenAnswer(lookup -> draft.get());
        when(versions.updateById(any(DocumentVersion.class))).thenReturn(true);

        Document active = process.uploadNewVersion(8L, "1.0.1", file, "test", "预约更新", true);

        assertThat(active.getCurrentVersionId()).isEqualTo(17L);
        assertThat(active.getStatus()).isEqualTo(DocumentStatus.VECTOR_STORED);
        assertThat(draft.get().getConvertedDocUrl()).isEqualTo(url);
        verify(documents).advanceDocumentAndVersionStatus(8L, 18L, DocumentStatus.CONVERTED);
        verify(documents, never()).updateById(any(Document.class));
    }
}
