package com.shuimumu.fuluogame.mq;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import com.shuimumu.fuluogame.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class EsSyncConsumer {

    @Autowired
    private ElasticsearchClient esClient;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_ES_INDEX)
    public void handleESIndex(Map<String, Object> doc) {
        try {
            String postId = doc.get("postId").toString();
            esClient.index(IndexRequest.of(i -> i.index("posts").id(postId).document(doc)));
            log.info("ES index 完成 postId={}", postId);
        } catch (Exception e) {
            log.error("ES index 失败 doc={}", doc, e);
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    @RabbitListener(queues = RabbitMQConfig.QUEUE_ES_UPDATE)
    public void handleESUpdate(Map<String, Object> doc) {
        try {
            String postId = doc.get("postId").toString();
            esClient.update(UpdateRequest.of(u -> u.index("posts").id(postId).doc(
                    Map.of("title", doc.getOrDefault("title", ""),
                           "content", doc.getOrDefault("content", ""))
            )), Map.class);
            log.info("ES update 完成 postId={}", postId);
        } catch (Exception e) {
            log.error("ES update 失败 doc={}", doc, e);
            throw new RuntimeException(e);
        }
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_ES_DELETE)
    public void handleESDelete(Map<String, Object> doc) {
        try {
            String postId = doc.get("postId").toString();
            esClient.delete(DeleteRequest.of(d -> d.index("posts").id(postId)));
            log.info("ES delete 完成 postId={}", postId);
        } catch (Exception e) {
            log.error("ES delete 失败 doc={}", doc, e);
            throw new RuntimeException(e);
        }
    }
}
