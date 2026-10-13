package com.stockwise.erp.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class SeedData implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    public SeedData(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.update("INSERT INTO organizations(name,slug) VALUES ('Northstar Supply Co.','northstar') ON CONFLICT (slug) DO NOTHING");
        long org = jdbc.queryForObject("SELECT id FROM organizations WHERE slug='northstar'", Long.class);
        seedUser(org, "Maya Chen", "admin@northstar.test", "ADMIN");
        seedUser(org, "Aarav Mehta", "sales@northstar.test", "SALES");
        seedUser(org, "Leah Brooks", "warehouse@northstar.test", "WAREHOUSE");
        seedUser(org, "Noah Williams", "procurement@northstar.test", "PROCUREMENT");
        seedUser(org, "Isabella Patel", "finance@northstar.test", "FINANCE");

        List<ProductSeed> products = List.of(
                new ProductSeed("ST-2048", "Thermal Label Roll 4×6", "Shipping", "case", "48.00", "29.00", 235, 40),
                new ProductSeed("PK-1002", "Kraft Mailer 10×13", "Packaging", "bundle", "32.50", "18.75", 84, 30),
                new ProductSeed("BX-3001", "Corrugated Box 12×10×8", "Packaging", "pack", "24.00", "13.20", 52, 24),
                new ProductSeed("TP-0074", "Clear Packing Tape 48mm", "Packaging", "case", "42.00", "25.00", 19, 24),
                new ProductSeed("SF-8110", "Safety Cutter Pro", "Warehouse", "unit", "16.00", "8.50", 68, 18),
                new ProductSeed("GL-2214", "Nitrile Gloves Large", "Safety", "box", "12.50", "6.90", 14, 20),
                new ProductSeed("PL-9050", "Pallet Wrap 500mm", "Warehouse", "roll", "18.75", "10.40", 45, 16),
                new ProductSeed("MK-0250", "Permanent Marker Black", "Office", "dozen", "9.00", "4.25", 112, 24)
        );
        for (ProductSeed p : products) jdbc.update("""
                INSERT INTO products(organization_id,sku,name,category,unit,unit_price,unit_cost,stock_on_hand,reorder_point)
                VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (organization_id,sku) DO NOTHING
                """, org, p.sku, p.name, p.category, p.unit, new BigDecimal(p.price), new BigDecimal(p.cost), p.stock, p.reorder);

        List<PartySeed> customers = List.of(
                new PartySeed("CUS-1001", "Harbor & Pine Retail", "ops@harborpine.test", "(415) 555-0124", "San Francisco"),
                new PartySeed("CUS-1002", "Meridian Office Group", "buying@meridian.test", "(503) 555-0180", "Portland"),
                new PartySeed("CUS-1003", "Juniper Home Goods", "orders@juniper.test", "(206) 555-0177", "Seattle"),
                new PartySeed("CUS-1004", "Cobalt Commerce", "supply@cobalt.test", "(408) 555-0141", "San Jose"),
                new PartySeed("CUS-1005", "Redwood Fulfillment", "receiving@redwood.test", "(916) 555-0162", "Sacramento"),
                new PartySeed("CUS-1006", "North Bay Market", "purchasing@northbay.test", "(707) 555-0130", "Santa Rosa")
        );
        for (PartySeed p : customers) jdbc.update("""
                INSERT INTO customers(organization_id,customer_code,name,email,phone,city,credit_limit)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT (organization_id,customer_code) DO NOTHING
                """, org, p.code, p.name, p.email, p.phone, p.city, new BigDecimal("25000"));

        List<PartySeed> suppliers = List.of(
                new PartySeed("SUP-2001", "Cascade Packaging Works", "sales@cascade.test", "(360) 555-0103", "Vancouver"),
                new PartySeed("SUP-2002", "Pacific Label & Paper", "orders@pacificpaper.test", "(503) 555-0198", "Portland"),
                new PartySeed("SUP-2003", "Evergreen Safety Supply", "hello@evergreen.test", "(425) 555-0119", "Bellevue"),
                new PartySeed("SUP-2004", "Westline Warehouse Goods", "service@westline.test", "(510) 555-0155", "Oakland")
        );
        for (PartySeed p : suppliers) jdbc.update("""
                INSERT INTO suppliers(organization_id,supplier_code,name,email,phone,city,lead_time_days)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT (organization_id,supplier_code) DO NOTHING
                """, org, p.code, p.name, p.email, p.phone, p.city, 7);
        seedOpeningBalances(org, jdbc.queryForObject("SELECT id FROM app_users WHERE organization_id=? AND email='admin@northstar.test'", Long.class, org));
        seedHistory(org);
    }

    private void seedUser(long org, String name, String email, String role) {
        jdbc.update("""
                INSERT INTO app_users(organization_id,full_name,email,password_hash,role)
                VALUES (?,?,?,?,?) ON CONFLICT (organization_id,email) DO NOTHING
                """, org, name, email, passwords.encode("ChangeMe!2026"), role);
    }

    private void seedHistory(long org) {
        long adminId = jdbc.queryForObject("SELECT id FROM app_users WHERE organization_id=? AND email='admin@northstar.test'", Long.class, org);
        long customer = jdbc.queryForObject("SELECT id FROM customers WHERE organization_id=? AND customer_code='CUS-1001'", Long.class, org);
        long product = jdbc.queryForObject("SELECT id FROM products WHERE organization_id=? AND sku='ST-2048'", Long.class, org);
        String[] numbers = {"SO-DEMO-001", "SO-DEMO-002", "SO-DEMO-003", "SO-DEMO-004"};
        String[] invoices = {"INV-DEMO-001", "INV-DEMO-002", "INV-DEMO-003", "INV-DEMO-004"};
        int[] qty = {38, 24, 16, 31};
        String[] totals = {"1824.00", "1152.00", "768.00", "1488.00"};
        String[] offsets = {"-22 days", "-14 days", "-6 days", "-2 days"};
        for (int i = 0; i < numbers.length; i++) {
            jdbc.update("""
                    INSERT INTO sales_orders(organization_id,order_number,customer_id,status,subtotal,total,created_by,created_at)
                    VALUES (?,?,?,'FULFILLED',?,?,?,now() + (?::interval)) ON CONFLICT (organization_id,order_number) DO NOTHING
                    """, org, numbers[i], customer, new BigDecimal(totals[i]), new BigDecimal(totals[i]), adminId, offsets[i]);
            Long orderId = jdbc.queryForObject("SELECT id FROM sales_orders WHERE organization_id=? AND order_number=?", Long.class, org, numbers[i]);
            if (jdbc.queryForObject("SELECT count(*) FROM sales_order_items WHERE sales_order_id=?", Integer.class, orderId) == 0) {
                jdbc.update("INSERT INTO sales_order_items(sales_order_id,product_id,quantity,unit_price,line_total) VALUES (?,?,?,?,?)",
                        orderId, product, qty[i], new BigDecimal("48.00"), new BigDecimal(totals[i]));
                jdbc.update("UPDATE products SET stock_on_hand=stock_on_hand-? WHERE id=?", qty[i], product);
                jdbc.update("""
                        INSERT INTO inventory_movements(organization_id,product_id,movement_type,quantity_delta,reference_type,reference_id,reason,actor_id,created_at)
                        VALUES (?,?,'SALE',?,'SALES_ORDER',?,'Seeded fulfilled order',?,now() + (?::interval))
                        """, org, product, -qty[i], orderId, adminId, offsets[i]);
                jdbc.update("""
                        INSERT INTO invoices(organization_id,invoice_number,sales_order_id,customer_id,status,subtotal,total,paid_amount,due_date,created_at)
                        VALUES (?,?,?,?,?,?,?,?,CURRENT_DATE + 30,now() + (?::interval)) ON CONFLICT (organization_id,invoice_number) DO NOTHING
                        """, org, invoices[i], orderId, customer, i == 2 ? "UNPAID" : "PAID", new BigDecimal(totals[i]), new BigDecimal(totals[i]),
                        i == 2 ? BigDecimal.ZERO : new BigDecimal(totals[i]), offsets[i]);
            }
        }
    }

    private void seedOpeningBalances(long org, long adminId) {
        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,stock_on_hand FROM products WHERE organization_id=? AND active", org);
        for (var row : rows) {
            long productId = ((Number) row.get("id")).longValue();
            int stock = ((Number) row.get("stock_on_hand")).intValue();
            if (jdbc.queryForObject("SELECT count(*) FROM inventory_movements WHERE organization_id=? AND product_id=?", Integer.class, org, productId) == 0 && stock > 0) {
                jdbc.update("""
                        INSERT INTO inventory_movements(organization_id,product_id,movement_type,quantity_delta,reference_type,reference_id,reason,actor_id)
                        VALUES (?,?,'OPENING_BALANCE',?,'SEED',NULL,'Seed opening inventory',?)
                        """, org, productId, stock, adminId);
            }
        }
    }

    private record ProductSeed(String sku, String name, String category, String unit, String price, String cost, int stock, int reorder) { }
    private record PartySeed(String code, String name, String email, String phone, String city) { }
}
