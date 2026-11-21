package com.edu.agent.config;

import com.edu.agent.service.impl.AgentTaskServiceImpl;
import com.edu.agent.service.impl.ExportServiceImpl;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Field-level @Qualifier is invisible to constructor injection unless lombok.config copies it;
 * Spring then silently falls back to the parameter name and Agent tasks lose their bounded queue.
 */
class ExecutorWiringTest {

    private static String executorQualifier(Class<?> type) {
        Parameter executor = Arrays.stream(type.getDeclaredConstructors()[0].getParameters())
                .filter(p -> p.getType() == Executor.class).findFirst().orElseThrow();
        Qualifier qualifier = executor.getAnnotation(Qualifier.class);
        return qualifier == null ? null : qualifier.value();
    }

    @Test
    void generatedConstructorsCarryTheExecutorQualifiers() {
        assertThat(executorQualifier(AgentTaskServiceImpl.class)).isEqualTo("agentTaskExecutor");
        assertThat(executorQualifier(ExportServiceImpl.class)).isEqualTo("agentExecutor");
    }

    @RequiredArgsConstructor
    static class SameWiringAsAgentTaskService {
        @Qualifier("agentTaskExecutor")
        private final Executor agentExecutor;
    }

    @Test
    void containerInjectsTheBoundedAgentTaskExecutorDespiteTheFieldName() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(AsyncConfig.class, SameWiringAsAgentTaskService.class);
            context.refresh();
            Object injected = ReflectionTestUtils.getField(context.getBean(SameWiringAsAgentTaskService.class), "agentExecutor");
            assertThat(injected).isSameAs(context.getBean("agentTaskExecutor"));
        }
    }
}
