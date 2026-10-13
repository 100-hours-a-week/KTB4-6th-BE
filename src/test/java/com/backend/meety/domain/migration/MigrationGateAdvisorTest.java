package com.backend.meety.domain.migration;

import static org.junit.jupiter.api.Assertions.*;

import com.backend.meety.global.migration.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MigrationGateAdvisorTest {
    private AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("migration.write-gate.enabled", "true", "spring.jpa.hibernate.ddl-auto", "validate")));
        context.register(Config.class);
        context.refresh();
        return context;
    }

    @Test
    void waitingStartsNoTransactionAndTimeoutCannotWrite() throws Exception {
        try (var context = context(); var executor = Executors.newSingleThreadExecutor()) {
            var service = context.getBean(Writer.class);
            var tx = context.getBean(TestTransactionManager.class);
            var result = executor.submit(service::write);
            var error = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertInstanceOf(MigrationGateUnavailableException.class, error.getCause());
            assertEquals(0, tx.begins.get());
            assertEquals(0, service.writes());
            context.getBean(MigrationWriteGate.class).open();
            service.write();
            assertEquals(1, tx.begins.get());
            assertEquals(1, tx.commits.get());
            assertEquals(1, service.writes());
        }
    }

    @Test
    void readOnlyTransactionsStillRunAndSchedulersSkipWhenClosed() {
        try (var context = context()) {
            var service = context.getBean(Writer.class);
            service.read();
            var scheduler = context.getBean(Worker.class);
            scheduler.run();
            assertEquals(0, scheduler.runs());
            assertEquals(0, service.writes());
            context.getBean(MigrationWriteGate.class).open();
            scheduler.run();
            assertEquals(1, scheduler.runs());
            assertEquals(1, service.writes());
            assertEquals(0, context.getBean(MigrationWriteGate.class).status().active());
        }
    }

    @Test
    void admittedControllerCanFinishAfterCloseAndPermitIncludesCommit() {
        try (var context = context()) {
            var gate = context.getBean(MigrationWriteGate.class);
            gate.open();
            context.getBean(Handler.class).handle();
            assertTrue(gate.status().drained());
            assertEquals(1, context.getBean(Writer.class).writes());
        }
    }

    @Test
    void httpTimeoutReturns503WithoutExecutingHandler() throws Exception {
        try (var context = context()) {
            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(Handler.class))
                    .setControllerAdvice(new MigrationGateExceptionHandler(),
                            new com.backend.meety.global.exception.GlobalExceptionHandler())
                    .build();
            mvc.perform(post("/test"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.error.code").value("MIGRATION_WRITE_UNAVAILABLE"));
            assertEquals(0, context.getBean(Writer.class).writes());
            assertEquals(0, context.getBean(TestTransactionManager.class).begins.get());
        }
    }

    @Test
    void writeInsideUngatedTransactionRunsWhenOpenAndFailsFastWhenClosed() {
        try (var context = context()) {
            var reader = context.getBean(Reader.class);
            var gate = context.getBean(MigrationWriteGate.class);
            long started = System.nanoTime();
            assertThrows(MigrationGateUnavailableException.class, reader::readThenWrite);

            assertTrue(System.nanoTime() - started < Duration.ofMillis(100).toNanos());
            assertEquals(0, context.getBean(Writer.class).writes());
            gate.open();
            reader.readThenWrite();
            assertEquals(1, context.getBean(Writer.class).writes());
            assertEquals(0, gate.status().active());
        }
    }

    @Test
    void nonPublicTransactionalMethodIsGated() {
        try (var context = context()) {
            var writer = context.getBean(Writer.class);
            assertThrows(MigrationGateUnavailableException.class, writer::protectedWrite);
            assertEquals(0, writer.writes());
            context.getBean(MigrationWriteGate.class).open();
            writer.protectedWrite();
            assertEquals(1, writer.writes());
        }
    }

    @Test
    void disabledConfigurationAddsNoGateAdvisor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    Map.of("migration.write-gate.enabled", "false")));
            context.register(Config.class);
            context.refresh();
            assertFalse(context.containsBean("migrationGateAdvisor"));
        }
    }

    @Test
    void enabledGateRejectsSchemaMutationAtStartup() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    Map.of("migration.write-gate.enabled", "true", "spring.jpa.hibernate.ddl-auto", "create")));
            context.register(Config.class);
            assertThrows(IllegalStateException.class, context::refresh);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import(MigrationGateConfiguration.class)
    static class Config {
        @Bean MigrationGateProperties properties() {
            return new MigrationGateProperties(true, true, Duration.ofMillis(100), 2, "a".repeat(32));
        }
        @Bean MigrationWriteGate gate(MigrationGateProperties properties) {
            return new MigrationWriteGate(properties);
        }
        @Bean TestTransactionManager transactionManager(MigrationWriteGate gate) {
            return new TestTransactionManager(gate);
        }
        @Bean Writer writer() { return new Writer(); }
        @Bean Reader reader(Writer writer) { return new Reader(writer); }
        @Bean Worker worker(Writer writer) { return new Worker(writer); }
        @Bean Handler handler(Writer writer, MigrationWriteGate gate) { return new Handler(writer, gate); }
    }

    static class Writer {
        private final AtomicInteger writes = new AtomicInteger();
        @Transactional public void write() {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            writes.incrementAndGet();
        }
        @Transactional(readOnly = true) public void read() { }
        @Transactional protected void protectedWrite() {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            writes.incrementAndGet();
        }
        public int writes() { return writes.get(); }
    }

    static class Reader {
        private final Writer writer;
        Reader(Writer writer) { this.writer = writer; }

        @Transactional(readOnly = true) public void readThenWrite() { writer.write(); }
    }

    static class Worker {
        private final Writer writer;
        private int runs;
        Worker(Writer writer) { this.writer = writer; }
        @Scheduled(fixedDelay = 1000) public void run() {
            runs++;
            writer.write();
        }
        public int runs() { return runs; }
    }

    @RestController
    static class Handler {
        private final Writer writer;
        private final MigrationWriteGate gate;
        Handler(Writer writer, MigrationWriteGate gate) { this.writer = writer; this.gate = gate; }
        @PostMapping("/test") public void handle() {

            assertFalse(gate.close().drained());
            writer.write();
            assertFalse(gate.status().drained());
        }
    }

    static class TestTransactionManager extends AbstractPlatformTransactionManager {
        final AtomicInteger begins = new AtomicInteger();
        final AtomicInteger commits = new AtomicInteger();
        private final MigrationWriteGate gate;
        TestTransactionManager(MigrationWriteGate gate) { this.gate = gate; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            begins.incrementAndGet();
        }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (!status.isReadOnly()) {
                assertTrue(gate.ownsPermit(), "커밋 전에 쓰기 허가가 반환되면 안 된다");
                assertEquals(1, gate.status().active());
            }
            commits.incrementAndGet();
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
