package com.devmate;

import com.devmate.database.MySqlIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DevMateApplicationTests extends MySqlIntegrationTestBase {

    @Test
    void contextLoadsWithManagedTestDatabase() {
    }
}
