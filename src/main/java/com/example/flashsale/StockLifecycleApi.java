package com.example.flashsale;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

import static com.example.flashsale.Model.*;

@RestController
@RequestMapping("/api/v1")
public class StockLifecycleApi {
    private final StockLifecycle lifecycle;
    private final Reservations reservations;

    public StockLifecycleApi(StockLifecycle lifecycle, Reservations reservations) {
        this.lifecycle = lifecycle;
        this.reservations = reservations;
    }

    @GetMapping("/admin/allocations/{id}")
    public Map<String, Object> pool(@PathVariable UUID id) {
        return lifecycle.pool(id);
    }

    @PostMapping("/admin/allocations/{id}/close")
    public Map<String, Object> close(@PathVariable UUID id, Authentication authentication) {
        return lifecycle.close(id, authentication.getName());
    }

    @PostMapping("/admin/reservations/{id}/fulfill")
    public Fulfillment fulfill(@PathVariable UUID id, @Valid @RequestBody Shipment shipment, Authentication authentication) {
        return lifecycle.fulfill(id, shipment, authentication.getName());
    }

    @GetMapping("/reservations/{id}/fulfillment")
    public Fulfillment fulfillment(@PathVariable UUID id, Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        reservations.owned(id, authentication.getName(), admin);
        return lifecycle.fulfillment(id);
    }
}
