package com.example.portability.operation;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
@Configuration @EnableScheduling @ConditionalOnProperty(name="jobs.enabled",havingValue="true",matchIfMissing=true) public class JobConfiguration {
}
