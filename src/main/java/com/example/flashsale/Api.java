package com.example.flashsale;

import java.util.*;

import jakarta.validation.Valid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import static com.example.flashsale.Model.*;

@RestController
@RequestMapping("/api/v1")
public class Api {
    final JdbcTemplate db;
    final Catalog catalog;
    final Reservations reservations;
    final Admission admission;
    final Recovery recovery;
    final Checkout checkout;

    public Api(JdbcTemplate db, Catalog catalog, Reservations reservations, Admission admission, Recovery recovery, Checkout checkout) {
        this.db = db;
        this.catalog = catalog;
        this.reservations = reservations;
        this.admission = admission;
        this.recovery = recovery;
        this.checkout = checkout;
    }

    static int page(int n) {
        if (n < 0 || n > 100000) throw new Problem.Rejected(400, "Invalid offset");
        return n;
    }

    boolean admin(Authentication a) {
        return a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_ADMIN"));
    }

    @PostMapping("/reservations/try")
    public Reservation attempt(@Valid @RequestBody Try body, @RequestHeader("Idempotency-Key") String key, Authentication a) {
        if (a.getAuthorities().stream().noneMatch(x -> x.getAuthority().equals("ROLE_CUSTOMER")))
            throw new Problem.Rejected(403, "Customer eligibility required");
        admission.acquire(body.saleId(), a.getName());
        try {
            return reservations.attempt(body, a.getName(), key, false);
        } finally {
            admission.release();
        }
    }

    @PostMapping("/reservations/cancel-try")
    public Reservation emptyCancel(@Valid @RequestBody Try body, @RequestHeader("Idempotency-Key") String key, Authentication a) {
        return reservations.attempt(body, a.getName(), key, true);
    }

    @GetMapping("/reservations/{id}")
    public Reservation reservation(@PathVariable UUID id, Authentication a) {
        return reservations.owned(id, a.getName(), admin(a));
    }

    @PostMapping("/reservations/{id}/confirm")
    public Reservation confirm(@PathVariable UUID id, Authentication a) {
        return reservations.transition(id, "CONFIRMED", a.getName(), admin(a));
    }

    @PostMapping("/reservations/{id}/cancel")
    public Reservation cancel(@PathVariable UUID id, Authentication a) {
        return reservations.transition(id, "CANCELLED", a.getName(), admin(a));
    }

    @PostMapping("/reservations/{id}/checkout")
    public Map<String, Object> checkout(@PathVariable UUID id, Authentication a) {
        reservations.owned(id, a.getName(), false);
        return checkout.complete(id, a.getName());
    }

    @GetMapping("/customers/me/reservations")
    public Object mine(Authentication a, @RequestParam(defaultValue = "0") int offset) {
        return db.query("SELECT * FROM inventory_reservations WHERE customer_id=? ORDER BY created_at DESC,id LIMIT 100 OFFSET ?", Reservations.MAPPER, a.getName(), page(offset));
    }

    @PostMapping("/flash-sales")
    public Object sale(@Valid @RequestBody Sale sale) {
        return Map.of("id", catalog.sale(sale));
    }

    @GetMapping("/flash-sales")
    public Object sales(@RequestParam(defaultValue = "0") int offset) {
        return db.queryForList("SELECT * FROM flash_sales ORDER BY starts_at,id LIMIT 100 OFFSET ?", page(offset));
    }

    @GetMapping("/flash-sales/{id}")
    public Object sale(@PathVariable UUID id) {
        var rows = db.queryForList("SELECT * FROM flash_sales WHERE id=?", id);
        if (rows.isEmpty()) throw new Problem.Rejected(404, "Sale not found");
        return rows.getFirst();
    }

    @PostMapping("/flash-sales/{id}/{action:activate|pause|complete|cancel|schedule}")
    public Object state(@PathVariable UUID id, @PathVariable String action) {
        catalog.state(id, switch (action) {
            case "activate" -> "ACTIVE";
            case "pause" -> "PAUSED";
            case "complete" -> "COMPLETED";
            case "cancel" -> "CANCELLED";
            default -> "SCHEDULED";
        });
        return sale(id);
    }

    @PostMapping("/flash-sales/{id}/allocations")
    public Object allocate(@PathVariable UUID id, @Valid @RequestBody Allocation allocation) {
        return Map.of("id", catalog.allocate(id, allocation));
    }

    @GetMapping("/flash-sales/{id}/availability")
    public Object availability(@PathVariable UUID id) {
        return db.queryForList("SELECT *,CASE WHEN closed_at IS NULL THEN capacity-reserved-committed ELSE 0 END AS available,capacity-fulfilled-released AS encumbered,committed-fulfilled AS committed_outstanding FROM inventory_allocations WHERE sale_id=? ORDER BY id LIMIT 100", id);
    }

    @GetMapping("/inventory/{sku}")
    public Object inventory(@PathVariable UUID sku) {
        return db.queryForList("SELECT *,sellable-allocated AS unallocated FROM inventory WHERE sku_id=? ORDER BY warehouse_id LIMIT 100", sku);
    }

    @PostMapping("/inventory/{sku}/adjustments")
    public Object adjust(@PathVariable UUID sku, @Valid @RequestBody Adjustment adjustment, @RequestHeader("Idempotency-Key") String key, Authentication a) {
        catalog.adjustIdempotent(sku, adjustment, a.getName(), key);
        return inventory(sku);
    }

    @PostMapping("/admin/products")
    public Object product(@Valid @RequestBody Named n) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO products VALUES(?,?)", id, n.name());
        return Map.of("id", id);
    }

    @PostMapping("/admin/warehouses")
    public Object warehouse(@Valid @RequestBody Named n) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO warehouses VALUES(?,?)", id, n.name());
        return Map.of("id", id);
    }

    @PostMapping("/admin/skus")
    public Object sku(@Valid @RequestBody Sku n) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO skus VALUES(?,?,?)", id, n.productId(), n.name());
        return Map.of("id", id);
    }

    @PostMapping("/admin/inventory")
    public Object stock(@Valid @RequestBody Stock s) {
        if (s.sellable() > s.physical()) throw new Problem.Rejected(400, "Sellable exceeds physical");
        db.update("INSERT INTO inventory(sku_id,warehouse_id,physical,sellable) VALUES(?,?,?,?)", s.skuId(), s.warehouseId(), s.physical(), s.sellable());
        return inventory(s.skuId());
    }

    @GetMapping("/admin/inventory/reconciliation")
    public Object reconciliation() {
        return db.queryForList("SELECT * FROM reconciliation_tasks ORDER BY created_at DESC LIMIT 100");
    }

    @PostMapping("/admin/inventory/reconciliation/run")
    public Object run() {
        return Map.of("checked", recovery.reconcile());
    }

    @GetMapping("/admin/flash-sales/{id}/metrics")
    public Object metrics(@PathVariable UUID id) {
        return db.queryForList("SELECT status,count(*) AS reservations,sum(quantity) AS units FROM inventory_reservations WHERE sale_id=? GROUP BY status", id);
    }

    public record Named(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 120) String name) {
    }

    public record Sku(@jakarta.validation.constraints.NotNull UUID productId,
                      @jakarta.validation.constraints.NotBlank String name) {
    }
}
