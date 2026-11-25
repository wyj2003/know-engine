package know.engine.document.service.impl;

import know.engine.document.constant.FileType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * MinerU 文档处理：PDF / Word 统一走基类 ZIP→Markdown 转换流程。
 */
@Slf4j
@Service
public class MinerUFileProcessServiceImpl extends MinerUProcessBaseServiceImpl {
    @org.springframework.beans.factory.annotation.Autowired
    private MarkdownProcessServiceImpl markdownProcessService;

    @org.springframework.beans.factory.annotation.Autowired
    private know.engine.document.service.FileStorageService fileStorageService;

    @Override
    public String processDocument(know.engine.document.entity.Document document,
                                  java.io.InputStream inputStream, String originalFileName) {
        // MinerU accepts OOXML .docx, but not the older OLE2 .doc format advertised by this project.
        if (originalFileName.toLowerCase(java.util.Locale.ROOT).endsWith(".doc")) {
            try (inputStream;
                 org.apache.poi.hwpf.HWPFDocument word = new org.apache.poi.hwpf.HWPFDocument(inputStream);
                 org.apache.poi.hwpf.extractor.WordExtractor extractor = new org.apache.poi.hwpf.extractor.WordExtractor(word)) {
                StringBuilder text = new StringBuilder(extractor.getText().replace('\r', '\n'));
                int imageNumber = 0;
                for (org.apache.poi.hwpf.usermodel.Picture picture : word.getPicturesTable().getAllPictures()) {
                    String objectName = "converted/" + document.getDocId() + "/" + document.getCurrentVersionId()
                            + "/images/legacy-" + (++imageNumber) + "." + picture.suggestFileExtension();
                    String imageUrl = fileStorageService.uploadFile(objectName, picture.getContent(), picture.getMimeType());
                    text.append("\n\n![文档图片](").append(imageUrl).append(")\n");
                }
                return markdownProcessService.processDocument(document,
                        new java.io.ByteArrayInputStream(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        originalFileName + ".md");
            } catch (Exception e) {
                throw new IllegalStateException("DOC 转换失败: " + e.getMessage(), e);
            }
        }
        return super.processDocument(document, inputStream, originalFileName);
    }

    @Override
    public boolean supports(FileType fileType) {
        return fileType == FileType.PDF || fileType == FileType.DOC;
    }
}
