CREATE TABLE organizations (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(160) NOT NULL,
  slug VARCHAR(80) NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_users (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL REFERENCES organizations(id),
  full_name VARCHAR(160) NOT NULL,
  email VARCHAR(180) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(24) NOT NULL CHECK (role IN ('ADMIN','SALES','WAREHOUSE','PROCUREMENT','FINANCE')),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (organization_id, email)
);

CREATE TABLE customers (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL REFERENCES organizations(id),
  customer_code VARCHAR(32) NOT NULL,
  name VARCHAR(180) NOT NULL,
  email VARCHAR(180),
  phone VARCHAR(40),
  city VARCHAR(100),
  credit_limit NUMERIC(14,2) NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (organization_id, customer_code)
);

CREATE TABLE suppliers (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL REFERENCES organizations(id),
  supplier_code VARCHAR(32) NOT NULL,
  name VARCHAR(180) NOT NULL,
  email VARCHAR(180),
  phone VARCHAR(40),
  city VARCHAR(100),
  lead_time_days INTEGER NOT NULL DEFAULT 7,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (organization_id, supplier_code)
);

CREATE TABLE products (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL REFERENCES organizations(id),
  sku VARCHAR(48) NOT NULL,
  name VARCHAR(180) NOT NULL,
  category VARCHAR(100) NOT NULL,
  unit VARCHAR(24) NOT NULL DEFAULT 'unit',
  unit_price NUMERIC(14,2) NOT NULL CHECK (unit_price >= 0),
  unit_cost NUMERIC(14,2) NOT NULL CHECK (unit_cost >= 0),
  stock_on_hand INTEGER NOT NULL DEFAULT 0 CHECK (stock_on_hand >= 0),
  reorder_point INTEGER NOT NULL DEFAULT 0 CHECK (reorder_point >= 0),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (organization_id, sku)
);

CREATE TABLE sales_orders (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL REFERENCES organizations(id),
  order_number VARCHAR(32) NOT NULL,
  customer_id BIGINT NOT NULL REFERENCES customers(id),
  status VARCHAR(24) NOT NULL DEFAULT 'CONFIRMED',
  subtotal NUMERIC(14,2) NOT NULL,
  tax_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
  total NUMERIC(14,2) NOT NULL,
  created_by BIGINT REFERENCES app_users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (organization_id, order_number)
);

CREATE TABLE sales_order_items (
  id BIGSERIAL PRIMARY KEY,
  sales_order_id BIGINT NOT NULL REFERENCES sales_orders(id) ON DELETE CASCADE,
  product_id BIGINT NOT NULL REFERENCES products(id),
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  unit_price NUMERIC(14,2) NOT NULL,
  line_total NUMERIC(14,2) NOT NULL
);

CREATE TABLE purchase_orders (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL REFERENCES organizations(id),
  po_number VARCHAR(32) NOT NULL,
  supplier_id BIGINT NOT NULL REFERENCES suppliers(id),
  status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
  total NUMERIC(14,2) NOT NULL,
  expected_date DATE,
  created_by BIGINT REFERENCES app_users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (organization_id, po_number)
);

CREATE TABLE purchase_order_items (
  id BIGSERIAL PRIMARY KEY,
  purchase_order_id BIGINT NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
  product_id BIGINT NOT NULL REFERENCES products(id),
  quantity_ordered INTEGER NOT NULL CHECK (quantity_ordered > 0),
  quantity_received INTEGER NOT NULL DEFAULT 0 CHECK (quantity_received >= 0),
  unit_cost NUMERIC(14,2) NOT NULL,
  line_total NUMERIC(14,2) NOT NULL
);

CREATE TABLE inventory_movements (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL REFERENCES organizations(id),
  product_id BIGINT NOT NULL REFERENCES products(id),
  movement_type VARCHAR(32) NOT NULL,
  quantity_delta INTEGER NOT NULL CHECK (quantity_delta <> 0),
  reference_type VARCHAR(32),
  reference_id BIGINT,
  reason VARCHAR(240),
  actor_id BIGINT REFERENCES app_users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE invoices (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL REFERENCES organizations(id),
  invoice_number VARCHAR(32) NOT NULL,
  sales_order_id BIGINT NOT NULL UNIQUE REFERENCES sales_orders(id),
  customer_id BIGINT NOT NULL REFERENCES customers(id),
  status VARCHAR(24) NOT NULL DEFAULT 'UNPAID',
  subtotal NUMERIC(14,2) NOT NULL,
  tax_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
  total NUMERIC(14,2) NOT NULL,
  paid_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
  due_date DATE NOT NULL DEFAULT (CURRENT_DATE + 30),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (organization_id, invoice_number)
);

CREATE TABLE invoice_payments (
  id BIGSERIAL PRIMARY KEY,
  invoice_id BIGINT NOT NULL REFERENCES invoices(id),
  amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
  reference VARCHAR(100),
  actor_id BIGINT REFERENCES app_users(id),
  paid_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL REFERENCES organizations(id),
  actor_id BIGINT REFERENCES app_users(id),
  action VARCHAR(80) NOT NULL,
  entity_type VARCHAR(60) NOT NULL,
  entity_id VARCHAR(80),
  details JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sync_operations (
  id BIGSERIAL PRIMARY KEY,
  organization_id BIGINT NOT NULL REFERENCES organizations(id),
  operation_key VARCHAR(100) NOT NULL,
  operation_type VARCHAR(48) NOT NULL,
  result JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (organization_id, operation_key)
);

CREATE TABLE event_outbox (
  id BIGSERIAL PRIMARY KEY,
  event_id UUID NOT NULL UNIQUE,
  organization_id BIGINT NOT NULL REFERENCES organizations(id),
  topic VARCHAR(120) NOT NULL,
  event_key VARCHAR(180) NOT NULL,
  payload JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error VARCHAR(800)
);

CREATE INDEX idx_products_org_name ON products(organization_id, name);
CREATE INDEX idx_inventory_movements_org_product_date ON inventory_movements(organization_id, product_id, created_at DESC);
CREATE INDEX idx_sales_orders_org_created ON sales_orders(organization_id, created_at DESC);
CREATE INDEX idx_invoices_org_status_due ON invoices(organization_id, status, due_date);
CREATE INDEX idx_audit_log_org_created ON audit_log(organization_id, created_at DESC);
CREATE INDEX idx_event_outbox_pending ON event_outbox(id) WHERE published_at IS NULL;
