package com.shuimumu.fuluogame.gateway.filter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * 网关鉴权全局过滤器（内网信任模式）。
 *
 * <p>职责：
 * <ol>
 *   <li><strong>剥离</strong>客户端伪造的 X-User-* 头，杜绝越权；</li>
 *   <li>只认 {@code Authorization: Bearer <token>}，先查本地 Caffeine（30s TTL，削 Redis 峰值），
 *       未命中再查 Redis {@code login:token:<token>}；</li>
 *   <li>命中则把用户 JSON 注入下游头 {@code X-User-Id/X-User-Role/X-User-Info}。</li>
 *   <li>封禁用户（status != 0）不注入身份头。</li>
 * </ol>
 *
 * <p>安全模型：<strong>强制内网</strong>——各服务只在内网可达（同机 127.0.0.1 / 私网 / 容器网络），
 * 对外只暴露网关端口，因此下游信任网关注入的身份头是安全的。</p>
 *
 * <p>不做拦截：细粒度放行/鉴权仍交由各下游服务自己的 SecurityConfig。</p>
 */
@Slf4j
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    /** 与单体 SystemRedisKeys.loginToken 保持一致 */
    private static final String LOGIN_TOKEN_PREFIX = "login:token:";

    private static final String H_USER_ID = "X-User-Id";
    private static final String H_USER_ROLE = "X-User-Role";
    private static final String H_USER_INFO = "X-User-Info";

    private final ReactiveStringRedisTemplate redis;
    /** 网关本地缓存 token->userJson，短 TTL 挡 Redis 抖动 */
    private final Cache<String, String> tokenCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    public AuthGlobalFilter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // 1. 先剥离客户端伪造的身份头
        ServerHttpRequest stripped = request.mutate()
                .headers(h -> {
                    h.remove(H_USER_ID);
                    h.remove(H_USER_ROLE);
                    h.remove(H_USER_INFO);
                })
                .build();

        String authorization = stripped.getHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return chain.filter(exchange.mutate().request(stripped).build());
        }
        String token = authorization.substring(7);

        String cached = tokenCache.getIfPresent(token);
        if (cached != null) {
            return chain.filter(exchange.mutate().request(inject(stripped, cached)).build());
        }

        return redis.opsForValue().get(LOGIN_TOKEN_PREFIX + token)
                .flatMap(userJson -> {
                    tokenCache.put(token, userJson);
                    return chain.filter(exchange.mutate().request(inject(stripped, userJson)).build());
                })
                // token 无效/过期：不注入身份，交给下游按未登录处理
                .switchIfEmpty(Mono.defer(() ->
                        chain.filter(exchange.mutate().request(stripped).build())));
    }

    /**
     * 注入身份头。封禁用户（status != 0）不注入任何身份头（视为未登录）。
     */
    private ServerHttpRequest inject(ServerHttpRequest base, String userJson) {
        try {
            JSONObject u = JSON.parseObject(userJson);
            if (u.getInteger("status") != null && u.getInteger("status") != 0) {
                log.warn("封禁用户尝试访问，不注入身份 userId={}", u.get("id"));
                return base;
            }
        } catch (Exception e) {
            log.warn("网关解析用户信息失败: {}", e.getMessage());
            return base;
        }

        Consumer<org.springframework.http.HttpHeaders> setter = h -> {
            try {
                JSONObject u = JSON.parseObject(userJson);
                if (u.containsKey("id")) h.set(H_USER_ID, String.valueOf(u.get("id")));
                if (u.containsKey("role")) h.set(H_USER_ROLE, String.valueOf(u.get("role")));
                h.set(H_USER_INFO, URLEncoder.encode(userJson, StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.warn("网关解析用户信息失败: {}", e.getMessage());
            }
        };
        return base.mutate().headers(setter).build();
    }

    @Override
    public int getOrder() {
        return -100; // 早于路由转发
    }
}