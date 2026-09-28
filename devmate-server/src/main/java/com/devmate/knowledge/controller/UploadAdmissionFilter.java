package com.devmate.knowledge.controller;

import com.devmate.common.api.*;
import com.devmate.common.exception.BusinessException;
import com.devmate.knowledge.application.UploadSlots;
import com.devmate.knowledge.config.KnowledgeProperties;
import com.devmate.project.service.ProjectService;
import com.devmate.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs after authentication and trace creation, before multipart parsing or buffering. */
public class UploadAdmissionFilter extends OncePerRequestFilter {
    private static final Pattern PATH = Pattern.compile("^/projects/([^/]+)/documents$");
    private final ProjectService projects;
    private final KnowledgeProperties properties;
    private final UploadSlots slots;
    private final ObjectMapper json;
    public UploadAdmissionFilter(ProjectService projects, KnowledgeProperties properties, UploadSlots slots, ObjectMapper json) {
        this.projects = projects; this.properties = properties; this.slots = slots; this.json = json;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var path = PATH.matcher(request.getRequestURI().substring(request.getContextPath().length()));
        boolean admittedPath = request.getMethod().equals("POST") && path.matches();
        String contentType = request.getContentType() == null ? "" : request.getContentType().toLowerCase(java.util.Locale.ROOT).strip();
        if (!admittedPath) {
            if (contentType.startsWith("multipart/")) {
                response.setStatus(415); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                json.writeValue(response.getOutputStream(), Result.error(ErrorCode.MULTIPART_UNSUPPORTED)); return;
            }
            chain.doFilter(request, response); return;
        }
        UploadSlots.Slot slot = null;
        try {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user)) throw new BusinessException(ErrorCode.UNAUTHORIZED);
            if (authentication.getAuthorities().stream().noneMatch(value -> value.getAuthority().equals("user"))) throw new BusinessException(ErrorCode.FORBIDDEN);
            long projectId;
            try { projectId = Long.parseLong(path.group(1)); }
            catch (NumberFormatException error) { throw new BusinessException(ErrorCode.INVALID_PARAMETER); }
            projects.requireOwnedActiveProject(user.id(), projectId);
            if (!properties.isEnabled()) throw new BusinessException(ErrorCode.KNOWLEDGE_SERVICE_DISABLED);
            if (!contentType.split(";", 2)[0].strip().equals(MediaType.MULTIPART_FORM_DATA_VALUE)) throw new BusinessException(ErrorCode.MULTIPART_UNSUPPORTED);
            if (request.getContentLengthLong() > properties.getMaxRequestBytes()) throw new BusinessException(ErrorCode.DOCUMENT_TOO_LARGE);
            slot = slots.acquire();
        } catch (BusinessException error) {
            response.setStatus(error.getHttpStatus().value()); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), Result.error(error.getCode(), error.getClientMessage())); return;
        } catch (DataAccessException | TransactionException error) {
            response.setStatus(503); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), Result.error(ErrorCode.KNOWLEDGE_DATABASE_UNAVAILABLE)); return;
        }
        try { chain.doFilter(request, response); } finally { slot.close(); }
    }
}
