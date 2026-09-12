package com.devmate.security;

import com.devmate.service.UserRoleService;
import io.jsonwebtoken.JwtException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void treatsInvalidJwtAsUnauthenticatedRequest() throws Exception {
        JwtService jwtService = mock(JwtService.class);
        UserRoleService userRoleService = mock(UserRoleService.class);
        HandlerExceptionResolver exceptionResolver = mock(HandlerExceptionResolver.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtService, userRoleService, exceptionResolver);
        MockHttpServletRequest request = bearerRequest("invalid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        when(jwtService.parse("invalid")).thenThrow(new JwtException("invalid token"));

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userRoleService, never()).findPermissionCodes(any());
        verify(exceptionResolver, never()).resolveException(any(), any(), any(), any());
    }

    @Test
    void delegatesPermissionStoreFailuresToServerErrorHandling() throws Exception {
        JwtService jwtService = mock(JwtService.class);
        UserRoleService userRoleService = mock(UserRoleService.class);
        HandlerExceptionResolver exceptionResolver = mock(HandlerExceptionResolver.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtService, userRoleService, exceptionResolver);
        MockHttpServletRequest request = bearerRequest("valid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        CurrentUser principal = new CurrentUser(7L, "alice", List.of("USER"));
        RuntimeException databaseFailure = new RuntimeException("permission store unavailable");
        when(jwtService.parse("valid")).thenReturn(principal);
        when(userRoleService.findPermissionCodes(7L)).thenThrow(databaseFailure);
        when(exceptionResolver.resolveException(request, response, null, databaseFailure))
                .thenAnswer(invocation -> {
                    response.setStatus(500);
                    return new ModelAndView();
                });

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(exceptionResolver).resolveException(request, response, null, databaseFailure);
    }

    @Test
    void authenticatesWithRolesAndDatabasePermissions() throws Exception {
        JwtService jwtService = mock(JwtService.class);
        UserRoleService userRoleService = mock(UserRoleService.class);
        HandlerExceptionResolver exceptionResolver = mock(HandlerExceptionResolver.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtService, userRoleService, exceptionResolver);
        MockHttpServletRequest request = bearerRequest("valid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        CurrentUser principal = new CurrentUser(7L, "alice", List.of("USER"));
        when(jwtService.parse("valid")).thenReturn(principal);
        when(userRoleService.findPermissionCodes(7L)).thenReturn(List.of("user"));

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_USER", "user");
        verify(exceptionResolver, never()).resolveException(any(), any(), isNull(), any());
    }

    private MockHttpServletRequest bearerRequest(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
