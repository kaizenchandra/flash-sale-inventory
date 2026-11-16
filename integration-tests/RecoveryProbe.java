import java.sql.*;
import java.util.UUID;

/**
 * Local-only integration probe against the demo database and a running recovery worker.
 * java -cp <postgresql-jdbc.jar> integration-tests/RecoveryProbe.java
 * DB_URL/DB_USER/DB_PASSWORD select the isolated verification database.
 */
public class RecoveryProbe {
    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/flashsale?socketTimeout=10"), System.getenv().getOrDefault("DB_USER", "flashsale"), System.getenv().getOrDefault("DB_PASSWORD", "local-only"))) {
            for (boolean expired : new boolean[]{false, true}) {
                UUID product = UUID.randomUUID(), sku = UUID.randomUUID(), warehouse = UUID.randomUUID(), sale = UUID.randomUUID(), pool = UUID.randomUUID(), reservation = UUID.randomUUID();
                c.setAutoCommit(false);
                update(c, "INSERT INTO products VALUES(?,'Recovery probe')", product);
                update(c, "INSERT INTO skus VALUES(?,?,'Probe')", sku, product);
                update(c, "INSERT INTO warehouses VALUES(?,'Probe')", warehouse);
                update(c, "INSERT INTO inventory(sku_id,warehouse_id,physical,sellable,allocated) VALUES(?,?,1,1,1)", sku, warehouse);
                update(c, "INSERT INTO flash_sales(id,name,state,starts_at,ends_at,customer_limit,max_quantity,ttl_seconds) VALUES(?,'Probe','ACTIVE',clock_timestamp()-interval '1 minute',clock_timestamp()+interval '1 hour',1,1,300)", sale);
                update(c, "INSERT INTO inventory_allocations(id,sale_id,sku_id,warehouse_id,capacity,reserved) VALUES(?,?,?,?,1,1)", pool, sale, sku, warehouse);
                update(c, "INSERT INTO customer_usage VALUES(?,'recovery-probe',1)", sale);
                update(c, "INSERT INTO inventory_reservations(id,sale_id,allocation_id,customer_id,quantity,status,expires_at) VALUES(?,?,?,'recovery-probe',1,'RESERVED',clock_timestamp()+(? * interval '1 second'))", reservation, sale, pool, expired ? -1 : 300);
                update(c, "INSERT INTO simulated_payments(reservation_id,state) VALUES(?,'AUTHORIZED')", reservation);
                update(c, "INSERT INTO checkout_sagas(reservation_id,state,payment_reference) VALUES(?,'AUTHORIZED',?)", reservation, "simulated-" + reservation);
                c.commit();
                c.setAutoCommit(true);
                String expected = expired ? "AUTHORIZATION_VOIDED" : "ORDER_CONFIRMED";
                boolean complete = false;
                for (int attempt = 0; attempt < 60; attempt++) {
                    try (var statement = c.prepareStatement("SELECT state FROM checkout_sagas WHERE reservation_id=?")) {
                        statement.setObject(1, reservation);
                        try (var rs = statement.executeQuery()) {
                            rs.next();
                            if (expected.equals(rs.getString(1))) {
                                complete = true;
                                break;
                            }
                        }
                    }
                    Thread.sleep(1000);
                }
                if (!complete) throw new AssertionError("Recovery did not finish: " + reservation);
                try (var s = c.prepareStatement("SELECT r.status,p.state,a.reserved,a.committed FROM inventory_reservations r JOIN simulated_payments p ON p.reservation_id=r.id JOIN inventory_allocations a ON a.id=r.allocation_id WHERE r.id=?")) {
                    s.setObject(1, reservation);
                    try (var rs = s.executeQuery()) {
                        rs.next();
                        if (!rs.getString(1).equals(expired ? "EXPIRED" : "CONFIRMED") || !rs.getString(2).equals(expired ? "VOIDED" : "CAPTURED") || rs.getLong(3) != 0 || rs.getLong(4) != (expired ? 0 : 1))
                            throw new AssertionError("Recovery accounting mismatch");
                    }
                }
                System.out.println("PASS authorization-boundary recovery: " + expected + " reservation=" + reservation);
            }
        }
    }

    static void update(Connection c, String sql, Object... values) throws SQLException {
        try (var s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
            s.executeUpdate();
        }
    }
}
