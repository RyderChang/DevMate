package com.devmate.knowledge.config;

import com.devmate.knowledge.application.UploadSlots;
import com.devmate.knowledge.controller.UploadAdmissionFilter;
import com.devmate.project.service.ProjectService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KnowledgeWebConfiguration {
    @Bean
    UploadAdmissionFilter uploadAdmissionFilter(ProjectService projects, KnowledgeProperties properties, UploadSlots slots, ObjectMapper mapper) {
        return new UploadAdmissionFilter(projects, properties, slots, mapper);
    }
    @Bean
    FilterRegistrationBean<UploadAdmissionFilter> uploadAdmissionRegistration(UploadAdmissionFilter filter) {
        var registration = new FilterRegistrationBean<>(filter); registration.setOrder(-98); return registration;
    }
}
