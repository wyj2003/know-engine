package know.engine.document.service.impl;

import know.engine.document.entity.KnowledgeSegment;
import know.engine.document.service.VectorStoreService;
import know.engine.rag.constant.MetadataKeyConstant;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 向量存储服务实现
 * 统一负责文本嵌入、向量写入、向量删除等与 Elasticsearch 向量库的交互。
 */
@Slf4j
@Service
public class VectorStoreServiceImpl implements VectorStoreService {

    @Autowired
    private ElasticsearchEmbeddingStore embeddingStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private org.elasticsearch.client.RestClient restClient;

    /** Make a completed write/delete visible before publishing its database state. */
    private void refreshIndex() {
        try {
            restClient.performRequest(new org.elasticsearch.client.Request("POST", "/"
                    + know.engine.rag.config.ElasticSearchConfiguration.INDEX_NAME + "/_refresh"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("刷新向量索引失败", e);
        }
    }

    @Override
    public List<String> embedAndStore(List<KnowledgeSegment> segments) {
        if (CollectionUtils.isEmpty(segments)) {
            return Collections.emptyList();
        }

        List<TextSegment> textSegments = segments.stream()
                .map(this::toTextSegment)
                .toList();

        Response<List<Embedding>> embeddingResponse = embeddingModel.embedAll(textSegments);
        List<String> embeddingIds = embeddingStore.addAll(embeddingResponse.content(), textSegments);
        refreshIndex();

        Assert.isTrue(embeddingIds.size() == segments.size(),
                "向量存储失败，向量数量与分段数量不一致");

        log.info("批量向量化完成, count: {}", segments.size());
        return embeddingIds;
    }

    @Override
    public String embedAndStore(KnowledgeSegment segment) {
        Assert.notNull(segment, "分段不能为空");
        TextSegment textSegment = toTextSegment(segment);
        Response<Embedding> embeddingResponse = embeddingModel.embed(textSegment.text());
        String embeddingId = embeddingStore.add(embeddingResponse.content(), textSegment);
        refreshIndex();
        log.info("单条向量化完成, segmentId: {}, embeddingId: {}", segment.getId(), embeddingId);
        return embeddingId;
    }

    @Override
    public void remove(String embeddingId) {
        if (embeddingId == null) {
            return;
        }
        try {
            embeddingStore.remove(embeddingId);
            refreshIndex();
            log.info("删除向量成功, embeddingId: {}", embeddingId);
        } catch (Exception e) {
            log.warn("删除向量失败, embeddingId: {}, error: {}", embeddingId, e.getMessage());
            throw new IllegalStateException("向量删除失败，数据库记录保留以便重试", e);
        }
    }

    @Override
    public void removeAll(List<String> embeddingIds) {
        if (CollectionUtils.isEmpty(embeddingIds)) {
            return;
        }
        try {
            embeddingStore.removeAll(embeddingIds);
            refreshIndex();
            log.info("批量删除向量成功, count: {}", embeddingIds.size());
        } catch (Exception e) {
            log.warn("批量删除向量失败, count: {}, error: {}", embeddingIds.size(), e.getMessage());
            throw new IllegalStateException("向量删除失败，数据库记录保留以便重试", e);
        }
    }

    @Override
    public void removeByDocId(Long docId) {
        try {
            Filter filter = metadataKey(MetadataKeyConstant.DOC_ID).isEqualTo(docId);
            refreshIndex();
            embeddingStore.removeAll(filter);
            refreshIndex();
            log.info("按docId删除向量成功, docId: {}", docId);
        } catch (Exception e) {
            log.warn("按docId删除向量失败, docId: {}, error: {}", docId, e.getMessage());
            throw new IllegalStateException("向量删除失败，数据库记录保留以便重试", e);
        }
    }

    @Override
    public void removeByDocIds(List<Long> docIds) {
        if (CollectionUtils.isEmpty(docIds)) {
            return;
        }
        try {
            Filter filter = metadataKey(MetadataKeyConstant.DOC_ID).isIn(docIds);
            refreshIndex();
            embeddingStore.removeAll(filter);
            refreshIndex();
            log.info("按docIds批量删除向量成功, count: {}", docIds.size());
        } catch (Exception e) {
            log.warn("按docIds批量删除向量失败, count: {}, error: {}", docIds.size(), e.getMessage());
            throw new IllegalStateException("向量删除失败，数据库记录保留以便重试", e);
        }
    }

    @Override
    public void removeByDocIdAndVersion(Long docId, Long versionId) {
        try {
            Filter filter = metadataKey(MetadataKeyConstant.DOC_ID).isEqualTo(docId)
                    .and(metadataKey(MetadataKeyConstant.VERSION).isEqualTo(versionId));
            refreshIndex();
            embeddingStore.removeAll(filter);
            refreshIndex();
            log.info("按docId+versionId删除向量成功, docId: {}, versionId: {}", docId, versionId);
        } catch (Exception e) {
            log.warn("按docId+versionId删除向量失败, docId: {}, versionId: {}, error: {}",
                    docId, versionId, e.getMessage());
            throw new IllegalStateException("向量删除失败，数据库记录保留以便重试", e);
        }
    }

    @Override
    public TextSegment toTextSegment(KnowledgeSegment segment) {
        Map<String, String> metadataMap = segment.getMetadataMap();
        Metadata metadata = metadataMap != null ? Metadata.from(metadataMap) : new Metadata();
        return TextSegment.from(segment.getText(), metadata);
    }
}
