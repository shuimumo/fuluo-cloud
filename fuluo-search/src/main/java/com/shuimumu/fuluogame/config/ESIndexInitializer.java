package com.shuimumu.fuluogame.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.StringReader;

@Slf4j
@Component
public class ESIndexInitializer {

    @Autowired
    private ElasticsearchClient esClient;

    @PostConstruct
    public void init() {
        try {
            boolean exists = esClient.indices().exists(e -> e.index("posts")).value();
            if (!exists) {
                esClient.indices().create(CreateIndexRequest.of(c -> c
                        .index("posts")
                        .settings(s -> s
                                .numberOfShards("1")
                                .numberOfReplicas("0")
                                .analysis(a -> a
                                        .analyzer("ik_smart_analyzer", an -> an
                                                .custom(cu -> cu.tokenizer("ik_smart")))
                                        .analyzer("ik_max_word_analyzer", an -> an
                                                .custom(cu -> cu.tokenizer("ik_max_word")))))
                        .mappings(m -> m.properties("title", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                                .properties("content", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                                .properties("userId", p -> p.long_(l -> l))
                                .properties("createTime", p -> p.long_(l -> l)))));
                log.info("ES 索引 posts 创建成功，分词器：ik_max_word");
            }
        } catch (Exception e) {
            log.error("ES 索引初始化失败", e);
        }
    }
}
