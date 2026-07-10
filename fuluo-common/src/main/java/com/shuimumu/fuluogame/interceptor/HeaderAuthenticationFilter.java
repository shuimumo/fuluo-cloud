package com.shuimumu.fuluogame.interceptor;

import cn.hutool.json.JSONUtil;
import com.shuimumu.fuluogame.vo.UserVO;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.List;

/**
 * 下游服务的鉴权过滤器。
 *
 * <p>与单体版 {@code TokenAuthenticationFilter} 不同：这里不再直接查 Redis 校验 token，
 * 而是<strong>信任网关</strong>注入的请求头：
 * <ul>
 *   <li>{@code X-User-Info}：URL 编码后的 UserVO JSON（由网关在校验 token 通过后写入）</li>
 *   <li>{@code X-User-Role}：角色，用于 {@code ROLE_ADMIN} 授权</li>
 * </ul>
 * 网关会剥离客户端伪造的这些头，因此内网流量可信。各服务在自己的 SecurityConfig 中
 * {@code addFilterBefore(new HeaderAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)}。
 */
@Slf4j
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    public static final String H_USER_ID = "X-User-Id";
    public static final String H_USER_ROLE = "X-User-Role";
    public static final String H_USER_INFO = "X-User-Info";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String userInfo = request.getHeader(H_USER_INFO);
        if (userInfo != null && !userInfo.isEmpty()) {
            try {
                String json = URLDecoder.decode(userInfo, StandardCharsets.UTF_8);
                UserVO user = JSONUtil.toBean(json, UserVO.class);
                List<SimpleGrantedAuthority> authorities = "admin".equals(user.getRole())
                        ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                        : Collections.emptyList();
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(user, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                log.warn("解析网关注入的用户信息失败: {}", e.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
}
