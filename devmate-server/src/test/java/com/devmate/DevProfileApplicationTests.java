package com.devmate;

import com.devmate.database.MySqlIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("dev")
class DevProfileApplicationTests extends MySqlIntegrationTestBase {

    @Test
    void devProfileUsesManagedTestDatabase() {
    }
}
