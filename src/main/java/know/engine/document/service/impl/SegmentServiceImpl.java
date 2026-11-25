package know.engine.document.service.impl;

import know.engine.document.entity.KnowledgeSegment;
import know.engine.document.mapper.SegmentMapper;
import know.engine.document.service.SegmentService;
import know.engine.document.service.VectorStoreService;
import know.engine.rag.constant.MetadataKeyConstant;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 知识片段表 Service 实现类
 */
@Slf4j
@Service
public class SegmentServiceImpl extends ServiceImpl<SegmentMapper, KnowledgeSegment> implements SegmentService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private VectorStoreService vectorStoreService;

    @Override
    public String getTextByChunkId(Serializable chunkId) {
        String text = stringRedisTemplate.opsForValue().get(chunkId);
        if (text != null) {
            if (text.isEmpty()) {
                return null;
            }
            return text;
        }

        QueryWrapper<KnowledgeSegment> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("chunk_id", chunkId);
        KnowledgeSegment segment = super.getOne(queryWrapper);

        if (segment != null) {
            stringRedisTemplate.opsForValue().set(chunkId.toString(), segment.getText(), 30, TimeUnit.SECONDS);
            return segment.getText();
        } else {
            // 缓存空值，避免缓存击穿，重复查询数据库
            stringRedisTemplate.opsForValue().set(chunkId.toString(), "", 30, TimeUnit.SECONDS);
        }

        return null;
    }

    /**
     * 根据文档ID物理删除所有分段（绕过 @TableLogic）
     */
    @Override
    public int removeSegmentsByDocId(Long docId) {
        return baseMapper.physicalDeleteByDocumentId(docId);
    }

    /**
     * 删除分段，同时删除向量存储中对应的向量（物理删除）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {
        // 先查询分段获取 embeddingId，再删除向量
        KnowledgeSegment segment = super.getById(id);
        if (segment != null) {
            if (Integer.valueOf(1).equals(segment.getSkipEmbedding())) {
                List<Long> ids = super.list(new QueryWrapper<KnowledgeSegment>()
                        .eq("document_id", segment.getDocumentId())
                        .eq("document_version", segment.getDocumentVersion())).stream()
                        .filter(s -> s.getId().equals(segment.getId()) || (s.getMetadataMap() != null
                                && segment.getChunkId().equals(s.getMetadataMap().get(MetadataKeyConstant.PARENT_CHUNK_ID))))
                        .map(KnowledgeSegment::getId).toList();
                return removeByIds(ids);
            }
            vectorStoreService.remove(segment.getEmbeddingId());
            int removed = baseMapper.physicalDeleteById((Long) id);
            syncParentSegmentText(segment, "");
            stringRedisTemplate.delete(segment.getChunkId());
            return removed > 0;
        }
        // 物理删除，绕过 @TableLogic
        return baseMapper.physicalDeleteById((Long) id) > 0;
    }

    /**
     * 批量删除分段，同时删除向量存储中对应的向量（物理删除）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeByIds(Collection<?> idList) {
        // 先查询分段获取 embeddingId 列表，再批量删除向量
        QueryWrapper<KnowledgeSegment> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("id", idList);
        List<KnowledgeSegment> segments = super.list(queryWrapper);
        List<String> embeddingIds = segments.stream()
                .map(KnowledgeSegment::getEmbeddingId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        vectorStoreService.removeAll(embeddingIds);
        int removed = baseMapper.physicalDeleteByIds(idList);
        for (KnowledgeSegment segment : segments) {
            stringRedisTemplate.delete(segment.getChunkId());
            if (!Integer.valueOf(1).equals(segment.getSkipEmbedding())) {
                syncParentSegmentText(segment, "");
            }
        }
        // 物理删除，绕过 @TableLogic
        return removed > 0;
    }

    /**
     * 更新分段，当文本内容变更时同步更新向量数据库（遵循分段更新与向量库同步规范）：
     * 1. 若子分段文本变更，同步更新对应父分段文本及 Redis 缓存；
     * 2. 仅当文本内容变更 && 已有 embeddingId && skipEmbedding ≠ 1 时，
     *    先写新向量，再更新数据库和清理旧向量；写入失败时保留原文本和原向量。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateById(KnowledgeSegment entity, Boolean updateVectorStore) {
        KnowledgeSegment oldSegment = super.getById(entity.getId());
        if (oldSegment == null) {
            return super.updateById(entity);
        }

        boolean textChanged = entity.getText() != null && !entity.getText().equals(oldSegment.getText());

        if (updateVectorStore) {
            boolean hasEmbedding = oldSegment.getEmbeddingId() != null;
            boolean skipEmbedding = oldSegment.getSkipEmbedding() != null && oldSegment.getSkipEmbedding() == 1;

            if (textChanged && hasEmbedding && !skipEmbedding) {
                // Write the replacement first: a failed embedding must preserve the old text/vector.
                try {
                    // 若更新请求未携带 metadata，复用旧分段的 metadata，避免检索过滤信息丢失
                    if (entity.getMetadata() == null && oldSegment.getMetadata() != null) {
                        entity.setMetadata(oldSegment.getMetadata());
                    }
                    String newEmbeddingId = vectorStoreService.embedAndStore(entity);
                    entity.setEmbeddingId(newEmbeddingId);
                    if (!super.updateById(entity)) {
                        vectorStoreService.remove(newEmbeddingId);
                        throw new IllegalStateException("分段已被其他请求修改，请刷新重试");
                    }
                    vectorStoreService.remove(oldSegment.getEmbeddingId());
                    syncParentSegmentText(oldSegment, entity.getText());
                    stringRedisTemplate.delete(oldSegment.getChunkId());
                    log.info("更新向量成功, segmentId: {}, oldEmbeddingId: {}, newEmbeddingId: {}",
                            entity.getId(), oldSegment.getEmbeddingId(), newEmbeddingId);
                    return true;
                } catch (Exception e) {
                    throw new IllegalStateException("更新向量失败，原分段未修改", e);
                }
            }

        }

        boolean updated = super.updateById(entity);
        // A rejected optimistic-lock update must not change the parent either.
        if (updated && textChanged) {
            syncParentSegmentText(oldSegment, entity.getText());
            stringRedisTemplate.delete(oldSegment.getChunkId());
        }

        return updated;
    }

    /**
     * 当子分段文本变更时，同步更新对应父分段的文本内容。
     * 父分段存储完整文本（skipEmbedding=1），子分段是其中的子串。
     * 按剩余子分段的顺序重建父分段，并清除 Redis 缓存，兼容重叠区域和子分段删除。
     *
     * @param oldSegment 修改前的子分段（包含旧文本和 metadata）
     * @param newText   修改后的新文本
     */
    private void syncParentSegmentText(KnowledgeSegment oldSegment, String newText) {
        Map<String, String> metadataMap = oldSegment.getMetadataMap();
        if (metadataMap == null) {
            return;
        }
        String parentChunkId = metadataMap.get(MetadataKeyConstant.PARENT_CHUNK_ID);
        if (parentChunkId == null) {
            return;
        }

        // 查找父分段
        QueryWrapper<KnowledgeSegment> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("chunk_id", parentChunkId);
        KnowledgeSegment parentSegment = super.getOne(queryWrapper);
        if (parentSegment == null) {
            log.warn("子分段修改后同步父分段失败：未找到父分段, parentChunkId: {}", parentChunkId);
            return;
        }

        // 将父分段文本中的旧子分段文本替换为新文本
        // Rebuild from surviving children; substring replacement breaks after an overlapping neighbour is deleted.
        List<KnowledgeSegment> children = super.list(new QueryWrapper<KnowledgeSegment>()
                .eq("document_id", oldSegment.getDocumentId())
                .eq("document_version", oldSegment.getDocumentVersion())
                .apply("JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.parentChunkId')) = {0}", parentChunkId)
                .orderByAsc("chunk_order"));
        StringBuilder rebuilt = new StringBuilder();
        Integer previousOrder = null;
        for (KnowledgeSegment child : children) {
            String text = child.getId().equals(oldSegment.getId()) ? newText : child.getText();
            if (text == null || text.isEmpty()) continue;
            Map<String, String> childMetadata = child.getMetadataMap();
            Object overlapValue = childMetadata == null ? null : ((Map<?, ?>) childMetadata).get("chunkOverlap");
            int overlap = overlapValue == null ? 0 : Integer.parseInt(overlapValue.toString());
            int matched = 0;
            if (previousOrder != null && child.getChunkOrder() == previousOrder + 1) {
                for (int size = Math.min(overlap, Math.min(rebuilt.length(), text.length())); size > 0; size--) {
                    if (rebuilt.substring(rebuilt.length() - size).equals(text.substring(0, size))) {
                        matched = size;
                        break;
                    }
                }
            }
            if (!rebuilt.isEmpty() && matched == 0) rebuilt.append('\n');
            rebuilt.append(text.substring(matched));
            previousOrder = child.getChunkOrder();
        }
        parentSegment.setText(rebuilt.toString());
        org.springframework.util.Assert.isTrue(super.updateById(parentSegment), "父分段已被其他请求修改，请重试");
        stringRedisTemplate.delete(parentChunkId);
    }
}
