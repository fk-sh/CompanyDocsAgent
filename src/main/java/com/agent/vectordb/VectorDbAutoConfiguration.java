package com.agent.vectordb;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.agent.core.EmbeddingService;
import com.agent.ingestion.FullIngestionPipeline;
import com.agent.ingestion.IngestionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量数据库自动配置类，条件装配 ES 向量存储和全链路摄入管道。
 * <p>
 * 仅在以下条件全部满足时激活：
 * <ul>
 *   <li>classpath 存在 {@link ElasticsearchClient}（即引入了 ES 依赖）</li>
 *   <li>配置了 {@code spring.elasticsearch.uris}</li>
 * </ul>
 * <p>
 * 这样测试环境不配置 ES 时，整个向量/ES 模块自动关闭，不影响其他 Bean。
 */
@Configuration
@ConditionalOnClass(ElasticsearchClient.class)
@ConditionalOnProperty(prefix = "spring.elasticsearch", name = "uris")
public class VectorDbAutoConfiguration {

    @Bean
    @ConditionalOnBean(ElasticsearchClient.class)
    public ElasticsearchVectorStore elasticsearchVectorStore(ElasticsearchClient esClient) {
        return new ElasticsearchVectorStore(esClient);
    }

    @Bean
    @ConditionalOnBean(ElasticsearchVectorStore.class)
    public FullIngestionPipeline fullIngestionPipeline(
            IngestionService ingestionService,
            EmbeddingService embeddingService,
            ElasticsearchVectorStore vectorStore) {
        return new FullIngestionPipeline(ingestionService, embeddingService, vectorStore);
    }
}
