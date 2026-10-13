package com.stockwise.erp.api;

import com.stockwise.erp.api.ApiModels.*;
import com.stockwise.erp.auth.ErpUser;
import com.stockwise.erp.service.ErpService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ErpController {
    private final ErpService erp;
    private final AiAssistant ai;

    public ErpController(ErpService erp, AiAssistant ai) { this.erp = erp; this.ai = ai; }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@AuthenticationPrincipal ErpUser user) { return erp.dashboard(user); }

    @GetMapping("/products")
    public List<Map<String, Object>> products(@AuthenticationPrincipal ErpUser user, @RequestParam(required = false) String q) {
        return erp.products(user.organizationId(), q);
    }

    @PostMapping("/products")
    public Map<String, Object> createProduct(@AuthenticationPrincipal ErpUser user, @Valid @RequestBody ProductRequest request) {
        return erp.createProduct(user, request);
    }

    @GetMapping("/customers")
    public List<Map<String, Object>> customers(@AuthenticationPrincipal ErpUser user, @RequestParam(required = false) String q) {
        return erp.customers(user.organizationId(), q);
    }

    @PostMapping("/customers")
    public Map<String, Object> createCustomer(@AuthenticationPrincipal ErpUser user, @Valid @RequestBody PartyRequest request) {
        return erp.createCustomer(user, request);
    }

    @GetMapping("/suppliers")
    public List<Map<String, Object>> suppliers(@AuthenticationPrincipal ErpUser user, @RequestParam(required = false) String q) {
        return erp.suppliers(user.organizationId(), q);
    }

    @PostMapping("/suppliers")
    public Map<String, Object> createSupplier(@AuthenticationPrincipal ErpUser user, @Valid @RequestBody PartyRequest request) {
        return erp.createSupplier(user, request);
    }

    @GetMapping("/orders")
    public List<Map<String, Object>> orders(@AuthenticationPrincipal ErpUser user, @RequestParam(required = false) String q) {
        return erp.orders(user.organizationId(), q);
    }

    @PostMapping("/orders")
    public Map<String, Object> createOrder(@AuthenticationPrincipal ErpUser user, @Valid @RequestBody SalesOrderRequest request) {
        return erp.createSalesOrder(user, request);
    }

    @GetMapping("/purchase-orders")
    public List<Map<String, Object>> purchaseOrders(@AuthenticationPrincipal ErpUser user, @RequestParam(required = false) String q) {
        return erp.purchaseOrders(user.organizationId(), q);
    }

    @GetMapping("/purchase-orders/{id}")
    public Map<String, Object> purchaseOrder(@AuthenticationPrincipal ErpUser user, @PathVariable long id) {
        return erp.purchaseOrderById(user.organizationId(), id);
    }

    @PostMapping("/purchase-orders")
    public Map<String, Object> createPurchaseOrder(@AuthenticationPrincipal ErpUser user, @Valid @RequestBody PurchaseOrderRequest request) {
        return erp.createPurchaseOrder(user, request);
    }

    @PostMapping("/purchase-orders/{id}/receive")
    public Map<String, Object> receivePurchaseOrder(@AuthenticationPrincipal ErpUser user, @PathVariable long id,
                                                   @Valid @RequestBody ReceiveRequest request) {
        return erp.receivePurchaseOrder(user, id, request);
    }

    @GetMapping("/invoices")
    public List<Map<String, Object>> invoices(@AuthenticationPrincipal ErpUser user, @RequestParam(required = false) String q) {
        return erp.invoices(user.organizationId(), q);
    }

    @PostMapping("/invoices/{id}/payments")
    public Map<String, Object> payment(@AuthenticationPrincipal ErpUser user, @PathVariable long id,
                                       @Valid @RequestBody PaymentRequest request) {
        return erp.payInvoice(user, id, request);
    }

    @PostMapping("/stock/adjustments")
    public Map<String, Object> adjustStock(@AuthenticationPrincipal ErpUser user, @Valid @RequestBody StockAdjustmentRequest request) {
        return erp.adjustStock(user, request);
    }

    @GetMapping("/ai/reorder-suggestions")
    public List<Map<String, Object>> reorderSuggestions(@AuthenticationPrincipal ErpUser user) {
        return erp.reorderSuggestions(user.organizationId());
    }

    @PostMapping("/ai/assistant")
    public Map<String, Object> assistant(@AuthenticationPrincipal ErpUser user, @Valid @RequestBody AiAssistant.Question request) {
        return Map.of("answer", ai.answer(user.organizationId(), request.question()), "provider", ai.provider());
    }

    @GetMapping("/reports/sales")
    public Map<String, Object> salesReport(@AuthenticationPrincipal ErpUser user) { return erp.salesReport(user.organizationId()); }

    @PostMapping("/sync/push")
    public List<Map<String, Object>> sync(@AuthenticationPrincipal ErpUser user, @Valid @RequestBody SyncRequest request) {
        return erp.sync(user, request);
    }
}
