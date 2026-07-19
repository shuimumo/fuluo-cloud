package com.shuimumu.fuluogame.utils;

import cn.hutool.json.JSONObject;
import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.ImageModerationRequest;
import com.aliyun.green20220302.models.ImageModerationResponse;
import com.aliyun.green20220302.models.ImageModerationResponseBody;
import com.aliyun.green20220302.models.TextModerationPlusRequest;
import com.aliyun.green20220302.models.TextModerationPlusResponse;
import com.aliyun.green20220302.models.TextModerationPlusResponseBody;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "aliyun.green", name = "access-key-id")
public class ContentModerationUtil {

    public static final String NICKNAME_DETECTION_PRO = "nickname_detection_pro";
    public static final String CHAT_DETECTION_PRO = "chat_detection_pro";
    public static final String COMMENT_DETECTION_PRO = "comment_detection_pro";
    public static final String AD_COMPLIANCE_DETECTION_PRO = "ad_compliance_detection_pro";

    @Autowired
    private Client greenClient;

    @Autowired
    private RedissonClient redissonClient;

    private RRateLimiter textLimiter;
    private RRateLimiter imageLimiter;

    @PostConstruct
    void initRateLimiter() {
        textLimiter = redissonClient.getRateLimiter("green:text_rate_limiter");
        textLimiter.trySetRate(RateType.OVERALL, 100, 1, RateIntervalUnit.SECONDS);
        imageLimiter = redissonClient.getRateLimiter("green:image_rate_limiter");
        imageLimiter.trySetRate(RateType.OVERALL, 100, 1, RateIntervalUnit.SECONDS);
    }

    public enum Action { PASS, REJECT, RETRY, MANUAL_REVIEW }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModerationResult {
        private boolean passed;
        private Action action = Action.REJECT;
        private String riskLevel;
        private String label;
        private String description;
        private String riskWords;
        private double confidence;
    }

    public ModerationResult moderateNickname(String nickname) {
        return doModerate(nickname, NICKNAME_DETECTION_PRO);
    }

    public ModerationResult moderateChat(String content) {
        return doModerate(content, CHAT_DETECTION_PRO);
    }

    public ModerationResult moderateComment(String content) {
        return doModerate(content, COMMENT_DETECTION_PRO);
    }

    public ModerationResult moderateAdCompliance(String content) {
        return doModerate(content, AD_COMPLIANCE_DETECTION_PRO);
    }

    public ModerationResult moderate(String content, String serviceType) {
        return doModerate(content, serviceType);
    }

