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
 * 网关鉴权全局过滤器。
 *
 * <p>职责（对应单体里的 {@code TokenAuthenticationFilter}，此处上移到网关）：
 * <ol>
 *   <li><strong>剥离</strong>客户端伪造的 X-User-* 头，杜绝越权。</li>
 *   <li>取 {@code Authorization: Bearer <token>}，先查本地 Caffeine（30s TTL，削 Redis 峰值，
 *       提升可用性），未命中再查 Redis {@code login:token:<token>}。</li>
 *   <li>命中则把用户 JSON 注入下游头 {@code X-User-Id/X-User-Role/X-User-Info}。</li>
 * </ol>
 * 不做拦截：细粒度放行/鉴权仍交由各下游服务自己的 SecurityConfig（保持与单体一致的权限矩阵）。
 */
@Slf4j
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    /** 与单体 SystemRedisKeys.loginToken 保持一致 */
    private static final String LOGIN_TOKEN_PREFIX = "login:token:";

    private final ReactiveStringRedisTemplate redis;
    /** 网关本地缓存 token->userJson，短 TTL 抗 Redis 抖动 */
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
                    h.remove("X-User-Id");
                    h.remove("X-User-Role");
                    h.remove("X-User-Info");
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

    private ServerHttpRequest inject(ServerHttpRequest base, String userJson) {
        Consumer<org.springframework.http.HttpHeaders> setter = h -> {
            try {
                JSONObject u = JSON.parseObject(userJson);
                if (u.containsKey("id")) h.set("X-User-Id", String.valueOf(u.get("id")));
                if (u.containsKey("role")) h.set("X-User-Role", String.valueOf(u.get("role")));
                h.set("X-User-Info", URLEncoder.encode(userJson, StandardCharsets.UTF_8));
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
