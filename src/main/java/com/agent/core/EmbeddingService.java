package com.agent.core;

import java.util.List;

/**
 * 向量化服务接口，将文本转为稠密向量。
 * <p>
 * 使用 bge-large-zh-v1.5 模型，输出 1024 维 float 向量。
 * 向量用于 ES 的 KNN 检索和长期记忆的语义检索。
 * <p>
 * 实现类应内置 Redis 缓存，避免重复向量化相同文本。
 */
public interface EmbeddingService {

    /**
     * 将单条文本向量化,文本向量化为1024 维 float 向量
     *
     * @param text 输入文本
     * @return 1024 维 float 向量
     */
    float[] embed(String text);

    /**
     * 批量向量化，默认顺序调用 {@link #embed(String)}。
     * 实现类可重写为批量调用 Embedding API 以提升吞吐。
     *
     * @param texts 文本列表
     * @return 向量列表，顺序与输入一致
     */
    default List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    /**
     * @return 向量维度（bge-large-zh-v1.5 = 1024）
     */
    int dimension();
}
