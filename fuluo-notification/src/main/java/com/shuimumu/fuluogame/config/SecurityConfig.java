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

@Configuration
@EnableWebSecurity
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
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/notifications/health",
                    "/doc.html", "/swagger-ui/**", "/v3/api-docs/**",
                    "/error", "/webjars/**", "/favicon.ico"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(new HeaderAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
