package com.example.flashsale;

import java.util.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static com.example.flashsale.Model.*;

@org.springframework.test.annotation.DirtiesContext
@Testcontainers
@SpringBootTest(properties = {"app.messaging=false"})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class InventoryTest {
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine").withStartupTimeout(java.time.Duration.ofMinutes(3));
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379).withStartupTimeout(java.time.Duration.ofMinutes(3));
    @Autowired
    Reservations reservations;
    @Autowired
    Catalog catalog;
    @Autowired
    JdbcTemplate db;
    @Autowired
    Admission admission;
    @Autowired
    Recovery recovery;
    @Autowired
    MockMvc mvc;
    @Autowired
    Checkout checkout;
    UUID sale, pool;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @BeforeEach
    void setup() {
        UUID product = UUID.randomUUID(), sku = UUID.randomUUID(), warehouse = UUID.randomUUID();
        db.update("INSERT INTO products VALUES(?,'Phone')", product);
        db.update("INSERT INTO skus VALUES(?,?,'Phone')", sku, product);
        db.update("INSERT INTO warehouses VALUES(?,'AZ1')", warehouse);
        db.update("INSERT INTO inventory(sku_id,warehouse_id,physical,sellable) VALUES(?,?,1000,1000)", sku, warehouse);
        sale = catalog.sale(new Sale("Sale", java.time.Instant.now().minusSeconds(30), java.time.Instant.now().plusSeconds(3600), 10, 10, 300));
        pool = catalog.allocate(sale, new Allocation(sku, warehouse, 100));
        catalog.state(sale, "ACTIVE");
    }

    List<Reservation> burst(int n, boolean sameKey) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore bounds = new Semaphore(8);
            List<Future<Reservation>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    bounds.acquire();
                    try {
                        return reservations.attempt(new Try(sale, pool, 1), sameKey ? "duplicate" : "customer-" + index, sameKey ? "same-key-000" : "key-000-" + index, false);
                    } finally {
                        bounds.release();
                    }
                }));
            }
            List<Reservation> result = new ArrayList<>();
            for (var f : futures) result.add(f.get(60, TimeUnit.SECONDS));
            return result;
        }
    }

    void accounting() {
        var p = db.queryForMap("SELECT * FROM inventory_allocations WHERE id=?", pool);
        var totals = db.queryForMap("SELECT COALESCE(sum(quantity) FILTER(WHERE status='RESERVED'),0) AS held,COALESCE(sum(quantity) FILTER(WHERE status='CONFIRMED'),0) AS committed FROM inventory_reservations WHERE allocation_id=?", pool);
        assertEquals(((Number) p.get("reserved")).longValue(), ((Number) totals.get("held")).longValue());
        assertEquals(((Number) p.get("committed")).longValue(), ((Number) totals.get("committed")).longValue());
        assertTrue(((Number) p.get("reserved")).longValue() + ((Number) p.get("committed")).longValue() <= ((Number) p.get("capacity")).longValue());
    }

    @Test
    void thousandForHundred() throws Exception {
        assertEquals(100, burst(1000, false).stream().filter(r -> r.status() == Status.RESERVED).count());
        accounting();
    }

    @Test
    void hundredForLastUnit() throws Exception {
        db.update("UPDATE inventory SET allocated=allocated-99 WHERE (sku_id,warehouse_id)=(SELECT sku_id,warehouse_id FROM inventory_allocations WHERE id=?)", pool);
        db.update("UPDATE inventory_allocations SET capacity=1 WHERE id=?", pool);
        assertEquals(1, burst(100, false).stream().filter(r -> r.status() == Status.RESERVED).count());
        accounting();
    }

    @Test
    void duplicateAcrossIndependentServiceInstances() throws Exception {
        var other = new Reservations(db, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()); // Real independent transaction proxy using same durable DB.
        var factory = new org.springframework.transaction.interceptor.TransactionProxyFactoryBean();
        factory.setTransactionManager(new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource()));
        factory.setTarget(other);
        java.util.Properties attributes = new java.util.Properties();
        attributes.setProperty("*", "PROPAGATION_REQUIRED");
        factory.setTransactionAttributes(attributes);
        factory.setProxyTargetClass(true);
        factory.afterPropertiesSet();
        Reservations pod = (Reservations) factory.getObject();
        try (var e = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = e.submit(() -> reservations.attempt(new Try(sale, pool, 1), "same", "same-key-123", false));
            var b = e.submit(() -> pod.attempt(new Try(sale, pool, 1), "same", "same-key-123", false));
            assertEquals(a.get().id(), b.get().id());
        }
        accounting();
    }

    @Test
    void concurrentDuplicates() throws Exception {
        assertEquals(1, burst(100, true).stream().map(Reservation::id).distinct().count());
        accounting();
    }

    @Test
    void conflictingFingerprint() {
        reservations.attempt(new Try(sale, pool, 1), "c", "key-00000", false);
        assertThrows(Problem.Rejected.class, () -> reservations.attempt(new Try(sale, pool, 2), "c", "key-00000", false));
        accounting();
    }

    @Test
    void confirmCancelRace() throws Exception {
        var r = reservations.attempt(new Try(sale, pool, 1), "c", "key-00000", false);
        try (var e = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = e.submit(() -> reservations.transition(r.id(), "CONFIRMED", "c", false));
            var b = e.submit(() -> reservations.transition(r.id(), "CANCELLED", "c", false));
            assertEquals(a.get().status(), b.get().status());
        }
        accounting();
    }

    @Test
    void staleExpiryCannotReleaseNewHold() {
        var a = reservations.attempt(new Try(sale, pool, 1), "a", "key-00000", false);
        db.update("UPDATE inventory_reservations SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?", a.id());
        assertEquals(Status.EXPIRED, reservations.transition(a.id(), "EXPIRED", "worker", true).status());
        var b = reservations.attempt(new Try(sale, pool, 1), "b", "key-00000", false);
        reservations.transition(a.id(), "EXPIRED", "worker", true);
        assertEquals(Status.RESERVED, reservations.get(b.id()).status());
        accounting();
    }

    @Test
    void emptyRollbackFencesLateTry() {
        var command = new Try(sale, pool, 1);
        assertEquals(Status.CANCELLED, reservations.attempt(command, "c", "key-00000", true).status());
        assertEquals(Status.CANCELLED, reservations.attempt(command, "c", "key-00000", false).status());
        accounting();
    }

    @Test
    void perCustomerLimit() {
        for (int i = 0; i < 20; i++) reservations.attempt(new Try(sale, pool, 1), "c", "key-000-" + i, false);
        assertEquals(10, db.queryForObject("SELECT sum(quantity) FROM inventory_reservations WHERE sale_id=? AND status='RESERVED'", Integer.class, sale));
        accounting();
    }

    @Test
    void pauseRejectsAndExistingHoldsCanConfirm() {
        var r = reservations.attempt(new Try(sale, pool, 1), "c", "key-00000", false);
        catalog.state(sale, "PAUSED");
        assertEquals(Status.FAILED, reservations.attempt(new Try(sale, pool, 1), "b", "key-00000", false).status());
        assertEquals(Status.CONFIRMED, reservations.transition(r.id(), "CONFIRMED", "c", false).status());
        accounting();
    }

    @Test
    void adjustmentCannotStealAllocatedStock() {
        var p = db.queryForMap("SELECT sku_id,warehouse_id FROM inventory_allocations WHERE id=?", pool);
        assertThrows(Problem.Rejected.class, () -> catalog.adjust((UUID) p.get("sku_id"), new Adjustment((UUID) p.get("warehouse_id"), -901, "damage")));
        accounting();
    }

    @Test
    void rollbackLeavesNoPartialIdempotency() {
        assertThrows(Problem.Rejected.class, () -> reservations.attempt(new Try(sale, UUID.randomUUID(), 1), "c", "key-00000", false));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM idempotency_records WHERE sale_id=?", Integer.class, sale));
    }

    @Test
    void redisAdmissionIsBounded() throws Exception {
        int accepted = 0;
        for (int i = 0; i < 10; i++) {
            try {
                admission.acquire(sale, "c");
                accepted++;
                admission.release();
            } catch (Problem.Rejected e) {
                assertEquals(429, e.status);
            }
        }
        assertTrue(accepted >= 3 && accepted <= 6);
    }

    @Test
    void reconciliationAndSimulatedCheckout() {
        var r = reservations.attempt(new Try(sale, pool, 1), "c", "key-00000", false);
        assertEquals("ORDER_CONFIRMED", checkout.complete(r.id(), "c").get("checkout"));
        assertEquals("ORDER_CONFIRMED", checkout.complete(r.id(), "c").get("checkout"));
        assertTrue(recovery.reconcile() > 0);
        accounting();
    }

    @Test
    void inboxDeduplicatesAndRejectsStaleProjection() throws Exception {
        var messaging = new Messaging(db, new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource())), null, new com.fasterxml.jackson.databind.ObjectMapper());
        UUID aggregate = UUID.randomUUID(), event = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + event + "\",\"aggregateId\":\"" + aggregate + "\",\"aggregateVersion\":2,\"schemaVersion\":1}";
        messaging.consume(payload);
        messaging.consume(payload);
        messaging.consume(payload.replace(event.toString(), UUID.randomUUID().toString()).replace("Version\":2", "Version\":1"));
        assertEquals(2, db.queryForObject("SELECT version FROM event_projection WHERE aggregate_id=?", Long.class, aggregate));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM processed_events WHERE event_id=?", Integer.class, event));
    }

    @Test
    void databasePoolExhaustionFailsBoundedly() throws Exception {
        List<java.sql.Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) held.add(db.getDataSource().getConnection());
            assertThrows(org.springframework.transaction.CannotCreateTransactionException.class, () -> reservations.attempt(new Try(sale, pool, 1), "c", "key-00000", false));
        } finally {
            for (var c : held) c.close();
        }
        accounting();
    }

    @Test
    void concurrentCustomerLimitAndStockAdjustment() throws Exception {
        var p = db.queryForMap("SELECT sku_id,warehouse_id FROM inventory_allocations WHERE id=?", pool);
        try (var e = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Reservation>> futures = new ArrayList<>();
            Semaphore bound = new Semaphore(8);
            for (int i = 0; i < 40; i++) {
                int n = i;
                futures.add(e.submit(() -> {
                    bound.acquire();
                    try {
                        return reservations.attempt(new Try(sale, pool, 1), "same-customer", "key-race-" + n, false);
                    } finally {
                        bound.release();
                    }
                }));
            }
            var replenish = e.submit(() -> catalog.adjust((UUID) p.get("sku_id"), new Adjustment((UUID) p.get("warehouse_id"), 100, "restock")));
            assertEquals(10, futures.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }).filter(x -> x.status() == Status.RESERVED).count());
            replenish.get();
        }
        accounting();
    }

    @Test
    void dueHoldCannotConfirmAndCancelIsIdempotent() {
        var r = reservations.attempt(new Try(sale, pool, 1), "c", "key-00000", false);
        db.update("UPDATE inventory_reservations SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?", r.id());
        assertEquals(Status.EXPIRED, reservations.transition(r.id(), "CONFIRMED", "c", false).status());
        assertEquals(Status.EXPIRED, reservations.transition(r.id(), "CANCELLED", "c", false).status());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=?", Integer.class, r.id()));
        accounting();
    }

    @Test
    void redisTimeoutDoesNotCreateOwnership() {
        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
        try {
            var error = assertThrows(Problem.Rejected.class, () -> admission.acquire(sale, "c"));
            assertEquals(503, error.status);
        } finally {
            redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
        }
        assertEquals(0, db.queryForObject("SELECT count(*) FROM inventory_reservations WHERE sale_id=?", Integer.class, sale));
        accounting();
    }

    @Test
    void luaConcurrentQuotaAndCleanup() throws Exception {
        String key = "admission:{" + sale + "}:test";
        try (var e = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                int n = i;
                futures.add(e.submit(() -> admission.redis.execute(admission.script, List.of(key, key + ":" + n), "10", "1")));
            }
            int accepted = 0;
            for (var f : futures) if (f.get() == 0) accepted++;
            assertEquals(10, accepted);
        }
        assertEquals("10", admission.redis.opsForValue().get(key));
        assertTrue(admission.redis.getExpire(key, TimeUnit.MILLISECONDS) > 0);
    }

    @Test
    void adjustmentIdempotencyAuditsActor() {
        var poolRow = db.queryForMap("SELECT sku_id,warehouse_id FROM inventory_allocations WHERE id=?", pool);
        UUID sku = (UUID) poolRow.get("sku_id"), warehouse = (UUID) poolRow.get("warehouse_id");
        var change = new Adjustment(warehouse, 5, "supplier receipt");
        catalog.adjustIdempotent(sku, change, "administrator", "adjust-key-000");
        catalog.adjustIdempotent(sku, change, "administrator", "adjust-key-000");
        assertEquals(1005, db.queryForObject("SELECT physical FROM inventory WHERE sku_id=? AND warehouse_id=?", Long.class, sku, warehouse));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM inventory_movements WHERE sku_id=? AND actor='administrator'", Integer.class, sku));
        assertThrows(Problem.Rejected.class, () -> catalog.adjustIdempotent(sku, new Adjustment(warehouse, 6, "supplier receipt"), "administrator", "adjust-key-000"));
    }

    @Test
    void sagaRecoversAfterAuthorizationBeforeInventory() {
        var r = reservations.attempt(new Try(sale, pool, 1), "c", "key-00000", false);
        db.update("INSERT INTO checkout_sagas VALUES(?,'AUTHORIZED',?,clock_timestamp())", r.id(), "simulated-" + r.id());
        db.update("INSERT INTO simulated_payments(reservation_id,state) VALUES(?,'AUTHORIZED')", r.id());
        checkout.recover();
        assertEquals(Status.CONFIRMED, reservations.get(r.id()).status());
        assertEquals("CAPTURED", db.queryForObject("SELECT state FROM simulated_payments WHERE reservation_id=?", String.class, r.id()));
        accounting();
    }

    @Test
    void sagaVoidsAuthorizationAfterExpiry() {
        var r = reservations.attempt(new Try(sale, pool, 1), "c", "key-00000", false);
        db.update("UPDATE inventory_reservations SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?", r.id());
        assertEquals("AUTHORIZATION_VOIDED", checkout.complete(r.id(), "c").get("checkout"));
        assertEquals("VOIDED", db.queryForObject("SELECT state FROM simulated_payments WHERE reservation_id=?", String.class, r.id()));
        accounting();
    }

    @Test
    void authorizationAndOwnership() throws Exception {
        mvc.perform(get("/api/v1/flash-sales")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/flash-sales").with(httpBasic("customer", "customer-local")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        var r = reservations.attempt(new Try(sale, pool, 1), "someone", "key-00000", false);
        mvc.perform(get("/api/v1/reservations/" + r.id()).with(httpBasic("customer", "customer-local"))).andExpect(status().isNotFound());
    }
}
