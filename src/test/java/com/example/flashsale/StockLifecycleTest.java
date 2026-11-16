package com.example.flashsale;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.*;

import static com.example.flashsale.Model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties = "app.messaging=false")
@ActiveProfiles("demo")
@AutoConfigureMockMvc
@DirtiesContext
@Timeout(60)
class StockLifecycleTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withStartupTimeout(Duration.ofMinutes(3));
    @Autowired
    JdbcTemplate db;
    @Autowired
    Catalog catalog;
    @Autowired
    Reservations reservations;
    @Autowired
    StockLifecycle lifecycle;
    @Autowired
    Recovery recovery;
    @Autowired
    MockMvc mvc;
    UUID sku, warehouse, sale, pool;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void stock() {
        UUID product = UUID.randomUUID();
        sku = UUID.randomUUID();
        warehouse = UUID.randomUUID();
        db.update("INSERT INTO products VALUES(?,'Lifecycle phone')", product);
        db.update("INSERT INTO skus VALUES(?,?,'Lifecycle SKU')", sku, product);
        db.update("INSERT INTO warehouses VALUES(?,'Lifecycle warehouse')", warehouse);
        db.update("INSERT INTO inventory(sku_id,warehouse_id,physical,sellable) VALUES(?,?,1000,1000)", sku, warehouse);
        sale = sale();
        pool = catalog.allocate(sale, new Allocation(sku, warehouse, 100));
        catalog.state(sale, "ACTIVE");
    }

    UUID sale() {
        return catalog.sale(new Sale("Lifecycle", Instant.now().minusSeconds(10), Instant.now().plusSeconds(3600), 10, 10, 300));
    }

    Reservation held(int quantity) {
        return reservations.attempt(new Try(sale, pool, quantity), "customer", UUID.randomUUID().toString(), false);
    }

    Reservation confirmed(int quantity) {
        return reservations.transition(held(quantity).id(), "CONFIRMED", "customer", false);
    }

    long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    Map<String, Object> warehouse() {
        return db.queryForMap("SELECT * FROM inventory WHERE sku_id=? AND warehouse_id=?", sku, warehouse);
    }

    void complete() {
        catalog.state(sale, "COMPLETED");
    }

    // Persisted invariants, including every pool sharing this warehouse (not just HTTP success counts).
    void accounting() {
        var pools = db.queryForList("SELECT * FROM inventory_allocations WHERE sku_id=? AND warehouse_id=?", sku, warehouse);
        long encumbered = 0, fulfilled = 0;
        for (var p : pools) {
            var totals = db.queryForMap("""
                    SELECT COALESCE(sum(r.quantity) FILTER(WHERE status='RESERVED'),0) AS reserved,
                           COALESCE(sum(r.quantity) FILTER(WHERE status='CONFIRMED'),0) AS committed,
                           COALESCE(sum(f.quantity),0) AS fulfilled
                    FROM inventory_reservations r LEFT JOIN inventory_fulfillments f ON f.reservation_id=r.id
                    WHERE allocation_id=?
                    """, p.get("id"));
            for (String field : List.of("reserved", "committed", "fulfilled"))
                assertEquals(number(totals, field), number(p, field), field);
            assertTrue(number(p, "fulfilled") <= number(p, "committed"));
            assertTrue(number(p, "reserved") + number(p, "committed") + number(p, "released") <= number(p, "capacity"));
            if (p.get("closed_at") != null) {
                assertEquals(0, number(p, "reserved"));
                assertEquals(number(p, "capacity") - number(p, "committed"), number(p, "released"));
            } else assertEquals(0, number(p, "released"));
            encumbered += number(p, "capacity") - number(p, "fulfilled") - number(p, "released");
            fulfilled += number(p, "fulfilled");
        }
        var stock = warehouse();
        assertEquals(encumbered, number(stock, "allocated"));
        assertEquals(fulfilled, number(stock, "fulfilled"));
        assertTrue(number(stock, "allocated") <= number(stock, "sellable"));
        assertTrue(number(stock, "sellable") <= number(stock, "physical"));
        assertTrue(number(stock, "allocated") >= 0);
    }

    @SafeVarargs
    final <T> List<T> race(Callable<T>... operations) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>();
            for (var operation : operations)
                futures.add(executor.submit(() -> {
                    start.await();
                    return operation.call();
                }));
            start.countDown();
            List<T> result = new ArrayList<>();
            for (var future : futures) result.add(future.get(30, TimeUnit.SECONDS));
            return result;
        }
    }

    // A close racing a hold transition may legitimately need a retry, never a forced release.
    int closeDecision() {
        try {
            lifecycle.close(pool, "admin");
            return 200;
        } catch (Problem.Rejected e) {
            assertEquals(409, e.status);
            return 409;
        }
    }

    @Test
    void closureRequiresTerminalSaleAndNoActiveHolds() {
        assertThrows(Problem.Rejected.class, () -> lifecycle.close(pool, "admin"));
        var hold = held(1);
        complete();
        assertThrows(Problem.Rejected.class, () -> lifecycle.close(pool, "admin"));
        db.update("UPDATE inventory_reservations SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?", hold.id());
        assertThrows(Problem.Rejected.class, () -> lifecycle.close(pool, "admin"));
        reservations.transition(hold.id(), "EXPIRED", "worker", true);
        assertEquals(100, number(lifecycle.close(pool, "admin"), "released"));
        assertEquals(0, number(warehouse(), "allocated"));
        accounting();
    }

    @Test
    void closureProtectsUnshippedCommitmentsAndAllowsSafeReallocation() {
        var order = confirmed(2);
        complete();
        var closed = lifecycle.close(pool, "admin");
        assertEquals(98, number(closed, "released"));
        assertEquals(2, number(closed, "encumbered"));
        assertEquals(0, number(closed, "available"));
        assertEquals(1000, number(warehouse(), "physical"));
        UUID nextSale = sale();
        catalog.allocate(nextSale, new Allocation(sku, warehouse, 998));
        assertThrows(Problem.Rejected.class, () -> catalog.allocate(sale(), new Allocation(sku, warehouse, 1)));
        lifecycle.fulfill(order.id(), new Shipment("shipment-reallocate"), "warehouse-operator");
        assertEquals(998, number(warehouse(), "physical"));
        assertEquals(998, number(warehouse(), "allocated"));
        accounting();
    }

    @Test
    void fulfillmentBeforeClosureAndAfterClosureUseIdenticalAccounting() {
        var a = confirmed(2);
        var b = confirmed(3);
        lifecycle.fulfill(a.id(), new Shipment("shipment-first"), "admin");
        assertEquals(95, number(lifecycle.pool(pool), "available"));
        assertEquals(998, number(warehouse(), "physical"));
        assertEquals(98, number(warehouse(), "allocated"));
        complete();
        lifecycle.close(pool, "admin");
        assertEquals(3, number(warehouse(), "allocated"));
        lifecycle.fulfill(b.id(), new Shipment("shipment-second"), "admin");
        assertEquals(995, number(warehouse(), "sellable"));
        assertEquals(0, number(warehouse(), "allocated"));
        assertEquals(5, number(warehouse(), "fulfilled"));
        assertEquals(95, number(lifecycle.pool(pool), "released"));
        assertEquals(5, number(lifecycle.pool(pool), "committed"));
        assertEquals(Status.CONFIRMED, reservations.transition(a.id(), "CANCELLED", "customer", false).status());
        assertEquals(5, db.queryForObject("SELECT quantity FROM customer_usage WHERE sale_id=? AND customer_id='customer'", Long.class, sale));
        accounting();
    }

    @Test
    void cancelledSaleCanCloseWithoutReservations() {
        catalog.state(sale, "CANCELLED");
        assertEquals(100, number(lifecycle.close(pool, "admin"), "released"));
        assertThrows(Problem.Rejected.class, () -> catalog.state(sale, "ACTIVE"));
        assertEquals(Status.FAILED, held(1).status());
        accounting();
    }

    @Test
    void shipmentIdentityIsDurableAndCannotBeReused() {
        var a = confirmed(1);
        var b = confirmed(1);
        var first = lifecycle.fulfill(a.id(), new Shipment("shipment-unique"), "admin");
        assertEquals(first, lifecycle.fulfill(a.id(), new Shipment("shipment-unique"), "retrying-operator"));
        assertThrows(Problem.Rejected.class, () -> lifecycle.fulfill(a.id(), new Shipment("shipment-different"), "admin"));
        assertThrows(DataIntegrityViolationException.class, () -> lifecycle.fulfill(b.id(), new Shipment("shipment-unique"), "admin"));
        assertEquals(999, number(warehouse(), "physical"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND type='InventoryFulfilled'", Integer.class, a.id()));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM inventory_movements WHERE reservation_id=? AND kind='FULFILL' AND actor='admin'", Integer.class, a.id()));
        assertEquals(1, reservations.get(b.id()).version());
        accounting();
    }

    @Test
    void unconfirmedAndExpiredReservationsCannotBeDispatched() {
        var a = held(1);
        assertThrows(Problem.Rejected.class, () -> lifecycle.fulfill(a.id(), new Shipment("shipment-invalid"), "admin"));
        reservations.transition(a.id(), "CANCELLED", "customer", false);
        assertThrows(Problem.Rejected.class, () -> lifecycle.fulfill(a.id(), new Shipment("shipment-invalid"), "admin"));
        var expired = held(1);
        db.update("UPDATE inventory_reservations SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?", expired.id());
        reservations.transition(expired.id(), "EXPIRED", "worker", true);
        assertThrows(Problem.Rejected.class, () -> lifecycle.fulfill(expired.id(), new Shipment("shipment-expired"), "admin"));
        accounting();
    }

    @Test
    void independentInstancesDispatchOneReservationOnce() throws Exception {
        var factory = new org.springframework.transaction.interceptor.TransactionProxyFactoryBean();
        factory.setTarget(new StockLifecycle(db, new ObjectMapper()));
        factory.setTransactionManager(new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource()));
        Properties attributes = new Properties();
        attributes.setProperty("*", "PROPAGATION_REQUIRED");
        factory.setTransactionAttributes(attributes);
        factory.setProxyTargetClass(true);
        factory.afterPropertiesSet();
        var other = (StockLifecycle) factory.getObject();
        var order = confirmed(4);
        var responses = race(() -> lifecycle.fulfill(order.id(), new Shipment("shipment-pods"), "admin"),
                () -> other.fulfill(order.id(), new Shipment("shipment-pods"), "admin"));
        assertEquals(responses.getFirst(), responses.getLast());
        assertEquals(996, number(warehouse(), "physical"));
        assertEquals(4, number(lifecycle.pool(pool), "fulfilled"));
        accounting();
    }

    @Test
    void concurrentDuplicateClosureReleasesCapacityOnce() throws Exception {
        complete();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> work = new ArrayList<>();
            var bound = new Semaphore(8);
            for (int i = 0; i < 40; i++)
                work.add(executor.submit(() -> {
                    bound.acquire();
                    try {
                        return lifecycle.close(pool, "admin");
                    } finally {
                        bound.release();
                    }
                }));
            for (var f : work) f.get(30, TimeUnit.SECONDS);
        }
        assertEquals(0, number(warehouse(), "allocated"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM inventory_movements WHERE allocation_id=? AND kind='POOL_CLOSE'", Integer.class, pool));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND type='InventoryPoolClosed'", Integer.class, pool));
        accounting();
    }

    @Test
    void dispatchRacingClosureKeepsConfirmedStockEncumberedUntilDispatch() throws Exception {
        var order = confirmed(1);
        complete();
        race(() -> {
                    lifecycle.close(pool, "admin");
                    return true;
                },
                () -> {
                    lifecycle.fulfill(order.id(), new Shipment("shipment-close-race"), "admin");
                    return true;
                });
        assertEquals(999, number(warehouse(), "physical"));
        assertEquals(0, number(warehouse(), "allocated"));
        assertEquals(99, number(lifecycle.pool(pool), "released"));
        accounting();
    }

    @Test
    void confirmRacingClosureCannotLoseACommitment() throws Exception {
        var hold = held(1);
        complete();
        race(() -> closeDecision(), () -> {
            assertEquals(Status.CONFIRMED, reservations.transition(hold.id(), "CONFIRMED", "customer", false).status());
            return 200;
        });
        lifecycle.close(pool, "admin");
        assertEquals(1, number(warehouse(), "allocated"));
        assertEquals(99, number(lifecycle.pool(pool), "released"));
        accounting();
    }

    @Test
    void cancelRacingClosureDoesNotReleaseTwice() throws Exception {
        var hold = held(1);
        complete();
        race(() -> closeDecision(), () -> {
            reservations.transition(hold.id(), "CANCELLED", "customer", false);
            return 200;
        });
        lifecycle.close(pool, "admin");
        assertEquals(0, number(warehouse(), "allocated"));
        accounting();
    }

    @Test
    void tryRacingSaleCompletionAndClosureNeverReservesReleasedCapacity() throws Exception {
        var result = new java.util.concurrent.atomic.AtomicReference<Reservation>();
        race(() -> {
            result.set(held(1));
            return 200;
        }, () -> {
            complete();
            return closeDecision();
        });
        if (result.get().status() == Status.RESERVED)
            reservations.transition(result.get().id(), "CANCELLED", "customer", false);
        lifecycle.close(pool, "admin");
        assertEquals(0, number(lifecycle.pool(pool), "available"));
        assertEquals(Status.FAILED, held(1).status());
        assertEquals(0, number(warehouse(), "allocated"));
        accounting();
    }

    @Test
    void negativeAdjustmentRacingDispatchAndClosureCannotConsumeProtectedUnits() throws Exception {
        var order = confirmed(1);
        complete();
        var adjusted = new java.util.concurrent.atomic.AtomicBoolean();
        race(() -> {
                    lifecycle.close(pool, "admin");
                    return true;
                },
                () -> {
                    lifecycle.fulfill(order.id(), new Shipment("shipment-adjust-race"), "admin");
                    return true;
                },
                () -> {
                    try {
                        catalog.adjustIdempotent(sku, new Adjustment(warehouse, -999, "damage"), "admin", "damage-race-key");
                        adjusted.set(true);
                    } catch (Problem.Rejected e) {
                        assertEquals(409, e.status);
                    }
                    return true;
                });
        assertEquals(adjusted.get() ? 0 : 999, number(warehouse(), "physical"));
        assertEquals(0, number(warehouse(), "allocated"));
        accounting();
    }

    @Test
    void hundredDispatchesAndZeroUnusedClosurePreserveWarehouseTotals() throws Exception {
        List<Reservation> orders = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String customer = "dispatch-customer-" + i;
            var hold = reservations.attempt(new Try(sale, pool, 1), customer, "dispatch-key-" + i, false);
            orders.add(reservations.transition(hold.id(), "CONFIRMED", customer, false));
        }
        complete();
        assertEquals(0, number(lifecycle.close(pool, "admin"), "released"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var bound = new Semaphore(8);
            List<Future<Fulfillment>> work = new ArrayList<>();
            for (var order : orders)
                work.add(executor.submit(() -> {
                    bound.acquire();
                    try {
                        return lifecycle.fulfill(order.id(), new Shipment("shipment-" + order.id()), "admin");
                    } finally {
                        bound.release();
                    }
                }));
            for (var future : work) assertEquals(1, future.get(30, TimeUnit.SECONDS).quantity());
        }
        assertEquals(900, number(warehouse(), "physical"));
        assertEquals(0, number(warehouse(), "allocated"));
        assertEquals(100, number(lifecycle.pool(pool), "fulfilled"));
        recovery.reconcile();
        assertEquals("CONSISTENT", db.queryForObject("SELECT state FROM reconciliation_tasks WHERE allocation_id=? ORDER BY created_at DESC LIMIT 1", String.class, pool));
        accounting();
    }

    @Test
    void warehouseFailureRollsBackFulfillmentAndItsEvent() {
        var order = confirmed(1);
        db.update("UPDATE inventory SET allocated=0 WHERE sku_id=? AND warehouse_id=?", sku, warehouse);
        try {
            assertThrows(Problem.Rejected.class, () -> lifecycle.fulfill(order.id(), new Shipment("shipment-rollback"), "admin"));
            assertEquals(0, db.queryForObject("SELECT count(*) FROM inventory_fulfillments WHERE reservation_id=?", Integer.class, order.id()));
            assertEquals(0, db.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND type='InventoryFulfilled'", Integer.class, order.id()));
            assertEquals(0, number(lifecycle.pool(pool), "fulfilled"));
            assertEquals(1, reservations.get(order.id()).version());
        } finally {
            db.update("UPDATE inventory SET allocated=100 WHERE sku_id=? AND warehouse_id=?", sku, warehouse);
        }
        accounting();
    }

    @Test
    void closureRefusesDivergentReservationTotals() {
        confirmed(1);
        complete();
        db.update("UPDATE inventory_allocations SET committed=0 WHERE id=?", pool);
        try {
            assertThrows(Problem.Rejected.class, () -> lifecycle.close(pool, "admin"));
            assertNull(lifecycle.pool(pool).get("closed_at"));
            assertEquals(100, number(warehouse(), "allocated"));
        } finally {
            db.update("UPDATE inventory_allocations SET committed=1 WHERE id=?", pool);
        }
        accounting();
    }

    @Test
    void databaseChecksRejectImpossibleFulfillmentAndClosure() {
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE inventory_allocations SET fulfilled=1 WHERE id=?", pool));
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE inventory_allocations SET released=1 WHERE id=?", pool));
        held(1);
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE inventory_allocations SET closed_at=clock_timestamp(),closed_by='bad' WHERE id=?", pool));
        accounting();
    }

    @Test
    void reconciliationAuditsFulfillmentLedgerAndWarehouseEncumbrance() {
        var order = confirmed(1);
        lifecycle.fulfill(order.id(), new Shipment("shipment-audit"), "admin");
        complete();
        lifecycle.close(pool, "admin");
        recovery.reconcile();
        assertEquals("CONSISTENT", db.queryForObject("SELECT state FROM reconciliation_tasks WHERE allocation_id=? ORDER BY created_at DESC LIMIT 1", String.class, pool));
        assertEquals("CONSISTENT", db.queryForObject("SELECT state FROM reconciliation_tasks WHERE sku_id=? AND warehouse_id=? AND allocation_id IS NULL ORDER BY created_at DESC LIMIT 1", String.class, sku, warehouse));
        db.update("DELETE FROM inventory_movements WHERE reservation_id=? AND kind='FULFILL'", order.id());
        recovery.reconcile();
        assertEquals("MANUAL_REVIEW", db.queryForObject("SELECT state FROM reconciliation_tasks WHERE allocation_id=? ORDER BY created_at DESC LIMIT 1", String.class, pool));
        assertEquals(999, number(warehouse(), "physical")); // Audit never resets ownership or stock.
        accounting();
    }

    @Test
    void migrationUpgradesExistingConfirmedAllocationsWithoutLosingEncumbrance() throws Exception {
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try {
            org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas(schema).defaultSchema(schema).target("3").load().migrate();
            try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                connection.setSchema(schema);
                var legacy = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
                UUID product = UUID.randomUUID(), legacySku = UUID.randomUUID(), legacyWarehouse = UUID.randomUUID(),
                        legacySale = UUID.randomUUID(), legacyPool = UUID.randomUUID(), order = UUID.randomUUID();
                legacy.update("INSERT INTO products VALUES(?,'Legacy')", product);
                legacy.update("INSERT INTO skus VALUES(?,?,'Legacy')", legacySku, product);
                legacy.update("INSERT INTO warehouses VALUES(?,'Legacy')", legacyWarehouse);
                legacy.update("INSERT INTO inventory(sku_id,warehouse_id,physical,sellable,allocated) VALUES(?,?,100,100,10)", legacySku, legacyWarehouse);
                legacy.update("INSERT INTO flash_sales(id,name,state,starts_at,ends_at,customer_limit,max_quantity,ttl_seconds) VALUES(?,'Legacy','COMPLETED',clock_timestamp()-interval '1 hour',clock_timestamp()+interval '1 hour',10,10,300)", legacySale);
                legacy.update("INSERT INTO inventory_allocations(id,sale_id,sku_id,warehouse_id,capacity,committed) VALUES(?,?,?,?,10,3)", legacyPool, legacySale, legacySku, legacyWarehouse);
                legacy.update("INSERT INTO inventory_reservations(id,sale_id,allocation_id,customer_id,quantity,status,expires_at) VALUES(?,?,?,'legacy-customer',3,'CONFIRMED',clock_timestamp())", order, legacySale, legacyPool);
                legacy.update("INSERT INTO customer_usage VALUES(?,'legacy-customer',3)", legacySale);
                var migrated = org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                        .schemas(schema).defaultSchema(schema).load().migrate();
                assertEquals(1, migrated.migrationsExecuted);
                var migratedPool = legacy.queryForMap("SELECT * FROM inventory_allocations WHERE id=?", legacyPool);
                assertEquals(3, number(migratedPool, "committed"));
                assertEquals(0, number(migratedPool, "fulfilled"));
                assertEquals(0, number(migratedPool, "released"));
                assertNull(migratedPool.get("closed_at"));
                var factory = new org.springframework.transaction.interceptor.TransactionProxyFactoryBean();
                factory.setTarget(new StockLifecycle(legacy, new ObjectMapper()));
                factory.setTransactionManager(new org.springframework.jdbc.datasource.DataSourceTransactionManager(legacy.getDataSource()));
                Properties attributes = new Properties();
                attributes.setProperty("*", "PROPAGATION_REQUIRED");
                factory.setTransactionAttributes(attributes);
                factory.setProxyTargetClass(true);
                factory.afterPropertiesSet();
                var upgraded = (StockLifecycle) factory.getObject();
                assertEquals(7, number(upgraded.close(legacyPool, "migration-admin"), "released"));
                upgraded.fulfill(order, new Shipment("shipment-upgraded"), "migration-admin");
                var stock = legacy.queryForMap("SELECT * FROM inventory WHERE sku_id=? AND warehouse_id=?", legacySku, legacyWarehouse);
                assertEquals(97, number(stock, "physical"));
                assertEquals(0, number(stock, "allocated"));
                assertEquals(3, number(stock, "fulfilled"));
            }
        } finally {
            // Only this test's generated namespace, never application data.
            db.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void administrativeDispatchAndClosureAreProtectedAndOwnersCanReadDispatch() throws Exception {
        var order = confirmed(1);
        mvc.perform(post("/api/v1/admin/reservations/" + order.id() + "/fulfill")
                .with(httpBasic("customer", "customer-local")).contentType("application/json")
                .content("{\"shipmentReference\":\"shipment-http\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/reservations/" + order.id() + "/fulfill")
                        .with(httpBasic("admin", "admin-local")).contentType("application/json")
                        .content("{\"shipmentReference\":\"shipment-http\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(1)).andExpect(jsonPath("$.actor").value("admin"));
        mvc.perform(get("/api/v1/reservations/" + order.id() + "/fulfillment").with(httpBasic("customer", "customer-local")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.shipmentReference").value("shipment-http"));
        var notOwned = reservations.attempt(new Try(sale, pool, 1), "different-owner", "different-owner-key", false);
        mvc.perform(get("/api/v1/reservations/" + notOwned.id() + "/fulfillment").with(httpBasic("customer", "customer-local")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/admin/allocations/" + pool + "/close").with(httpBasic("customer", "customer-local")))
                .andExpect(status().isForbidden());
        reservations.transition(notOwned.id(), "CANCELLED", "different-owner", false);
        complete();
        mvc.perform(post("/api/v1/admin/allocations/" + pool + "/close").with(httpBasic("admin", "admin-local")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(0));
        mvc.perform(get("/api/v1/flash-sales/" + sale + "/availability").with(httpBasic("customer", "customer-local")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].available").value(0));
        accounting();
    }
}
