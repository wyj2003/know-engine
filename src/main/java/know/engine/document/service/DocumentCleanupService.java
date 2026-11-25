package know.engine.document.service;

/**
 * 文档版本清理服务
 * 负责清理旧版本的分段和向量数据（影子更新收尾）
 */
public interface DocumentCleanupService {

    /**
     * 清理明确指定版本的 ES 向量；保留数据库中的分段以支持重新激活。
     * 调用方必须传待停用的版本，不能把当前版本 ID 当成“保留版本”传入。
     *
     * @param docId            文档ID
     * @param versionId 待清理的版本ID
     */
    boolean cleanupOldVersionData(Long docId, Long versionId);
}
