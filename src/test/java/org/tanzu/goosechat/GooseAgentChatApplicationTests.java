package org.tanzu.goosechat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "goose.enabled=false"
})
class GooseAgentChatApplicationTests {

    @Test
    void contextLoads() {
    }
}

