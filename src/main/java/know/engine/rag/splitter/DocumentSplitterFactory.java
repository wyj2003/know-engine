package know.engine.rag.splitter;

import know.engine.document.constant.SplitType;
import know.engine.document.entity.DocumentSplitParam;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentByRegexSplitter;
import dev.langchain4j.data.document.splitter.DocumentByWordSplitter;

public class DocumentSplitterFactory {

    public static DocumentSplitter getInstance(DocumentSplitParam documentSplitParam) {
        org.springframework.util.Assert.notNull(documentSplitParam.chunkSize(), "chunkSize 不能为空");
        int overlap = documentSplitParam.overlap() == null ? 0 : documentSplitParam.overlap();
        org.springframework.util.Assert.isTrue(documentSplitParam.chunkSize() > 0
                && overlap >= 0 && overlap < documentSplitParam.chunkSize(), "chunkSize 必须大于 overlap 且 overlap 不能为负数");
        documentSplitParam = new DocumentSplitParam(documentSplitParam.splitType(), documentSplitParam.chunkSize(),
                overlap, documentSplitParam.titleLevel() == null ? 2 : documentSplitParam.titleLevel(),
                documentSplitParam.separator(), documentSplitParam.regex());
        if (SplitType.LENGTH.name().equals(documentSplitParam.splitType())) {
            return new DocumentByWordSplitter(documentSplitParam.chunkSize(), documentSplitParam.overlap());
        }

        if (SplitType.TITLE.name().equals(documentSplitParam.splitType())) {
            return new MarkdownHeaderParentTextSplitter(documentSplitParam.titleLevel(), false, false, documentSplitParam.chunkSize(), documentSplitParam.overlap());
        }

        if (SplitType.REGEX.name().equals(documentSplitParam.splitType())) {
            org.springframework.util.Assert.hasText(documentSplitParam.regex(), "regex 不能为空");
            return new DocumentByRegexSplitter(documentSplitParam.regex(), "\n\n", documentSplitParam.chunkSize(), documentSplitParam.overlap(),
                    new dev.langchain4j.data.document.splitter.DocumentByCharacterSplitter(documentSplitParam.chunkSize(), overlap));
        }

        if (SplitType.SMART.name().equals(documentSplitParam.splitType())) {
            return new MarkdownHeaderParentTextSplitter(documentSplitParam.chunkSize(), (int) (documentSplitParam.chunkSize() * 0.1));
        }

        if (SplitType.SEPARATOR.name().equals(documentSplitParam.splitType())) {
            org.springframework.util.Assert.hasText(documentSplitParam.separator(), "separator 不能为空");
            return new DocumentByRegexSplitter(java.util.regex.Pattern.quote(documentSplitParam.separator()), "\n\n", documentSplitParam.chunkSize(), documentSplitParam.overlap(),
                    new dev.langchain4j.data.document.splitter.DocumentByCharacterSplitter(documentSplitParam.chunkSize(), overlap));
        }
        throw new IllegalArgumentException("未知的 splitType: " + documentSplitParam.splitType());
    }

}
