package com.agent.vectordb;

import java.util.Optional;

/**
 * Embedding 向量缓存接口，避免重复调用 Embedding API 对相同文本生成向量。
 * <p>
 * 生产环境由 {@link RedisEmbeddingCache} 实现，基于 Redis 跨实例共享缓存。
 * 测试环境由 {@code EmbeddingCacheTestConfig} 提供的 {@code InMemoryEmbeddingCache} 实现。
 * <p>
 * 缓存的 key 为原始文本的 SHA-256 摘要，value 为 1024 维 float 向量（JSON 序列化）。
 */
public interface EmbeddingCache {

    /**
     * 按文本获取缓存的向量，未命中时返回 {@link Optional#empty()}。
     *
     * @param text 原始文本
     * @return 缓存的向量（可能为空）
     */
    Optional<float[]> get(String text);

    /**
     * 将文本及其向量写入缓存。
     *
     * @param text      原始文本
     * @param embedding 1024 维 float 向量
     */
    void put(String text, float[] embedding);

    /**
     * @return 缓存中大致条目数，用于监控
     */
    long size();

    /**
     * 清空全部缓存。
     */
    void clear();
}
