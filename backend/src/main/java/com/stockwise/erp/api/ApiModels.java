package com.stockwise.erp.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ApiModels {
    private ApiModels() { }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) { }
    public record ProductRequest(@NotBlank @Size(max = 48) String sku,
                                 @NotBlank @Size(max = 180) String name,
                                 @NotBlank String category,
                                 @NotBlank String unit,
                                 @NotNull @DecimalMin("0") BigDecimal unitPrice,
                                 @NotNull @DecimalMin("0") BigDecimal unitCost,
                                 @Min(0) int stockOnHand,
                                 @Min(0) int reorderPoint) { }
    public record PartyRequest(@NotBlank @Size(max = 180) String name,
                               @Email String email,
                               String phone,
                               String city,
                               BigDecimal creditLimit,
                               Integer leadTimeDays) { }
    public record OrderLineRequest(@Positive long productId, @Positive int quantity) { }
    public record SalesOrderRequest(@Positive long customerId, @NotEmpty List<@Valid OrderLineRequest> items) { }
    public record PurchaseOrderRequest(@Positive long supplierId,
                                       @NotNull LocalDate expectedDate,
                                       @NotEmpty List<@Valid PurchaseLineRequest> items) { }
    public record PurchaseLineRequest(@Positive long productId, @Positive int quantity) { }
    public record ReceiptLineRequest(@Positive long lineId, @Positive int quantityReceived) { }
    public record ReceiveRequest(@NotEmpty List<@Valid ReceiptLineRequest> items) { }
    public record StockAdjustmentRequest(@Positive long productId, @NotNull Integer quantityDelta, @NotBlank String reason) { }
    public record PaymentRequest(@NotNull @DecimalMin(value = "0.01") BigDecimal amount, @Size(max = 100) String reference) { }
    public record SyncOperation(@NotBlank String operationKey, @NotBlank String type, @NotNull JsonNode payload) { }
    public record SyncRequest(@NotEmpty List<@Valid SyncOperation> operations) { }
}
