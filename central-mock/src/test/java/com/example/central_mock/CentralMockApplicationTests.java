package com.example.central_mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = "spring.datasource.url=jdbc:h2:mem:central-mock-test;DB_CLOSE_DELAY=-1"
)
class CentralMockApplicationTests {

    @Test
    void contextLoads() {}
}