    /**
     * 图片审核。imageUrl 为 OSS 公网地址
     */
    public ModerationResult moderateImage(String imageUrl) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            return new ModerationResult(true, Action.PASS, "none", null, null, null, 0);
        }
        JSONObject params = new JSONObject();
        params.set("imageUrl", imageUrl);
        ImageModerationRequest request = new ImageModerationRequest();
        request.setService("postImageCheck");
        request.setServiceParameters(params.toString());

        try {
            imageLimiter.acquire();
            ImageModerationResponse response = greenClient.imageModeration(request);

            // 1. HTTP 状态码
            if (response.getStatusCode() != 200) {
                log.error("Green 图片HTTP异常 statusCode={}", response.getStatusCode());
                return new ModerationResult(false, Action.REJECT, "error", null,
                        "HTTP " + response.getStatusCode(), null, 0);
            }

            // 2. 响应体
            ImageModerationResponseBody body = response.getBody();
            if (body == null) {
                return new ModerationResult(true, Action.PASS, "none", null, null, null, 0);
            }

            // 3. 业务 code（200=成功）
            if (body.getCode() == null || body.getCode() != 200) {
                log.error("Green 图片业务错误 code={} msg={}", body.getCode(), body.getMsg());
                return new ModerationResult(false, Action.REJECT, "error", null,
                        "API code: " + body.getCode(), null, 0);
            }

            // 4. 审核结果数据
            ImageModerationResponseBody.ImageModerationResponseBodyData data = body.getData();
            if (data == null) {
                return new ModerationResult(true, Action.PASS, "none", null, null, null, 0);
            }

            log.info("Green 图片审核响应 riskLevel={}", data.getRiskLevel());

            // 5. 风险等级
            String riskLevel = data.getRiskLevel() != null ? data.getRiskLevel() : "none";
            if ("none".equals(riskLevel)) {
                return new ModerationResult(true, Action.PASS, "none", null, null, null, 0);
            }

            // 6. 命中详情
            var resultList = data.getResult();
            if (resultList == null || resultList.isEmpty()) {
                return new ModerationResult(false, Action.REJECT, riskLevel, null, null, null, 0);
            }

            var firstHit = resultList.get(0);
            String label = firstHit.getLabel();             // 违规标签
            String description = firstHit.getDescription(); // 命中描述
            double confidence = firstHit.getConfidence() != null ? firstHit.getConfidence() : 0.0;

            log.info("Green 图片审核命中 riskLevel={} label={} description={} confidence={}",
                    riskLevel, label, description, confidence);
            return new ModerationResult(false, Action.REJECT, riskLevel, label, description, null, confidence);

        } catch (Exception e) {
            log.error("Green 图片审核调用异常 imageUrl={}", imageUrl, e);
            return new ModerationResult(false, Action.REJECT, "error", null,
                    "API调用异常: " + e.getMessage(), null, 0);
        }
    }

    private ModerationResult doModerate(String content, String serviceType) {
        if (content == null || content.trim().isEmpty()) {
            return new ModerationResult(true, Action.PASS, "none", null, null, null, 0);
        }
        if (content.length() > 600) {
            content = content.substring(0, 600);
        }

        JSONObject serviceParams = new JSONObject();
        serviceParams.set("content", content);
        TextModerationPlusRequest request = new TextModerationPlusRequest();
        request.setService(serviceType);
        request.setServiceParameters(serviceParams.toString());

        try {
            textLimiter.acquire();
            TextModerationPlusResponse response = greenClient.textModerationPlus(request);

            // 1. HTTP 状态码
            int statusCode = response.getStatusCode();
            if (statusCode != 200) {
                log.error("Green HTTP异常 statusCode={}", statusCode);
                Action action = (statusCode == 500 || statusCode == 581) ? Action.RETRY
                        : (statusCode == 588) ? Action.MANUAL_REVIEW : Action.REJECT;
                return new ModerationResult(false, action, "error", null,
                        "HTTP " + statusCode, null, 0);
            }

            // 2. 响应体
            TextModerationPlusResponseBody body = response.getBody();
            if (body == null) {
                return new ModerationResult(true, Action.PASS, "none", null, null, null, 0);
            }

            // 3. 业务 code（200=成功）
            Integer bizCode = body.getCode();
            if (bizCode == null || bizCode != 200) {
                log.error("Green 业务错误 code={} msg={}", bizCode, body.getMessage());
                Action action = (bizCode != null && (bizCode == 500 || bizCode == 581)) ? Action.RETRY
                        : (bizCode != null && bizCode == 588) ? Action.MANUAL_REVIEW : Action.REJECT;
                return new ModerationResult(false, action, "error", null,
                        "API code: " + bizCode, null, 0);
            }

            // 4. 审核结果数据
            TextModerationPlusResponseBody.TextModerationPlusResponseBodyData data = body.getData();
            if (data == null) {
                return new ModerationResult(true, Action.PASS, "none", null, null, null, 0);
            }

            log.info("Green 审核响应 riskLevel={} rawData={}", data.getRiskLevel(),
                    cn.hutool.json.JSONUtil.toJsonStr(data));

            // 5. 风险等级：none=通过，high=违规，medium=疑似
            String riskLevel = data.getRiskLevel() != null ? data.getRiskLevel() : "none";
            if ("none".equals(riskLevel)) {
                return new ModerationResult(true, Action.PASS, "none", null, null, null, 0);
            }

            // 6. 命中详情：违规标签 + 描述 + 敏感词 + 置信度
            var resultList = data.getResult();
            if (resultList == null || resultList.isEmpty()) {
                return new ModerationResult(false, Action.REJECT, riskLevel, null, null, null, 0);
            }

            var firstHit = resultList.get(0);
            String label = firstHit.getLabel();             // 违规标签 e.g. "political_content"
            String description = firstHit.getDescription(); // 命中描述
            String riskWords = firstHit.getRiskWords();     // 命中的敏感词
            double confidence = firstHit.getConfidence() != null ? firstHit.getConfidence() : 0.0; // 置信度 0-1

            log.info("Green 审核命中 riskLevel={} label={} description={} riskWords={} confidence={}",
                    riskLevel, label, description, riskWords, confidence);
            return new ModerationResult(false, Action.REJECT, riskLevel, label, description, riskWords, confidence);

        } catch (Exception e) {
            log.error("Green 审核调用异常 contentHead={}",
                    content.substring(0, Math.min(50, content.length())), e);
            return new ModerationResult(false, Action.RETRY, "error", null,
                    "API调用异常: " + e.getMessage(), null, 0);
        }
    }
}
