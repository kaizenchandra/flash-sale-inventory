package com.example.flashsale;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.*;

public final class Model {
    private Model() {
    }

    public enum Status {RESERVED, CONFIRMED, CANCELLED, EXPIRED, FAILED}

    public record Try(@NotNull UUID saleId, @NotNull UUID allocationId, @Min(1) @Max(100) int quantity) {
        public String fingerprint() {
            return allocationId + ":" + quantity;
        }
    }

    public record Reservation(UUID id, UUID saleId, UUID allocationId, String customerId, int quantity, Status status,
                              String reason, Instant expiresAt, long version) {
    }

    public record Sale(@NotBlank @Size(max = 120) String name, @NotNull Instant startsAt, @NotNull Instant endsAt,
                       @Min(1) int customerLimit, @Min(1) int maxQuantity, @Min(1) @Max(3600) int ttlSeconds) {
    }

    public record Stock(@NotNull UUID skuId, @NotNull UUID warehouseId, @Min(0) long physical, @Min(0) long sellable) {
    }

    public record Allocation(@NotNull UUID skuId, @NotNull UUID warehouseId, @Min(1) long quantity) {
    }

    public record Shipment(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String shipmentReference) {
    }

    public record Fulfillment(UUID reservationId, String shipmentReference, int quantity, String actor,
                              Instant fulfilledAt) {
    }

    public record Adjustment(@NotNull UUID warehouseId, long delta, @NotBlank @Size(max = 300) String reason) {
    }
}
