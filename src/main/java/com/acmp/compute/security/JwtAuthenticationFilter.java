package com.acmp.compute.security;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器：从 Authorization: Bearer <token> 解析用户并设置 SecurityContext。
 * 关键逻辑：校验 token → 解析 claims → 构造 UserPrincipal → 放入 SecurityContext。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String token = getJwtFromRequest(request);
            if (StringUtils.hasText(token)) {
                var claims = jwtTokenProvider.parseToken(token);
                String userId = claims.getSubject();
                String username = claims.get("username", String.class);
                String role = claims.get("role", String.class);
                @SuppressWarnings("unchecked")
                List<String> resourcePoolIds = (List<String>) claims.get("resourcePoolIds");
                if (resourcePoolIds == null) {
                    resourcePoolIds = List.of();
                }
                UserPrincipal principal = UserPrincipal.builder()
                        .id(userId)
                        .username(username)
                        .role(role)
                        .resourcePoolIds(resourcePoolIds)
                        .build();
                var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        } catch (Exception e) {
            log.debug("JWT 解析失败: {}", e.getMessage());
        }
        filterChain.doFilter(request, response);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
