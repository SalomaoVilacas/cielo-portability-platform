package com.example.portability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:backend-test;DB_CLOSE_DELAY=-1")
class BackendApplicationTests {

    @Test
    void contextLoads() {}
}
