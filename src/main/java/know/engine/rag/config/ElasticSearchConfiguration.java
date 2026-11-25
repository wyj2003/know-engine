package know.engine.rag.config;

import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(ElasticSearchProperties.class)
public class ElasticSearchConfiguration {

    @Autowired
    private ElasticSearchProperties properties;

    public static final String INDEX_NAME = "know-engine-vector";

    @Bean
    public EmbeddingModel embeddingModel() {
        if ("local".equalsIgnoreCase(properties.getEmbeddingProvider())) {
            // 量化中文模型在 Java 进程内运行，无需另一个云端 API Key。
            return new BgeSmallZhV15QuantizedEmbeddingModel(Runnable::run);
        }
        return OpenAiEmbeddingModel.builder()
                .modelName(properties.getModelName())
                .dimensions(properties.getDimensions())
                .baseUrl(properties.getBaseUrl())
                .maxSegmentsPerBatch(9)
                .apiKey(properties.getApiKey()).build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public RestClient restClient() {
        return RestClient
                .builder(HttpHost.create(properties.getHost()))
                .build();
    }

    @Primary
    @ConditionalOnMissingBean
    @Bean
    public ElasticsearchEmbeddingStore elasticsearchEmbeddingStore(RestClient restClient) {
        return ElasticsearchEmbeddingStore.builder()
                .restClient(restClient)
                .indexName(INDEX_NAME)
                .build();
    }
}
