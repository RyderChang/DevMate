package com.devmate.security;

import com.devmate.service.UserRoleService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserRoleService userRoleService;
    private final HandlerExceptionResolver exceptionResolver;

    public JwtAuthenticationFilter(JwtService jwtService, UserRoleService userRoleService,
                                   @Qualifier("handlerExceptionResolver")
                                   HandlerExceptionResolver exceptionResolver) {
        this.jwtService = jwtService;
        this.userRoleService = userRoleService;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            CurrentUser principal;
            try {
                principal = jwtService.parse(authorization.substring(7));
            } catch (JwtException | IllegalArgumentException exception) {
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }

            List<String> permissions;
            try {
                permissions = userRoleService.findPermissionCodes(principal.id());
            } catch (RuntimeException exception) {
                SecurityContextHolder.clearContext();
                if (exceptionResolver.resolveException(request, response, null, exception) == null) {
                    throw exception;
                }
                return;
            }

            List<SimpleGrantedAuthority> authorities = Stream.concat(
                            principal.roles().stream().map(role -> "ROLE_" + role), permissions.stream())
                    .distinct().sorted().map(SimpleGrantedAuthority::new).toList();
            var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }
}
