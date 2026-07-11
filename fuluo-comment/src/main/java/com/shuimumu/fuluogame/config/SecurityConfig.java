package com.shuimumu.fuluogame.config;

import com.shuimumu.fuluogame.interceptor.HeaderAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * 评论服务鉴权：身份由网关注入头恢复（{@link HeaderAuthenticationFilter}），
 * 放行评论列表/回复列表等公开读接口。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, resp, e) -> resp.setStatus(401))
                .accessDeniedHandler((req, resp, e) -> resp.setStatus(403))
            )
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers(
                    "/doc.html", "/swagger-ui/**", "/v3/api-docs/**",
                    "/error", "/webjars/**", "/favicon.ico"
                ).permitAll();
                // 评论列表、回复列表允许公开读
                auth.requestMatchers(r -> "GET".equals(r.getMethod())
                        && r.getRequestURI().matches("^/post/\\d+/comments$")).permitAll();
                auth.requestMatchers(r -> "GET".equals(r.getMethod())
                        && r.getRequestURI().matches("^/post/comment/\\d+/replies$")).permitAll();
                auth.requestMatchers(r -> "GET".equals(r.getMethod())
                        && r.getRequestURI().matches("^/comment/health$")).permitAll();
                auth.anyRequest().authenticated();
            })
            .addFilterBefore(new HeaderAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
