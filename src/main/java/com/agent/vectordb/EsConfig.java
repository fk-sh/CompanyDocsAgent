package com.agent.vectordb;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "spring.elasticsearch", name = "uris")
public class EsConfig {

    private final org.springframework.core.env.Environment env;

    public EsConfig(org.springframework.core.env.Environment env) {
        this.env = env;
    }

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        String uris = env.getProperty("spring.elasticsearch.uris", "http://localhost:9200");
        String username = env.getProperty("spring.elasticsearch.username");
        String password = env.getProperty("spring.elasticsearch.password");

        String[] uriParts = uris.split(",");
        HttpHost[] hosts = new HttpHost[uriParts.length];
        for (int i = 0; i < uriParts.length; i++) {
            hosts[i] = HttpHost.create(uriParts[i].trim());
        }

        RestClientBuilder restClientBuilder = RestClient.builder(hosts);

        if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
            CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(username, password));
            restClientBuilder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
        }

        RestClient restClient = restClientBuilder.build();

        ObjectMapper esObjectMapper = new ObjectMapper();
        esObjectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

        JacksonJsonpMapper jsonpMapper = new JacksonJsonpMapper(esObjectMapper);

        RestClientTransport transport = new RestClientTransport(restClient, jsonpMapper);
        return new ElasticsearchClient(transport);
    }
}
