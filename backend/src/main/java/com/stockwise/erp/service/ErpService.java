package com.stockwise.erp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockwise.erp.api.ApiModels.*;
import com.stockwise.erp.auth.ErpUser;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ErpService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final BusinessEvents events;

    public ErpService(JdbcTemplate jdbc, ObjectMapper mapper, BusinessEvents events) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.events = events;
    }

    @Cacheable(value = "dashboard", key = "#user.organizationId")
    public Map<String, Object> dashboard(ErpUser user) {
        long org = user.organizationId();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("metrics", jdbc.queryForMap("""
                SELECT
                  (SELECT count(*) FROM products WHERE organization_id=? AND active) AS products,
                  (SELECT count(*) FROM customers WHERE organization_id=? AND status='ACTIVE') AS customers,
                  (SELECT count(*) FROM sales_orders WHERE organization_id=? AND created_at >= date_trunc('month',CURRENT_DATE)) AS orders_this_month,
                  (SELECT COALESCE(sum(total),0) FROM invoices WHERE organization_id=? AND status <> 'VOID' AND created_at >= date_trunc('month',CURRENT_DATE)) AS revenue_this_month,
                  (SELECT count(*) FROM products WHERE organization_id=? AND active AND stock_on_hand <= reorder_point) AS low_stock,
                  (SELECT COALESCE(sum(stock_on_hand * unit_cost),0) FROM products WHERE organization_id=? AND active) AS inventory_value
                """, org, org, org, org, org, org));
        data.put("recentOrders", jdbc.queryForList("""
                SELECT o.id,o.order_number AS \"orderNumber\",c.name AS customer,o.status,o.total,
                       o.created_at AS \"createdAt\"
                FROM sales_orders o JOIN customers c ON c.id=o.customer_id
                WHERE o.organization_id=? ORDER BY o.created_at DESC LIMIT 7
                """, org));
        data.put("salesTrend", jdbc.queryForList("""
                SELECT to_char(month,'Mon') AS label,COALESCE(sum(o.total),0) AS total
                FROM generate_series(date_trunc('month',CURRENT_DATE)-interval '5 months',date_trunc('month',CURRENT_DATE),interval '1 month') month
                LEFT JOIN sales_orders o ON date_trunc('month',o.created_at)=month AND o.organization_id=?
                GROUP BY month ORDER BY month
                """, org));
        data.put("lowStockItems", lowStock(org, 5));
        data.put("categoryMix", jdbc.queryForList("""
                SELECT category AS label, count(*) AS count
                FROM products WHERE organization_id=? AND active GROUP BY category ORDER BY count DESC LIMIT 5
                """, org));
        return data;
    }

    public List<Map<String, Object>> products(long org, String search) {
        String pattern = search == null ? "%%" : "%" + search.trim() + "%";
        return jdbc.queryForList("""
                SELECT id,sku,name,category,unit,unit_price AS \"unitPrice\",unit_cost AS \"unitCost\",
                       stock_on_hand AS \"stockOnHand\",reorder_point AS \"reorderPoint\",active
                FROM products WHERE organization_id=? AND active AND (name ILIKE ? OR sku ILIKE ? OR category ILIKE ?)
                ORDER BY name LIMIT 250
                """, org, pattern, pattern, pattern);
    }

    @Transactional
    @CacheEvict(value = "dashboard", allEntries = true)
    public Map<String, Object> createProduct(ErpUser actor, ProductRequest request) {
        requireRole(actor, "ADMIN", "WAREHOUSE");
        long id = jdbc.queryForObject("""
                INSERT INTO products(organization_id,sku,name,category,unit,unit_price,unit_cost,stock_on_hand,reorder_point)
                VALUES (?,?,?,?,?,?,?,?,?) RETURNING id
                """, Long.class, actor.organizationId(), request.sku().trim().toUpperCase(), request.name().trim(),
                request.category().trim(), request.unit().trim(), money(request.unitPrice()), money(request.unitCost()),
                request.stockOnHand(), request.reorderPoint());
        if (request.stockOnHand() > 0) movement(actor, id, "OPENING_BALANCE", request.stockOnHand(), "PRODUCT", id, "Opening balance");
        audit(actor, "PRODUCT_CREATED", "PRODUCT", id, Map.of("sku", request.sku()));
        events.publish(actor, "ProductCreated", "PRODUCT", String.valueOf(id), Map.of("sku", request.sku(), "name", request.name()));
        return oneProduct(actor.organizationId(), id);
    }

    public List<Map<String, Object>> customers(long org, String search) {
        String pattern = search == null ? "%%" : "%" + search.trim() + "%";
        return jdbc.queryForList("""
                SELECT id,customer_code AS \"customerCode\",name,email,phone,city,credit_limit AS \"creditLimit\",status
                FROM customers WHERE organization_id=? AND (name ILIKE ? OR customer_code ILIKE ? OR city ILIKE ?)
                ORDER BY name LIMIT 250
                """, org, pattern, pattern, pattern);
    }

    @Transactional
    @CacheEvict(value = "dashboard", allEntries = true)
    public Map<String, Object> createCustomer(ErpUser actor, PartyRequest request) {
        requireRole(actor, "ADMIN", "SALES");
        String code = "CUS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        long id = jdbc.queryForObject("""
                INSERT INTO customers(organization_id,customer_code,name,email,phone,city,credit_limit)
                VALUES (?,?,?,?,?,?,?) RETURNING id
                """, Long.class, actor.organizationId(), code, request.name().trim(), request.email(), request.phone(),
                request.city(), request.creditLimit() == null ? BigDecimal.ZERO : money(request.creditLimit()));
        audit(actor, "CUSTOMER_CREATED", "CUSTOMER", id, Map.of("code", code));
        return jdbc.queryForMap("SELECT id,customer_code AS \"customerCode\",name,email,phone,city,credit_limit AS \"creditLimit\",status FROM customers WHERE id=?", id);
    }

    public List<Map<String, Object>> suppliers(long org, String search) {
        String pattern = search == null ? "%%" : "%" + search.trim() + "%";
        return jdbc.queryForList("""
                SELECT id,supplier_code AS \"supplierCode\",name,email,phone,city,lead_time_days AS \"leadTimeDays\",status
                FROM suppliers WHERE organization_id=? AND (name ILIKE ? OR supplier_code ILIKE ? OR city ILIKE ?)
                ORDER BY name LIMIT 250
                """, org, pattern, pattern, pattern);
    }

    @Transactional
    @CacheEvict(value = "dashboard", allEntries = true)
    public Map<String, Object> createSupplier(ErpUser actor, PartyRequest request) {
        requireRole(actor, "ADMIN", "PROCUREMENT");
        String code = "SUP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        long leadDays = request.leadTimeDays() == null ? 7 : request.leadTimeDays();
        long id = jdbc.queryForObject("""
                INSERT INTO suppliers(organization_id,supplier_code,name,email,phone,city,lead_time_days)
                VALUES (?,?,?,?,?,?,?) RETURNING id
                """, Long.class, actor.organizationId(), code, request.name().trim(), request.email(), request.phone(), request.city(), leadDays);
        audit(actor, "SUPPLIER_CREATED", "SUPPLIER", id, Map.of("code", code));
        return jdbc.queryForMap("SELECT id,supplier_code AS \"supplierCode\",name,email,phone,city,lead_time_days AS \"leadTimeDays\",status FROM suppliers WHERE id=?", id);
    }

    public List<Map<String, Object>> orders(long org, String search) {
        String pattern = search == null ? "%%" : "%" + search.trim() + "%";
        return jdbc.queryForList("""
                SELECT o.id,o.order_number AS \"orderNumber\",c.name AS customer,o.status,o.subtotal,o.tax_amount AS \"taxAmount\",
                       o.total,o.created_at AS \"createdAt\",u.full_name AS \"createdBy\"
                FROM sales_orders o JOIN customers c ON c.id=o.customer_id LEFT JOIN app_users u ON u.id=o.created_by
                WHERE o.organization_id=? AND (o.order_number ILIKE ? OR c.name ILIKE ?)
                ORDER BY o.created_at DESC LIMIT 250
                """, org, pattern, pattern);
    }

    @Transactional
    @CacheEvict(value = "dashboard", allEntries = true)
    public Map<String, Object> createSalesOrder(ErpUser actor, SalesOrderRequest request) {
        return createSalesOrderInternal(actor, request);
    }

    private Map<String, Object> createSalesOrderInternal(ErpUser actor, SalesOrderRequest request) {
        requireRole(actor, "ADMIN", "SALES");
        if (request == null || request.customerId() < 1 || request.items() == null || request.items().isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A customer and at least one order line are required");
        long org = actor.organizationId();
        requireExists("SELECT id FROM customers WHERE organization_id=? AND id=? AND status='ACTIVE'", org, request.customerId());
        List<OrderItem> items = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (OrderLineRequest line : request.items()) {
            if (line == null || line.productId() < 1 || line.quantity() < 1)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each order line needs a product and a positive quantity");
            Map<String, Object> product = lockProduct(org, line.productId());
            int stock = ((Number) product.get("stock_on_hand")).intValue();
            if (stock < line.quantity()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Not enough stock for " + product.get("name") + ": " + stock + " available");
            BigDecimal price = (BigDecimal) product.get("unit_price");
            BigDecimal lineTotal = money(price.multiply(BigDecimal.valueOf(line.quantity())));
            subtotal = subtotal.add(lineTotal);
            items.add(new OrderItem(line.productId(), line.quantity(), price, lineTotal));
        }
        subtotal = money(subtotal);
        String number = "SO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        long orderId = jdbc.queryForObject("""
                INSERT INTO sales_orders(organization_id,order_number,customer_id,status,subtotal,tax_amount,total,created_by)
                VALUES (?,?,?,'CONFIRMED',?,0,?,?) RETURNING id
                """, Long.class, org, number, request.customerId(), subtotal, subtotal, actor.id());
        for (OrderItem item : items) {
            jdbc.update("INSERT INTO sales_order_items(sales_order_id,product_id,quantity,unit_price,line_total) VALUES (?,?,?,?,?)",
                    orderId, item.productId, item.quantity, item.price, item.total);
            jdbc.update("UPDATE products SET stock_on_hand=stock_on_hand-? WHERE organization_id=? AND id=?", item.quantity, org, item.productId);
            movement(actor, item.productId, "SALE", -item.quantity, "SALES_ORDER", orderId, "Allocated to " + number);
        }
        String invoiceNumber = "INV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("""
                INSERT INTO invoices(organization_id,invoice_number,sales_order_id,customer_id,status,subtotal,total,due_date)
                VALUES (?,?,?,?,'UNPAID',?,?,?)
                """, org, invoiceNumber, orderId, request.customerId(), subtotal, subtotal, LocalDate.now().plusDays(30));
        audit(actor, "SALES_ORDER_CREATED", "SALES_ORDER", orderId, Map.of("orderNumber", number, "total", subtotal));
        events.publish(actor, "SalesOrderConfirmed", "SALES_ORDER", String.valueOf(orderId),
                Map.of("orderNumber", number, "customerId", request.customerId(), "total", subtotal));
        return order(org, orderId);
    }

    public List<Map<String, Object>> purchaseOrders(long org, String search) {
        String pattern = search == null ? "%%" : "%" + search.trim() + "%";
        return jdbc.queryForList("""
                SELECT p.id,p.po_number AS \"poNumber\",s.name AS supplier,p.status,p.total,
                       p.expected_date AS \"expectedDate\",p.created_at AS \"createdAt\",
                       COALESCE(sum(i.quantity_ordered),0) AS \"unitsOrdered\",COALESCE(sum(i.quantity_received),0) AS \"unitsReceived\"
                FROM purchase_orders p JOIN suppliers s ON s.id=p.supplier_id
                LEFT JOIN purchase_order_items i ON i.purchase_order_id=p.id
                WHERE p.organization_id=? AND (p.po_number ILIKE ? OR s.name ILIKE ?)
                GROUP BY p.id,s.name ORDER BY p.created_at DESC LIMIT 250
                """, org, pattern, pattern);
    }

    public Map<String, Object> purchaseOrderById(long org, long id) {
        return purchaseOrder(org, id);
    }

    @Transactional
    @CacheEvict(value = "dashboard", allEntries = true)
    public Map<String, Object> createPurchaseOrder(ErpUser actor, PurchaseOrderRequest request) {
        requireRole(actor, "ADMIN", "PROCUREMENT");
        long org = actor.organizationId();
        requireExists("SELECT id FROM suppliers WHERE organization_id=? AND id=? AND status='ACTIVE'", org, request.supplierId());
        BigDecimal total = BigDecimal.ZERO;
        List<PurchaseItem> items = new ArrayList<>();
        for (PurchaseLineRequest line : request.items()) {
            Map<String, Object> product = product(org, line.productId());
            BigDecimal cost = (BigDecimal) product.get("unit_cost");
            BigDecimal lineTotal = money(cost.multiply(BigDecimal.valueOf(line.quantity())));
            total = total.add(lineTotal);
            items.add(new PurchaseItem(line.productId(), line.quantity(), cost, lineTotal));
        }
        total = money(total);
        String number = "PO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        long poId = jdbc.queryForObject("""
                INSERT INTO purchase_orders(organization_id,po_number,supplier_id,status,total,expected_date,created_by)
                VALUES (?,?,?,'OPEN',?,?,?) RETURNING id
                """, Long.class, org, number, request.supplierId(), total, request.expectedDate(), actor.id());
        for (PurchaseItem item : items) jdbc.update("""
                INSERT INTO purchase_order_items(purchase_order_id,product_id,quantity_ordered,unit_cost,line_total)
                VALUES (?,?,?,?,?)
                """, poId, item.productId, item.quantity, item.cost, item.total);
        audit(actor, "PURCHASE_ORDER_CREATED", "PURCHASE_ORDER", poId, Map.of("poNumber", number, "total", total));
        events.publish(actor, "PurchaseOrderCreated", "PURCHASE_ORDER", String.valueOf(poId), Map.of("poNumber", number, "total", total));
        return purchaseOrder(org, poId);
    }

    @Transactional
    @CacheEvict(value = "dashboard", allEntries = true)
    public Map<String, Object> receivePurchaseOrder(ErpUser actor, long poId, ReceiveRequest request) {
        requireRole(actor, "ADMIN", "PROCUREMENT", "WAREHOUSE");
        long org = actor.organizationId();
        Map<String, Object> po = lockPurchaseOrder(org, poId);
        if ("RECEIVED".equals(po.get("status")) || "CANCELLED".equals(po.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This purchase order cannot receive additional stock");
        for (ReceiptLineRequest received : request.items()) {
            Map<String, Object> line = jdbc.queryForMap("""
                    SELECT id,product_id,quantity_ordered,quantity_received FROM purchase_order_items
                    WHERE purchase_order_id=? AND id=? FOR UPDATE
                    """, poId, received.lineId());
            int ordered = ((Number) line.get("quantity_ordered")).intValue();
            int already = ((Number) line.get("quantity_received")).intValue();
            if (already + received.quantityReceived() > ordered)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Received quantity exceeds the open quantity on a line");
            long productId = ((Number) line.get("product_id")).longValue();
            lockProduct(org, productId);
            jdbc.update("UPDATE purchase_order_items SET quantity_received=quantity_received+? WHERE id=?", received.quantityReceived(), received.lineId());
            jdbc.update("UPDATE products SET stock_on_hand=stock_on_hand+? WHERE organization_id=? AND id=?", received.quantityReceived(), org, productId);
            movement(actor, productId, "PURCHASE_RECEIPT", received.quantityReceived(), "PURCHASE_ORDER", poId, "Received against " + po.get("po_number"));
        }
        int remaining = jdbc.queryForObject("SELECT count(*) FROM purchase_order_items WHERE purchase_order_id=? AND quantity_received<quantity_ordered", Integer.class, poId);
        String status = remaining == 0 ? "RECEIVED" : "PARTIAL";
        jdbc.update("UPDATE purchase_orders SET status=? WHERE id=?", status, poId);
        audit(actor, "PURCHASE_ORDER_RECEIVED", "PURCHASE_ORDER", poId, Map.of("status", status));
        events.publish(actor, "PurchaseOrderReceived", "PURCHASE_ORDER", String.valueOf(poId), Map.of("status", status));
        return purchaseOrder(org, poId);
    }

    public List<Map<String, Object>> invoices(long org, String search) {
        String pattern = search == null ? "%%" : "%" + search.trim() + "%";
        return jdbc.queryForList("""
                SELECT i.id,i.invoice_number AS \"invoiceNumber\",c.name AS customer,i.status,i.subtotal,i.total,
                       i.paid_amount AS \"paidAmount\",(i.total-i.paid_amount) AS balance,i.due_date AS \"dueDate\",
                       i.created_at AS \"createdAt\",o.order_number AS \"orderNumber\"
                FROM invoices i JOIN customers c ON c.id=i.customer_id JOIN sales_orders o ON o.id=i.sales_order_id
                WHERE i.organization_id=? AND (i.invoice_number ILIKE ? OR c.name ILIKE ?)
                ORDER BY i.due_date ASC LIMIT 250
                """, org, pattern, pattern);
    }

    @Transactional
    @CacheEvict(value = "dashboard", allEntries = true)
    public Map<String, Object> payInvoice(ErpUser actor, long invoiceId, PaymentRequest request) {
        requireRole(actor, "ADMIN", "FINANCE");
        Map<String, Object> invoice;
        try {
            invoice = jdbc.queryForMap("SELECT id,invoice_number,total,paid_amount,status FROM invoices WHERE organization_id=? AND id=? FOR UPDATE", actor.organizationId(), invoiceId);
        } catch (EmptyResultDataAccessException ex) { throw notFound("Invoice not found"); }
        BigDecimal total = (BigDecimal) invoice.get("total");
        BigDecimal paid = (BigDecimal) invoice.get("paid_amount");
        BigDecimal amount = money(request.amount());
        if (amount.compareTo(total.subtract(paid)) > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment exceeds the invoice balance");
        BigDecimal newPaid = paid.add(amount);
        String status = newPaid.compareTo(total) == 0 ? "PAID" : "PARTIAL";
        jdbc.update("INSERT INTO invoice_payments(invoice_id,amount,reference,actor_id) VALUES (?,?,?,?)", invoiceId, amount, request.reference(), actor.id());
        jdbc.update("UPDATE invoices SET paid_amount=?,status=? WHERE id=?", newPaid, status, invoiceId);
        audit(actor, "INVOICE_PAYMENT_RECORDED", "INVOICE", invoiceId, Map.of("amount", amount, "status", status));
        events.publish(actor, "InvoicePaymentRecorded", "INVOICE", String.valueOf(invoiceId), Map.of("amount", amount, "status", status));
        return jdbc.queryForMap("""
                SELECT id,invoice_number AS \"invoiceNumber\",status,total,paid_amount AS \"paidAmount\",(total-paid_amount) AS balance
                FROM invoices WHERE id=?
                """, invoiceId);
    }

    @Transactional
    @CacheEvict(value = "dashboard", allEntries = true)
    public Map<String, Object> adjustStock(ErpUser actor, StockAdjustmentRequest request) {
        requireRole(actor, "ADMIN", "WAREHOUSE");
        if (request == null || request.productId() < 1 || request.quantityDelta() == null || request.reason() == null || request.reason().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A product, quantity change, and reason are required");
        if (request.quantityDelta() == 0) throw new IllegalArgumentException("quantityDelta must not be zero");
        long org = actor.organizationId();
        Map<String, Object> product = lockProduct(org, request.productId());
        int stock = ((Number) product.get("stock_on_hand")).intValue();
        if (stock + request.quantityDelta() < 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Adjustment would make stock negative");
        jdbc.update("UPDATE products SET stock_on_hand=stock_on_hand+? WHERE organization_id=? AND id=?", request.quantityDelta(), org, request.productId());
        movement(actor, request.productId(), "COUNT_ADJUSTMENT", request.quantityDelta(), "MANUAL", null, request.reason());
        audit(actor, "STOCK_ADJUSTED", "PRODUCT", request.productId(), Map.of("quantityDelta", request.quantityDelta(), "reason", request.reason()));
        events.publish(actor, "StockAdjusted", "PRODUCT", String.valueOf(request.productId()), Map.of("quantityDelta", request.quantityDelta()));
        return oneProduct(org, request.productId());
    }

    public List<Map<String, Object>> reorderSuggestions(long org) {
        return jdbc.queryForList("""
                SELECT p.id,p.sku,p.name,p.category,p.stock_on_hand AS \"stockOnHand\",p.reorder_point AS \"reorderPoint\",
                       COALESCE(sold.units_30d,0) AS \"unitsSold30d\",
                       GREATEST(p.reorder_point*2,CEIL(COALESCE(sold.units_30d,0)/30.0*21)::int+p.reorder_point-p.stock_on_hand,0) AS \"suggestedOrder\",
                       CASE WHEN p.stock_on_hand=0 THEN 'Stockout risk' WHEN p.stock_on_hand<=p.reorder_point THEN 'Below reorder point' ELSE 'Demand forecast' END AS reason
                FROM products p
                LEFT JOIN (SELECT i.product_id,sum(i.quantity) AS units_30d FROM sales_order_items i
                           JOIN sales_orders o ON o.id=i.sales_order_id
                           WHERE o.organization_id=? AND o.created_at>=now()-interval '30 days' GROUP BY i.product_id) sold
                  ON sold.product_id=p.id
                WHERE p.organization_id=? AND p.active AND p.stock_on_hand<=p.reorder_point*2
                ORDER BY p.stock_on_hand::numeric/GREATEST(p.reorder_point,1),p.name
                """, org, org);
    }

    public Map<String, Object> salesReport(long org) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("monthly", jdbc.queryForList("""
                SELECT to_char(month,'YYYY-MM') AS period,to_char(month,'Mon YYYY') AS label,COALESCE(sum(o.total),0) AS total,
                       count(o.id) AS orders
                FROM generate_series(date_trunc('month',CURRENT_DATE)-interval '11 months',date_trunc('month',CURRENT_DATE),interval '1 month') month
                LEFT JOIN sales_orders o ON date_trunc('month',o.created_at)=month AND o.organization_id=?
                GROUP BY month ORDER BY month
                """, org));
        report.put("topCustomers", jdbc.queryForList("""
                SELECT c.name AS customer,count(o.id) AS orders,COALESCE(sum(o.total),0) AS revenue
                FROM customers c LEFT JOIN sales_orders o ON o.customer_id=c.id AND o.organization_id=?
                WHERE c.organization_id=? GROUP BY c.id ORDER BY revenue DESC LIMIT 5
                """, org, org));
        report.put("summary", jdbc.queryForMap("""
                SELECT count(*) AS orders,COALESCE(sum(total),0) AS revenue,
                       COALESCE(avg(total),0) AS \"averageOrderValue\"
                FROM sales_orders WHERE organization_id=? AND created_at>=date_trunc('month',CURRENT_DATE)
                """, org));
        return report;
    }

    @Transactional
    @CacheEvict(value = "dashboard", allEntries = true)
    public List<Map<String, Object>> sync(ErpUser actor, SyncRequest request) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (SyncOperation operation : request.operations()) {
            Map<String, Object> existing = findSynced(actor.organizationId(), operation.operationKey());
            if (existing != null) {
                results.add(Map.of("operationKey", operation.operationKey(), "status", "already_applied", "result", existing));
                continue;
            }
            Map<String, Object> result = switch (operation.type()) {
                case "CREATE_SALES_ORDER" -> createSalesOrderInternal(actor, mapper.convertValue(operation.payload(), SalesOrderRequest.class));
                case "ADJUST_STOCK" -> adjustStock(actor, mapper.convertValue(operation.payload(), StockAdjustmentRequest.class));
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported offline operation: " + operation.type());
            };
            try {
                jdbc.update("INSERT INTO sync_operations(organization_id,operation_key,operation_type,result) VALUES (?,?,?,?::jsonb)",
                        actor.organizationId(), operation.operationKey(), operation.type(), mapper.writeValueAsString(result));
            } catch (JsonProcessingException ex) { throw new IllegalStateException("Could not record offline operation", ex); }
            results.add(Map.of("operationKey", operation.operationKey(), "status", "applied", "result", result));
        }
        return results;
    }

    private Map<String, Object> findSynced(long org, String key) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT result::text AS result FROM sync_operations WHERE organization_id=? AND operation_key=?", org, key);
        if (rows.isEmpty()) return null;
        Object value = rows.getFirst().get("result");
        try {
            if (value instanceof String json) return mapper.readValue(json, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            return mapper.convertValue(value, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (JsonProcessingException ex) { throw new IllegalStateException("Could not read offline operation result", ex); }
    }

    public Map<String, Object> assistantContext(long org) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("dashboard", Map.of("metrics", jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM products WHERE organization_id=? AND active) AS products,
                       (SELECT count(*) FROM sales_orders WHERE organization_id=? AND created_at>=date_trunc('month',CURRENT_DATE)) AS orders_this_month,
                       (SELECT COALESCE(sum(total),0) FROM invoices WHERE organization_id=? AND status<>'VOID' AND created_at>=date_trunc('month',CURRENT_DATE)) AS revenue_this_month
                """, org, org, org)));
        context.put("reorderSuggestions", reorderSuggestions(org));
        return context;
    }

    private List<Map<String, Object>> lowStock(long org, int limit) {
        return jdbc.queryForList("""
                SELECT id,sku,name,category,stock_on_hand AS \"stockOnHand\",reorder_point AS \"reorderPoint\"
                FROM products WHERE organization_id=? AND active AND stock_on_hand<=reorder_point
                ORDER BY (stock_on_hand::numeric/GREATEST(reorder_point,1)),name LIMIT ?
                """, org, limit);
    }

    private Map<String, Object> order(long org, long id) {
        Map<String, Object> order = jdbc.queryForMap("""
                SELECT o.id,o.order_number AS \"orderNumber\",c.name AS customer,o.status,o.subtotal,o.total,
                       o.created_at AS \"createdAt\",i.invoice_number AS \"invoiceNumber\"
                FROM sales_orders o JOIN customers c ON c.id=o.customer_id LEFT JOIN invoices i ON i.sales_order_id=o.id
                WHERE o.organization_id=? AND o.id=?
                """, org, id);
        order.put("items", jdbc.queryForList("""
                SELECT p.sku,p.name,i.quantity,i.unit_price AS \"unitPrice\",i.line_total AS \"lineTotal\"
                FROM sales_order_items i JOIN products p ON p.id=i.product_id WHERE i.sales_order_id=?
                """, id));
        return order;
    }

    private Map<String, Object> purchaseOrder(long org, long id) {
        Map<String, Object> order = jdbc.queryForMap("""
                SELECT p.id,p.po_number AS \"poNumber\",s.name AS supplier,p.status,p.total,
                       p.expected_date AS \"expectedDate\",p.created_at AS \"createdAt\"
                FROM purchase_orders p JOIN suppliers s ON s.id=p.supplier_id WHERE p.organization_id=? AND p.id=?
                """, org, id);
        order.put("items", jdbc.queryForList("""
                SELECT i.id AS \"lineId\",p.sku,p.name,i.quantity_ordered AS \"quantityOrdered\",
                       i.quantity_received AS \"quantityReceived\",i.unit_cost AS \"unitCost\"
                FROM purchase_order_items i JOIN products p ON p.id=i.product_id WHERE i.purchase_order_id=?
                """, id));
        return order;
    }

    private Map<String, Object> oneProduct(long org, long id) {
        try { return jdbc.queryForMap("""
                SELECT id,sku,name,category,unit,unit_price AS \"unitPrice\",unit_cost AS \"unitCost\",
                       stock_on_hand AS \"stockOnHand\",reorder_point AS \"reorderPoint\",active
                FROM products WHERE organization_id=? AND id=?
                """, org, id); }
        catch (EmptyResultDataAccessException ex) { throw notFound("Product not found"); }
    }

    private Map<String, Object> product(long org, long id) {
        try { return jdbc.queryForMap("SELECT id,unit_cost,unit_price,stock_on_hand,name FROM products WHERE organization_id=? AND id=? AND active", org, id); }
        catch (EmptyResultDataAccessException ex) { throw notFound("Product not found"); }
    }

    private Map<String, Object> lockProduct(long org, long id) {
        try { return jdbc.queryForMap("SELECT id,unit_cost,unit_price,stock_on_hand,name FROM products WHERE organization_id=? AND id=? AND active FOR UPDATE", org, id); }
        catch (EmptyResultDataAccessException ex) { throw notFound("Product not found"); }
    }

    private Map<String, Object> lockPurchaseOrder(long org, long id) {
        try { return jdbc.queryForMap("SELECT id,po_number,status FROM purchase_orders WHERE organization_id=? AND id=? FOR UPDATE", org, id); }
        catch (EmptyResultDataAccessException ex) { throw notFound("Purchase order not found"); }
    }

    private void requireExists(String sql, Object... args) {
        List<Long> rows = jdbc.query(sql, (rs, row) -> rs.getLong(1), args);
        if (rows.isEmpty()) throw notFound("Related record not found");
    }

    private void movement(ErpUser actor, long productId, String type, int delta, String referenceType, Long referenceId, String reason) {
        jdbc.update("""
                INSERT INTO inventory_movements(organization_id,product_id,movement_type,quantity_delta,reference_type,reference_id,reason,actor_id)
                VALUES (?,?,?,?,?,?,?,?)
                """, actor.organizationId(), productId, type, delta, referenceType, referenceId, reason, actor.id());
    }

    private void audit(ErpUser actor, String action, String entityType, long entityId, Object details) {
        try {
            jdbc.update("INSERT INTO audit_log(organization_id,actor_id,action,entity_type,entity_id,details) VALUES (?,?,?,?,?,?::jsonb)",
                    actor.organizationId(), actor.id(), action, entityType, String.valueOf(entityId), mapper.writeValueAsString(details));
        } catch (JsonProcessingException ex) { throw new IllegalStateException("Could not write audit entry", ex); }
    }

    private void requireRole(ErpUser user, String... roles) {
        for (String role : roles) if (role.equals(user.role())) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role cannot perform this action");
    }

    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
    private BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }
    private record OrderItem(long productId, int quantity, BigDecimal price, BigDecimal total) { }
    private record PurchaseItem(long productId, int quantity, BigDecimal cost, BigDecimal total) { }
}
