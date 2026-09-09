package com.devmate.mapper;

import com.devmate.database.MySqlIntegrationTestBase;
import com.devmate.entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserMapperIntegrationTest extends MySqlIntegrationTestBase {
    @Autowired UserMapper mapper;

    @BeforeEach void clean() { mapper.delete(null); }

    @Test
    void savesAndFindsUserAndEnforcesUniqueUsername() {
        UserEntity user = user("mapper-user");
        assertThat(mapper.insert(user)).isOne();
        UserEntity found = mapper.findByUsername("mapper-user");
        assertThat(found.getId()).isEqualTo(user.getId());
        assertThat(found.getRole()).isEqualTo("USER");
        assertThat(found.getCreateTime()).isNotNull();
        assertThatThrownBy(() -> mapper.insert(user("mapper-user"))).isInstanceOf(DuplicateKeyException.class);
    }

    private UserEntity user(String username) {
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPassword("$2a$10$test.hash.value.long.enough.for.persistence.only.123456789");
        return user;
    }
}
