package know.engine.document.constant;

public enum DocumentStatus {
    /**
     * 上传完成
     */
    UPLOADED,
    /**
     * 转换中
     */
    CONVERTING,
    /**
     * 转换完成
     */
    CONVERTED,
    /**
     * 分块完成
     */
    CHUNKED,
    /**
     * 向量存储完成
     */
    VECTOR_STORED,
    /** 手动停用，定时补偿不得自动重新激活。 */
    INACTIVE;
}
