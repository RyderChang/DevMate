package com.devmate.auth;

import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.database.MySqlIntegrationTestBase;
import com.devmate.mapper.UserMapper;
import com.devmate.service.UserRoleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegistrationRollbackIntegrationTest extends MySqlIntegrationTestBase {
    @Autowired MockMvc mockMvc;
    @Autowired UserMapper userMapper;
    @MockBean UserRoleService userRoleService;

    @Test
    void rollsBackCreatedUserWhenDefaultRoleAssignmentFails() throws Exception {
        doThrow(new BusinessException(ErrorCode.DEFAULT_ROLE_NOT_CONFIGURED))
                .when(userRoleService).assignDefaultRole(anyLong());
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"rollback-user\",\"password\":\"password123\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500));
        assertThat(userMapper.findByUsername("rollback-user")).isNull();
    }
}
