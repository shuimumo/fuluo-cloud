package com.shuimumu.fuluogame.config;

import com.shuimumu.fuluogame.interceptor.HeaderAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 用户服务鉴权：身份由网关注入的头恢复（{@link HeaderAuthenticationFilter}），
 * 本服务只声明<strong>本域</strong>的放行矩阵。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, resp, e) -> resp.setStatus(401))
                .accessDeniedHandler((req, resp, e) -> resp.setStatus(403))
            )
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers(
                    "/user/login", "/user/login/password", "/user/sendCode",
                    "/internal/**",              // 服务间内部接口（网关不透出，仅内网可达）
                    "/doc.html", "/swagger-ui/**", "/v3/api-docs/**",
                    "/error", "/webjars/**", "/favicon.ico"
                ).permitAll();
                auth.requestMatchers(r -> "GET".equals(r.getMethod())
                        && r.getRequestURI().matches("^/user/\\d+(/.*)?")).permitAll();
                auth.anyRequest().authenticated();
            })
            .addFilterBefore(new HeaderAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
