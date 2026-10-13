const API = "/api";
const DEMO_MODE = location.protocol === "file:" || new URLSearchParams(location.search).get("demo") === "1";
const DEMO_STORAGE_KEY = "stockwise-local-demo-v1";
let demoMemory = null;
const CURRENCY = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD", maximumFractionDigits: 0 });
const MONEY = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" });
const navItems = [
  ["dashboard", "Overview", "⌂"], ["orders", "Sales orders", "▤"], ["inventory", "Inventory", "▦"],
  ["purchasing", "Purchasing", "↗"], ["customers", "Customers", "◉"], ["suppliers", "Suppliers", "▱"],
  ["invoices", "Invoices", "＄"], ["reports", "Reports", "▥"], ["assistant", "AI assistant", "✦"]
];
const state = { token: safeSessionGet("stockwise-token"), user: null, page: "dashboard", search: "", chat: [], stale: false };
const roleLabels = { ADMIN: "Administrator", SALES: "Sales", WAREHOUSE: "Warehouse", PROCUREMENT: "Procurement", FINANCE: "Finance" };

function safeSessionGet(key) { try { return sessionStorage.getItem(key); } catch { return null; } }
function safeSessionSet(key, value) { try { sessionStorage.setItem(key, value); } catch { } }
function safeSessionRemove(key) { try { sessionStorage.removeItem(key); } catch { } }

function tokenClaims() {
  if (!state.token) return null;
  try {
    const raw = state.token.split(".")[0].replaceAll("-", "+").replaceAll("_", "/");
    const bytes = Uint8Array.from(atob(raw + "=".repeat((4 - raw.length % 4) % 4)), c => c.charCodeAt(0));
    const fields = new TextDecoder().decode(bytes).split("\n");
    return { id: Number(fields[0]), organizationId: Number(fields[1]) };
  } catch { return null; }
}
function profileKey(user) { return `org:${user.organizationId}:user:${user.id}`; }
function cacheKey(path) { return `org:${state.user?.organizationId || tokenClaims()?.organizationId || "anonymous"}:${path}`; }

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[char]));
}
function idbOpen() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open("stockwise-erp", 1);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains("cache")) db.createObjectStore("cache");
      if (!db.objectStoreNames.contains("outbox")) db.createObjectStore("outbox", { keyPath: "operationKey" });
      if (!db.objectStoreNames.contains("profile")) db.createObjectStore("profile");
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}
async function dbGet(store, key) {
  const db = await idbOpen();
  return new Promise((resolve, reject) => { const tx = db.transaction(store, "readonly"); const req = tx.objectStore(store).get(key); req.onsuccess = () => resolve(req.result); req.onerror = () => reject(req.error); });
}
async function dbPut(store, value, key) {
  const db = await idbOpen();
  return new Promise((resolve, reject) => { const tx = db.transaction(store, "readwrite"); const req = key === undefined ? tx.objectStore(store).put(value) : tx.objectStore(store).put(value, key); req.onsuccess = () => resolve(); req.onerror = () => reject(req.error); });
}
async function dbAll(store) {
  const db = await idbOpen();
  return new Promise((resolve, reject) => { const tx = db.transaction(store, "readonly"); const req = tx.objectStore(store).getAll(); req.onsuccess = () => resolve(req.result || []); req.onerror = () => reject(req.error); });
}
async function dbDelete(store, key) {
  const db = await idbOpen();
  return new Promise((resolve, reject) => { const tx = db.transaction(store, "readwrite"); const req = tx.objectStore(store).delete(key); req.onsuccess = () => resolve(); req.onerror = () => reject(req.error); });
}

function createDemoSeed() {
  const now = new Date();
  const daysAgo = days => new Date(now.getTime() - days * 86400000).toISOString();
  return {
    nextId: 100,
    customers: [
      { id: 1, customerCode: "CUS-1001", name: "Harbor & Pine Retail", email: "ops@harborpine.test", phone: "(415) 555-0124", city: "San Francisco", creditLimit: 25000, status: "ACTIVE" },
      { id: 2, customerCode: "CUS-1002", name: "Meridian Office Group", email: "buying@meridian.test", phone: "(503) 555-0180", city: "Portland", creditLimit: 25000, status: "ACTIVE" },
      { id: 3, customerCode: "CUS-1003", name: "Juniper Home Goods", email: "orders@juniper.test", phone: "(206) 555-0177", city: "Seattle", creditLimit: 25000, status: "ACTIVE" },
      { id: 4, customerCode: "CUS-1004", name: "Cobalt Commerce", email: "supply@cobalt.test", phone: "(408) 555-0141", city: "San Jose", creditLimit: 25000, status: "ACTIVE" }
    ],
    suppliers: [
      { id: 1, supplierCode: "SUP-2001", name: "Cascade Packaging Works", email: "sales@cascade.test", phone: "(360) 555-0103", city: "Vancouver", leadTimeDays: 7, status: "ACTIVE" },
      { id: 2, supplierCode: "SUP-2002", name: "Pacific Label & Paper", email: "orders@pacificpaper.test", phone: "(503) 555-0198", city: "Portland", leadTimeDays: 7, status: "ACTIVE" },
      { id: 3, supplierCode: "SUP-2003", name: "Evergreen Safety Supply", email: "hello@evergreen.test", phone: "(425) 555-0119", city: "Bellevue", leadTimeDays: 7, status: "ACTIVE" }
    ],
    products: [
      { id: 1, sku: "ST-2048", name: "Thermal Label Roll 4×6", category: "Shipping", unit: "case", unitPrice: 48, unitCost: 29, stockOnHand: 126, reorderPoint: 40, active: true },
      { id: 2, sku: "PK-1002", name: "Kraft Mailer 10×13", category: "Packaging", unit: "bundle", unitPrice: 32.5, unitCost: 18.75, stockOnHand: 84, reorderPoint: 30, active: true },
      { id: 3, sku: "BX-3001", name: "Corrugated Box 12×10×8", category: "Packaging", unit: "pack", unitPrice: 24, unitCost: 13.2, stockOnHand: 52, reorderPoint: 24, active: true },
      { id: 4, sku: "TP-0074", name: "Clear Packing Tape 48mm", category: "Packaging", unit: "case", unitPrice: 42, unitCost: 25, stockOnHand: 19, reorderPoint: 24, active: true },
      { id: 5, sku: "SF-8110", name: "Safety Cutter Pro", category: "Warehouse", unit: "unit", unitPrice: 16, unitCost: 8.5, stockOnHand: 68, reorderPoint: 18, active: true },
      { id: 6, sku: "GL-2214", name: "Nitrile Gloves Large", category: "Safety", unit: "box", unitPrice: 12.5, unitCost: 6.9, stockOnHand: 14, reorderPoint: 20, active: true },
      { id: 7, sku: "PL-9050", name: "Pallet Wrap 500mm", category: "Warehouse", unit: "roll", unitPrice: 18.75, unitCost: 10.4, stockOnHand: 45, reorderPoint: 16, active: true },
      { id: 8, sku: "MK-0250", name: "Permanent Marker Black", category: "Office", unit: "dozen", unitPrice: 9, unitCost: 4.25, stockOnHand: 112, reorderPoint: 24, active: true }
    ],
    orders: [
      { id: 1, orderNumber: "SO-DEMO-001", customerId: 1, customer: "Harbor & Pine Retail", status: "FULFILLED", subtotal: 1824, total: 1824, createdAt: daysAgo(22), createdBy: "Maya Chen", invoiceNumber: "INV-DEMO-001", items: [{ productId: 1, quantity: 38, unitPrice: 48, lineTotal: 1824 }] },
      { id: 2, orderNumber: "SO-DEMO-002", customerId: 2, customer: "Meridian Office Group", status: "FULFILLED", subtotal: 1152, total: 1152, createdAt: daysAgo(14), createdBy: "Aarav Mehta", invoiceNumber: "INV-DEMO-002", items: [{ productId: 1, quantity: 24, unitPrice: 48, lineTotal: 1152 }] },
      { id: 3, orderNumber: "SO-DEMO-003", customerId: 3, customer: "Juniper Home Goods", status: "FULFILLED", subtotal: 768, total: 768, createdAt: daysAgo(6), createdBy: "Aarav Mehta", invoiceNumber: "INV-DEMO-003", items: [{ productId: 1, quantity: 16, unitPrice: 48, lineTotal: 768 }] },
      { id: 4, orderNumber: "SO-DEMO-004", customerId: 1, customer: "Harbor & Pine Retail", status: "FULFILLED", subtotal: 1488, total: 1488, createdAt: daysAgo(2), createdBy: "Maya Chen", invoiceNumber: "INV-DEMO-004", items: [{ productId: 1, quantity: 31, unitPrice: 48, lineTotal: 1488 }] }
    ],
    purchaseOrders: [{ id: 1, poNumber: "PO-DEMO-001", supplierId: 3, supplier: "Evergreen Safety Supply", status: "OPEN", total: 138, expectedDate: new Date(now.getTime()+5*86400000).toISOString().slice(0,10), createdAt: daysAgo(1), unitsOrdered: 20, unitsReceived: 0, items: [{ lineId: 1, productId: 6, sku: "GL-2214", name: "Nitrile Gloves Large", quantityOrdered: 20, quantityReceived: 0, unitCost: 6.9 }] }],
    invoices: [
      { id: 1, invoiceNumber: "INV-DEMO-001", orderNumber: "SO-DEMO-001", customer: "Harbor & Pine Retail", customerId: 1, status: "PAID", total: 1824, paidAmount: 1824, balance: 0, dueDate: new Date(now.getTime()+8*86400000).toISOString().slice(0,10), createdAt: daysAgo(22) },
      { id: 2, invoiceNumber: "INV-DEMO-002", orderNumber: "SO-DEMO-002", customer: "Meridian Office Group", customerId: 2, status: "PAID", total: 1152, paidAmount: 1152, balance: 0, dueDate: new Date(now.getTime()+16*86400000).toISOString().slice(0,10), createdAt: daysAgo(14) },
      { id: 3, invoiceNumber: "INV-DEMO-003", orderNumber: "SO-DEMO-003", customer: "Juniper Home Goods", customerId: 3, status: "UNPAID", total: 768, paidAmount: 0, balance: 768, dueDate: new Date(now.getTime()+24*86400000).toISOString().slice(0,10), createdAt: daysAgo(6) },
      { id: 4, invoiceNumber: "INV-DEMO-004", orderNumber: "SO-DEMO-004", customer: "Harbor & Pine Retail", customerId: 1, status: "UNPAID", total: 1488, paidAmount: 0, balance: 1488, dueDate: new Date(now.getTime()+28*86400000).toISOString().slice(0,10), createdAt: daysAgo(2) }
    ], movements: [], audit: [], syncKeys: []
  };
}
function loadDemoData() {
  try {
    const value = localStorage.getItem(DEMO_STORAGE_KEY);
    if (value) return JSON.parse(value);
  } catch { }
  if (!demoMemory) demoMemory = createDemoSeed();
  return demoMemory;
}
function saveDemoData(data) {
  demoMemory = data;
  try { localStorage.setItem(DEMO_STORAGE_KEY, JSON.stringify(data)); } catch { }
}
function demoUser(email = "admin@northstar.test") {
  const roleByEmail = { "admin@northstar.test": [1,"Maya Chen","ADMIN"], "sales@northstar.test": [2,"Aarav Mehta","SALES"], "warehouse@northstar.test": [3,"Leah Brooks","WAREHOUSE"], "procurement@northstar.test": [4,"Noah Williams","PROCUREMENT"], "finance@northstar.test": [5,"Isabella Patel","FINANCE"] };
  const [id,name,role] = roleByEmail[email.toLowerCase()] || roleByEmail["admin@northstar.test"];
  return { id, organizationId: 1, name, email: email.toLowerCase(), role };
}
function demoNumber(data) { return data.nextId++; }
function demoRecordAudit(data, action, type, id) { data.audit.unshift({ id: demoNumber(data), action, entityType: type, entityId: id, createdAt: new Date().toISOString(), actor: state.user?.name || "Maya Chen" }); }
function demoSuggestions(data) {
  return data.products.filter(p=>p.active && p.stockOnHand<=p.reorderPoint*2).map(p=>{
    const sold=data.orders.filter(o=>new Date(o.createdAt)>=new Date(Date.now()-30*86400000)).flatMap(o=>o.items||[]).filter(i=>i.productId===p.id).reduce((sum,i)=>sum+i.quantity,0);
    return { id:p.id,sku:p.sku,name:p.name,category:p.category,stockOnHand:p.stockOnHand,reorderPoint:p.reorderPoint,unitsSold30d:sold,suggestedOrder:Math.max(p.reorderPoint*2,Math.ceil(sold/30*21)+p.reorderPoint-p.stockOnHand,0),reason:p.stockOnHand===0?"Stockout risk":p.stockOnHand<=p.reorderPoint?"Below reorder point":"Demand forecast" };
  }).sort((a,b)=>a.stockOnHand/Math.max(1,a.reorderPoint)-b.stockOnHand/Math.max(1,b.reorderPoint));
}
function demoDashboard(data) {
  const month=new Date().toISOString().slice(0,7);
  const salesTrend=Array.from({length:6},(_,index)=>{const date=new Date();date.setDate(1);date.setMonth(date.getMonth()-5+index);const key=date.toISOString().slice(0,7);return {label:new Intl.DateTimeFormat("en-US",{month:"short"}).format(date),total:data.orders.filter(o=>o.createdAt.slice(0,7)===key).reduce((sum,o)=>sum+o.total,0)};});
  return { metrics:{ products:data.products.filter(p=>p.active).length,customers:data.customers.filter(c=>c.status==="ACTIVE").length,orders_this_month:data.orders.filter(o=>o.createdAt.slice(0,7)===month).length,revenue_this_month:data.invoices.filter(i=>i.createdAt.slice(0,7)===month).reduce((sum,i)=>sum+i.total,0),low_stock:data.products.filter(p=>p.active&&p.stockOnHand<=p.reorderPoint).length,inventory_value:data.products.reduce((sum,p)=>sum+p.stockOnHand*p.unitCost,0) }, recentOrders:[...data.orders].sort((a,b)=>b.createdAt.localeCompare(a.createdAt)).slice(0,7),salesTrend,lowStockItems:data.products.filter(p=>p.active&&p.stockOnHand<=p.reorderPoint).sort((a,b)=>a.stockOnHand/a.reorderPoint-b.stockOnHand/b.reorderPoint).slice(0,5),categoryMix:Object.entries(data.products.reduce((acc,p)=>(acc[p.category]=(acc[p.category]||0)+1,acc),{})).map(([label,count])=>({label,count})).slice(0,5) };
}
function demoApi(path, options = {}) {
  const method=(options.method||"GET").toUpperCase(); const url=new URL(path,"http://stockwise.local"); const route=url.pathname.replace(/^\/api/,""); const body=options.body||{}; const data=loadDemoData();
  const save=(result)=>{saveDemoData(data);return structuredClone(result);};
  const list=(items)=>{const q=(url.searchParams.get("q")||"").toLowerCase();return items.filter(item=>!q||Object.values(item).some(value=>String(value??"").toLowerCase().includes(q)));};
  if(route==="/auth/me") return Promise.resolve(demoUser(state.user?.email||"admin@northstar.test"));
  if(route==="/auth/login") {if(body.password!=="ChangeMe!2026") return Promise.reject(new Error("Demo password is ChangeMe!2026"));const user=demoUser(body.email||"");return Promise.resolve({accessToken:"browser-demo-token",tokenType:"Bearer",user});}
  if(method==="GET") {
    if(route==="/health") return Promise.resolve({status:"ok",service:"stockwise-browser-demo"});
    if(route==="/dashboard") return Promise.resolve(demoDashboard(data));
    if(route==="/products") return Promise.resolve(list(data.products.filter(p=>p.active)));
    if(route==="/customers") return Promise.resolve(list(data.customers));
    if(route==="/suppliers") return Promise.resolve(list(data.suppliers));
    if(route==="/orders") return Promise.resolve(list([...data.orders].sort((a,b)=>b.createdAt.localeCompare(a.createdAt))));
    if(route==="/purchase-orders") return Promise.resolve(list([...data.purchaseOrders].sort((a,b)=>b.createdAt.localeCompare(a.createdAt))));
    const poMatch=route.match(/^\/purchase-orders\/(\d+)$/); if(poMatch) {const po=data.purchaseOrders.find(p=>p.id===Number(poMatch[1]));return po?Promise.resolve(structuredClone(po)):Promise.reject(new Error("Purchase order not found"));}
    if(route==="/invoices") return Promise.resolve(list([...data.invoices].sort((a,b)=>a.dueDate.localeCompare(b.dueDate))));
    if(route==="/ai/reorder-suggestions") return Promise.resolve(demoSuggestions(data));
    if(route==="/reports/sales") {const monthly=Array.from({length:12},(_,index)=>{const date=new Date();date.setDate(1);date.setMonth(date.getMonth()-11+index);const key=date.toISOString().slice(0,7);const orders=data.orders.filter(o=>o.createdAt.slice(0,7)===key);return {period:key,label:new Intl.DateTimeFormat("en-US",{month:"short",year:"numeric"}).format(date),total:orders.reduce((sum,o)=>sum+o.total,0),orders:orders.length};});const thisMonth=data.orders.filter(o=>o.createdAt.slice(0,7)===new Date().toISOString().slice(0,7));const customerTotals=data.customers.map(c=>{const orders=data.orders.filter(o=>o.customerId===c.id);return {customer:c.name,orders:orders.length,revenue:orders.reduce((sum,o)=>sum+o.total,0)};}).sort((a,b)=>b.revenue-a.revenue);return Promise.resolve({monthly,topCustomers:customerTotals.slice(0,5),summary:{orders:thisMonth.length,revenue:thisMonth.reduce((sum,o)=>sum+o.total,0),averageOrderValue:thisMonth.length?thisMonth.reduce((sum,o)=>sum+o.total,0)/thisMonth.length:0}});}
    return Promise.reject(new Error(`Unknown demo route: ${route}`));
  }
  if(route==="/sync/push") {const results=[];for(const operation of body.operations||[]){if(data.syncKeys.includes(operation.operationKey)){results.push({operationKey:operation.operationKey,status:"already_applied",result:{}});continue;}const result=demoApplyOperation(data,operation.type,operation.payload);data.syncKeys.push(operation.operationKey);results.push({operationKey:operation.operationKey,status:"applied",result});}return Promise.resolve(save(results));}
  if(route==="/products") {const item={id:demoNumber(data),sku:body.sku.toUpperCase(),name:body.name,category:body.category,unit:body.unit,unitPrice:Number(body.unitPrice),unitCost:Number(body.unitCost),stockOnHand:Number(body.stockOnHand),reorderPoint:Number(body.reorderPoint),active:true};data.products.unshift(item);if(item.stockOnHand)data.movements.unshift({productId:item.id,quantityDelta:item.stockOnHand,movementType:"OPENING_BALANCE",reason:"Opening balance"});demoRecordAudit(data,"PRODUCT_CREATED","PRODUCT",item.id);return Promise.resolve(save(item));}
  if(route==="/customers"||route==="/suppliers") {const customers=route==="/customers";const collection=customers?data.customers:data.suppliers;const item={id:demoNumber(data),[customers?"customerCode":"supplierCode"]:`${customers?"CUS":"SUP"}-${String(data.nextId).padStart(4,"0")}`,name:body.name,email:body.email,phone:body.phone,city:body.city,status:"ACTIVE",...(customers?{creditLimit:Number(body.creditLimit||0)}:{leadTimeDays:Number(body.leadTimeDays||7)})};collection.unshift(item);demoRecordAudit(data,customers?"CUSTOMER_CREATED":"SUPPLIER_CREATED",customers?"CUSTOMER":"SUPPLIER",item.id);return Promise.resolve(save(item));}
  if(route==="/orders") return Promise.resolve(save(demoCreateOrder(data,body)));
  if(route==="/purchase-orders") {const supplier=data.suppliers.find(x=>x.id===Number(body.supplierId));const items=(body.items||[]).map(line=>{const product=data.products.find(p=>p.id===Number(line.productId));return {lineId:demoNumber(data),productId:product.id,sku:product.sku,name:product.name,quantityOrdered:Number(line.quantity),quantityReceived:0,unitCost:product.unitCost};});const po={id:demoNumber(data),poNumber:`PO-${String(data.nextId).slice(-6)}`,supplierId:supplier.id,supplier:supplier.name,status:"OPEN",total:items.reduce((sum,i)=>sum+i.quantityOrdered*i.unitCost,0),expectedDate:body.expectedDate,createdAt:new Date().toISOString(),unitsOrdered:items.reduce((sum,i)=>sum+i.quantityOrdered,0),unitsReceived:0,items};data.purchaseOrders.unshift(po);demoRecordAudit(data,"PURCHASE_ORDER_CREATED","PURCHASE_ORDER",po.id);return Promise.resolve(save(po));}
  const receiveMatch=route.match(/^\/purchase-orders\/(\d+)\/receive$/);if(receiveMatch){const po=data.purchaseOrders.find(p=>p.id===Number(receiveMatch[1]));if(!po)return Promise.reject(new Error("Purchase order not found"));for(const received of body.items||[]){const line=po.items.find(i=>i.lineId===Number(received.lineId));const quantity=Number(received.quantityReceived);if(!line||quantity<1||line.quantityReceived+quantity>line.quantityOrdered)return Promise.reject(new Error("Received quantity exceeds the open quantity on a line"));line.quantityReceived+=quantity;po.unitsReceived+=quantity;const product=data.products.find(p=>p.id===line.productId);product.stockOnHand+=quantity;data.movements.unshift({productId:product.id,quantityDelta:quantity,movementType:"PURCHASE_RECEIPT",reason:`Received against ${po.poNumber}`});}po.status=po.unitsReceived===po.unitsOrdered?"RECEIVED":"PARTIAL";demoRecordAudit(data,"PURCHASE_ORDER_RECEIVED","PURCHASE_ORDER",po.id);return Promise.resolve(save(po));}
  const paymentMatch=route.match(/^\/invoices\/(\d+)\/payments$/);if(paymentMatch){const invoice=data.invoices.find(i=>i.id===Number(paymentMatch[1]));if(!invoice)return Promise.reject(new Error("Invoice not found"));const amount=Number(body.amount);if(amount<=0||amount>invoice.balance)return Promise.reject(new Error("Payment exceeds the invoice balance"));invoice.paidAmount+=amount;invoice.balance=invoice.total-invoice.paidAmount;invoice.status=invoice.balance===0?"PAID":"PARTIAL";demoRecordAudit(data,"INVOICE_PAYMENT_RECORDED","INVOICE",invoice.id);return Promise.resolve(save(invoice));}
  if(route==="/stock/adjustments")return Promise.resolve(save(demoAdjustStock(data,body)));
  if(route==="/ai/assistant"){const suggestions=demoSuggestions(data).slice(0,4);const names=suggestions.map(x=>`${x.name} (${x.suggestedOrder} suggested)`).join(", ");return Promise.resolve({answer:`This browser demo uses local replenishment rules, not a connected AI model. ${names?`Items to review: ${names}.`:"No items are currently near their reorder point."}`,provider:"rules"});}
  return Promise.reject(new Error(`Unknown demo route: ${route}`));
}
function demoCreateOrder(data,body){const customer=data.customers.find(c=>c.id===Number(body.customerId));if(!customer)throw new Error("Customer not found");const items=(body.items||[]).map(line=>{const product=data.products.find(p=>p.id===Number(line.productId));const quantity=Number(line.quantity);if(!product||quantity<1)throw new Error("Choose a product and positive quantity");if(product.stockOnHand<quantity)throw new Error(`Not enough stock for ${product.name}: ${product.stockOnHand} available`);return {productId:product.id,sku:product.sku,name:product.name,quantity,unitPrice:product.unitPrice,lineTotal:product.unitPrice*quantity};});const total=items.reduce((sum,i)=>sum+i.lineTotal,0);const id=demoNumber(data);const orderNumber=`SO-${String(data.nextId).slice(-6)}`;const invoiceNumber=`INV-${String(data.nextId+1).slice(-6)}`;const order={id,orderNumber,customerId:customer.id,customer:customer.name,status:"CONFIRMED",subtotal:total,total,createdAt:new Date().toISOString(),createdBy:state.user?.name||"Maya Chen",invoiceNumber,items};for(const item of items){data.products.find(p=>p.id===item.productId).stockOnHand-=item.quantity;data.movements.unshift({productId:item.productId,quantityDelta:-item.quantity,movementType:"SALE",reason:`Allocated to ${orderNumber}`});}data.orders.unshift(order);data.invoices.unshift({id:demoNumber(data),invoiceNumber,orderNumber,customer:customer.name,customerId:customer.id,status:"UNPAID",total,paidAmount:0,balance:total,dueDate:new Date(Date.now()+30*86400000).toISOString().slice(0,10),createdAt:order.createdAt});demoRecordAudit(data,"SALES_ORDER_CREATED","SALES_ORDER",id);return order;}
function demoAdjustStock(data,body){const product=data.products.find(p=>p.id===Number(body.productId));if(!product)throw new Error("Product not found");const delta=Number(body.quantityDelta);if(!delta||product.stockOnHand+delta<0)throw new Error("Adjustment must be non-zero and cannot make stock negative");product.stockOnHand+=delta;data.movements.unshift({productId:product.id,quantityDelta:delta,movementType:"COUNT_ADJUSTMENT",reason:body.reason});demoRecordAudit(data,"STOCK_ADJUSTED","PRODUCT",product.id);return product;}
function demoApplyOperation(data,type,payload){if(type==="CREATE_SALES_ORDER")return demoCreateOrder(data,payload);if(type==="ADJUST_STOCK")return demoAdjustStock(data,payload);throw new Error(`Unsupported offline action: ${type}`);}

async function api(path, options = {}) {
  if (DEMO_MODE) return demoApi(path, options);
  const method = options.method || "GET";
  const headers = { "Content-Type": "application/json", ...(state.token ? { Authorization: `Bearer ${state.token}` } : {}), ...(options.headers || {}) };
  try {
    const response = await fetch(API + path, { ...options, headers, body: options.body && typeof options.body !== "string" ? JSON.stringify(options.body) : options.body });
    if (response.status === 401 && path !== "/auth/login") { logout(false); throw new Error("Your session expired. Sign in again."); }
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.detail || body.message || `Request failed (${response.status})`);
    state.stale = false;
    if (method === "GET") await dbPut("cache", body, cacheKey(path));
    return body;
  } catch (error) {
    if (method === "GET") {
      const cached = await dbGet("cache", cacheKey(path)).catch(() => undefined);
      if (cached !== undefined) { state.stale = true; return cached; }
    }
    throw error;
  }
}

function initials(name) { return (name || "S").split(/\s+/).slice(0, 2).map(part => part[0]).join("").toUpperCase(); }
function dateLabel(value) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.valueOf()) ? String(value) : new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", year: "numeric" }).format(date);
}
function money(value, compact = false) { const n = Number(value || 0); return compact ? CURRENCY.format(n) : MONEY.format(n); }
function number(value) { return new Intl.NumberFormat("en-US").format(Number(value || 0)); }
function statusBadge(status) {
  const s = String(status || "").toUpperCase();
  let kind = "gray";
  if (["PAID", "RECEIVED", "FULFILLED", "ACTIVE"].includes(s)) kind = "green";
  else if (["UNPAID", "OPEN", "PARTIAL", "CONFIRMED", "PROCESSING"].includes(s)) kind = "orange";
  else if (["OVERDUE", "CANCELLED", "VOID"].includes(s)) kind = "red";
  return `<span class="badge badge-${kind}">${escapeHtml(s.replaceAll("_", " ").toLowerCase().replace(/\b\w/g, c => c.toUpperCase()))}</span>`;
}
function showToast(message, kind = "") {
  const region = document.getElementById("toast-region");
  const toast = document.createElement("div"); toast.className = `toast ${kind}`; toast.textContent = message;
  region.append(toast); setTimeout(() => toast.remove(), 3800);
}
function pageName(key) { return navItems.find(item => item[0] === key)?.[1] || "Overview"; }
function pageIntro(key) {
  return ({
    dashboard: "Your operations at a glance. Here’s what needs attention today.", orders: "Track customer orders from confirmation through fulfillment.",
    inventory: "Keep product availability, valuation, and stock counts in sync.", purchasing: "Create purchase orders and receive goods into inventory.",
    customers: "Manage accounts and the relationships behind every order.", suppliers: "Keep vendor contacts and purchasing partners organized.",
    invoices: "Review customer balances and record incoming payments.", reports: "See sales performance and the accounts driving revenue.",
    assistant: "Ask a question about operations or review replenishment suggestions."
  })[key] || "";
}

function renderLogin() {
  document.getElementById("app").innerHTML = `
    <div class="login-screen">
      <section class="login-panel">
        <div class="brand"><span class="brand-mark">S</span><div class="brand-copy"><div class="brand-name">stockwise</div><div class="brand-caption">Operations, in sync</div></div></div>
        <h1>Welcome back</h1><p>Sign in to your Northstar Supply workspace.</p>
        <form id="login-form" class="login-fields">
          <div class="field"><label for="login-email">Work email</label><input id="login-email" name="email" type="email" required autocomplete="username" value="admin@northstar.test"></div>
          <div class="field"><label for="login-password">Password</label><input id="login-password" name="password" type="password" required autocomplete="current-password" value="ChangeMe!2026"></div>
          <button class="btn btn-primary login-submit" type="submit">Sign in <span>→</span></button>
        </form>
        <div class="login-hint"><b>Demo workspace</b><br>Use the prefilled admin account to explore the seeded client data. Local demo credentials only.</div>
      </section>
      <aside class="login-art"><div class="art-copy"><span class="art-kicker">Northstar Supply Co.</span><h2>Every moving part,<br>on the same page.</h2><p>Orders, inventory, suppliers, and cash flow connected in one calm workspace built for the pace of distribution.</p></div></aside>
    </div>`;
  document.getElementById("login-form").addEventListener("submit", async event => {
    event.preventDefault(); const form = new FormData(event.currentTarget);
    const button = event.currentTarget.querySelector("button"); button.disabled = true; button.textContent = "Signing in…";
    try {
      const response = await api("/auth/login", { method: "POST", body: { email: form.get("email"), password: form.get("password") } });
      state.token = response.accessToken; state.user = response.user; safeSessionSet("stockwise-token", state.token);
      if (!DEMO_MODE) await dbPut("profile", state.user, profileKey(state.user));
      state.page = "dashboard"; await renderShell();
    } catch (error) { showToast(error.message, "error"); button.disabled = false; button.innerHTML = "Sign in <span>→</span>"; }
  });
}

async function logout(showMessage = true) {
  state.token = null; state.user = null; safeSessionRemove("stockwise-token");
  if (showMessage) showToast("You’re signed out.");
  renderLogin();
}

function canWrite(area) {
  const role = state.user?.role;
  const access = { product: ["ADMIN", "WAREHOUSE"], stock: ["ADMIN", "WAREHOUSE"], order: ["ADMIN", "SALES"], customer: ["ADMIN", "SALES"], supplier: ["ADMIN", "PROCUREMENT"], purchase: ["ADMIN", "PROCUREMENT"], receive: ["ADMIN", "PROCUREMENT", "WAREHOUSE"], payment: ["ADMIN", "FINANCE"] };
  return (access[area] || []).includes(role);
}

function renderShell() {
  const nav = navItems.map(([key, label, icon]) => `<button class="nav-item ${state.page === key ? "active" : ""}" data-nav="${key}" title="${label}"><span class="nav-icon">${icon}</span><span>${label}</span>${key === "inventory" ? `<span class="nav-count" id="nav-low-stock"></span>` : ""}</button>`).join("");
  document.getElementById("app").innerHTML = `
    <div class="app-shell">
      <aside class="sidebar">
        <div class="brand"><span class="brand-mark">S</span><div class="brand-copy"><div class="brand-name">stockwise</div><div class="brand-caption">Operations, in sync</div></div></div>
        <div class="org-switch"><span class="org-dot"></span><div><div class="org-name">Northstar Supply Co.</div><div class="org-sub">${DEMO_MODE ? "Browser demo · saved on this device" : "West region · Main warehouse"}</div></div><span style="margin-left:auto;color:#8ba496">⌄</span></div>
        <div class="nav-label">Workspace</div><nav class="nav-list" aria-label="Main navigation">${nav}</nav>
        <div class="sidebar-bottom"><div class="help-card"><strong>Need a hand?</strong><p>Check stock, create an order, or ask Stockwise Assistant.</p></div><div class="profile"><div class="avatar">${escapeHtml(initials(state.user?.name))}</div><div class="profile-copy"><div class="profile-name">${escapeHtml(state.user?.name)}</div><div class="profile-role">${escapeHtml(roleLabels[state.user?.role] || state.user?.role)}</div></div><button class="logout" data-action="logout" title="Sign out" aria-label="Sign out">↪</button></div></div>
      </aside>
      <main class="main"><header class="topbar"><div class="breadcrumb">Northstar Supply <span style="padding:0 7px;color:#c1c9c2">/</span> <strong>${escapeHtml(pageName(state.page))}</strong></div><div class="top-actions"><span class="today">${new Intl.DateTimeFormat("en-US", { weekday: "short", month: "short", day: "numeric" }).format(new Date())}</span><div class="connection ${DEMO_MODE || navigator.onLine ? "" : "offline"}" id="connection"><span class="connection-dot"></span><span>${DEMO_MODE ? "Local demo" : navigator.onLine ? "Connected" : "Offline mode"}</span></div></div></header>
        <div class="page" id="page-content"><div class="boot-screen">Loading workspace…</div></div>
      </main>
    </div>`;
  document.querySelectorAll("[data-nav]").forEach(button => button.addEventListener("click", () => { state.page = button.dataset.nav; state.search = ""; renderShell(); }));
  document.querySelector("[data-action='logout']").addEventListener("click", () => logout());
  window.addEventListener("online", () => { setConnection(); if (!DEMO_MODE) syncOutbox(); });
  window.addEventListener("offline", setConnection);
  renderPage();
}

function setConnection() {
  const el = document.getElementById("connection"); if (!el) return;
  el.classList.toggle("offline", !DEMO_MODE && !navigator.onLine); el.querySelector("span:last-child").textContent = DEMO_MODE ? "Local demo" : navigator.onLine ? "Connected" : "Offline mode";
}
function heading(title, description, action = "") {
  return `<div class="page-heading"><div><div class="eyebrow">NORTHSTAR SUPPLY · OPERATIONS</div><h1>${title}</h1><p>${description}</p></div>${action ? `<div class="heading-actions">${action}</div>` : ""}</div>`;
}
function staleBanner() { return state.stale ? `<div class="status-banner">You’re viewing the latest data saved on this device. Changes will sync when you’re back online.</div>` : ""; }
function actionButton(action, label, icon = "＋") { return `<button class="btn btn-primary" data-open-form="${action}"><span>${icon}</span>${label}</button>`; }
function headerActions(page) {
  if (page === "orders" && canWrite("order")) return actionButton("order", "New order");
  if (page === "inventory" && canWrite("product")) return actionButton("product", "Add product");
  if (page === "purchasing" && canWrite("purchase")) return actionButton("purchase", "New purchase order");
  if (page === "customers" && canWrite("customer")) return actionButton("customer", "Add customer");
  if (page === "suppliers" && canWrite("supplier")) return actionButton("supplier", "Add supplier");
  return "";
}

async function renderPage() {
  const host = document.getElementById("page-content"); if (!host) return;
  try {
    if (state.page === "dashboard") host.innerHTML = await renderDashboard();
    else if (state.page === "assistant") host.innerHTML = await renderAssistant();
    else if (state.page === "reports") host.innerHTML = await renderReports();
    else host.innerHTML = await renderModule(state.page);
    wirePage();
    const lowCount = document.getElementById("nav-low-stock");
    if (lowCount) {
      const data = await api("/dashboard"); const count = Number(data.metrics.low_stock || 0);
      lowCount.textContent = count ? count : "";
    }
  } catch (error) {
    host.innerHTML = `<div class="page-heading"><div><div class="eyebrow">WORKSPACE</div><h1>${escapeHtml(pageName(state.page))}</h1><p>We couldn’t load this view.</p></div></div><div class="panel"><div class="empty-state">${escapeHtml(error.message)}<br><br><button class="btn" data-action="retry">Try again</button></div></div>`;
    wirePage();
  }
}

async function renderDashboard() {
  const data = await api("/dashboard"); const m = data.metrics || {};
  const cards = [
    ["Revenue this month", money(m.revenue_this_month, true), "＄", "From issued invoices"],
    ["Orders this month", number(m.orders_this_month), "▤", "Customer orders created"],
    ["Active customers", number(m.customers), "◉", "Accounts in your workspace"],
    ["Active products", number(m.products), "▦", `${money(m.inventory_value, true)} inventory value`],
    ["Need replenishment", number(m.low_stock), "↗", "At or below reorder point"]
  ].map(([label, value, icon, foot]) => `<article class="metric-card"><div class="metric-top"><span>${label}</span><span class="metric-icon">${icon}</span></div><div class="metric-value">${value}</div><div class="metric-foot">${foot}</div></article>`).join("");
  const trend = data.salesTrend || []; const max = Math.max(1, ...trend.map(item => Number(item.total || 0)));
  const chart = trend.map((item, index) => `<div class="chart-col"><span class="chart-value">${money(item.total, true)}</span><div class="chart-bar ${index === trend.length - 1 ? "current" : ""}" style="height:${Math.max(4, Number(item.total || 0) / max * 140)}px"></div><span class="chart-label">${escapeHtml(item.label)}</span></div>`).join("");
  const low = (data.lowStockItems || []).map(item => `<div class="low-row"><div><div class="low-name">${escapeHtml(item.name)}</div><div class="low-sku">${escapeHtml(item.sku)} · ${number(item.stockOnHand)} on hand · min ${number(item.reorderPoint)}</div><div class="stock-progress"><span style="width:${Math.min(100, Number(item.stockOnHand) / Math.max(1, Number(item.reorderPoint)) * 100)}%"></span></div></div><div class="low-count">${number(item.stockOnHand)}<small>units</small></div></div>`).join("") || `<div class="empty-state">Stock is in good shape. No products are at their reorder point.</div>`;
  const recent = data.recentOrders || [];
  const firstName = escapeHtml((state.user?.name || "").split(" ")[0]);
  const actions = `${canWrite("order") ? actionButton("order", "Create order") : ""}${canWrite("purchase") ? `<button class="btn" data-open-form="purchase">＋ Purchase order</button>` : ""}`;
  return `${heading(`Good morning, ${firstName}.`, pageIntro("dashboard"), actions)}${staleBanner()}
    <section class="grid metric-grid">${cards}</section>
    <section class="grid dashboard-grid">
      <article class="panel"><div class="panel-heading"><div><h2>Sales performance</h2><p>Sales order value · last six months</p></div><span class="period">Last 6 months⌄</span></div><div class="chart">${chart}</div><div class="chart-axis"><span>Net sales in USD</span><span>Source: confirmed orders</span></div></article>
      <article class="panel"><div class="panel-heading"><div><h2>Stock to watch</h2><p>${number(m.low_stock)} products at or below their reorder point</p></div><button class="link-button" data-nav-inline="inventory">View inventory →</button></div><div class="stack">${low}</div></article>
    </section>
    <section class="panel table-panel"><div class="table-toolbar"><div><h2>Recent orders</h2><p>Latest activity across your customer accounts</p></div><button class="link-button" style="margin-left:auto" data-nav-inline="orders">View all →</button></div>${simpleTable(recent,[['orderNumber','Order'],['customer','Customer'],['status','Status'],['total','Total'],['createdAt','Date']],true)}</section>`;
}

function simpleTable(rows, columns, compact = false) {
  if (!rows.length) return `<div class="empty-state">Nothing to show yet. Your activity will appear here.</div>`;
  return `<div class="table-wrap"><table><thead><tr>${columns.map(([, label]) => `<th>${label}</th>`).join("")}</tr></thead><tbody>${rows.map(row => `<tr>${columns.map(([key]) => `<td>${cellValue(key, row[key], row)}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`;
}
function cellValue(key, value, row = {}) {
  if (key === "status") return statusBadge(value);
  if (["total", "unitPrice", "unitCost", "creditLimit", "balance", "paidAmount"].includes(key)) return `<span class="amount">${money(value)}</span>`;
  if (["createdAt", "dueDate", "expectedDate"].includes(key)) return dateLabel(value);
  if (key === "stockOnHand") return `<span class="amount">${number(value)}</span>`;
  if (key === "name" && row.sku) return `<div>${escapeHtml(value)}</div><div class="table-sub">${escapeHtml(row.sku)} · ${escapeHtml(row.category || "")}</div>`;
  if (key === "customer" || key === "supplier") return escapeHtml(value);
  return escapeHtml(value ?? "—");
}

const moduleConfigs = {
  orders: { title: "Sales orders", path: "/orders", desc: "Customer orders and fulfillment status.", columns: [["orderNumber","Order"],["customer","Customer"],["status","Status"],["total","Total"],["invoiceNumber","Invoice"],["createdAt","Created"]] },
  inventory: { title: "Inventory", path: "/products", desc: "Product availability and reorder thresholds.", columns: [["name","Product"],["category","Category"],["stockOnHand","On hand"],["reorderPoint","Reorder at"],["unitPrice","Sale price"],["status","Health"]] },
  purchasing: { title: "Purchasing", path: "/purchase-orders", desc: "Open orders, inbound quantities, and supplier spend.", columns: [["poNumber","PO number"],["supplier","Supplier"],["status","Status"],["unitsOrdered","Units ordered"],["unitsReceived","Received"],["total","Total"],["expectedDate","Expected"],["_action","Action"]] },
  customers: { title: "Customers", path: "/customers", desc: "Customer accounts and contact details.", columns: [["customerCode","Account"],["name","Customer"],["email","Email"],["city","City"],["creditLimit","Credit limit"],["status","Status"]] },
  suppliers: { title: "Suppliers", path: "/suppliers", desc: "Supplier contacts, locations, and lead times.", columns: [["supplierCode","Account"],["name","Supplier"],["email","Email"],["city","City"],["leadTimeDays","Lead time"],["status","Status"]] },
  invoices: { title: "Invoices", path: "/invoices", desc: "Open balances and recorded customer payments.", columns: [["invoiceNumber","Invoice"],["orderNumber","Order"],["customer","Customer"],["status","Status"],["total","Total"],["paidAmount","Paid"],["balance","Balance"],["dueDate","Due date"],["_action","Action"]] }
};
async function renderModule(key) {
  const config = moduleConfigs[key]; const qs = state.search ? `?q=${encodeURIComponent(state.search)}` : "";
  const rows = await api(config.path + qs);
  const title = config.title;
  const columns = config.columns;
  const columnHeaders = columns.map(([, label]) => `<th>${label}</th>`).join("");
  let body = rows.map(row => `<tr>${columns.map(([key]) => {
    if (key === "_action") {
      if (state.page === "purchasing" && canWrite("receive") && ["OPEN","PARTIAL"].includes(row.status)) return `<td><button class="table-action" data-receive-po="${row.id}">Receive</button></td>`;
      if (state.page === "invoices" && canWrite("payment") && Number(row.balance) > 0) return `<td><button class="table-action" data-pay-invoice="${row.id}" data-balance="${row.balance}">Record payment</button></td>`;
      return `<td>—</td>`;
    }
    if (key === "status" && state.page === "inventory") {
      const low = Number(row.stockOnHand) <= Number(row.reorderPoint);
      return `<td>${statusBadge(low ? "LOW STOCK" : "IN STOCK")}</td>`;
    }
    if (key === "stockOnHand" && state.page === "inventory") return `<td>${cellValue(key,row[key])}${canWrite("stock") ? ` <button class="table-action" data-adjust-stock="${row.id}" data-product-name="${escapeHtml(row.name)}">Count</button>` : ""}</td>`;
    return `<td>${cellValue(key,row[key],row)}</td>`;
  }).join("")}</tr>`).join("");
  if (!rows.length) body = `<tr><td colspan="${columns.length}" class="empty-state">No records match your search.</td></tr>`;
  return `${heading(title, config.desc, headerActions(key))}${staleBanner()}<section class="panel table-panel"><div class="table-toolbar"><div><h2>${escapeHtml(title)} <span style="font-weight:400;color:#859088">${number(rows.length)}</span></h2><p>${escapeHtml(config.desc)}</p></div><div class="search-wrap"><span class="search-icon">⌕</span><input class="search" type="search" id="module-search" placeholder="Search ${title.toLowerCase()}…" value="${escapeHtml(state.search)}"></div></div><div class="table-wrap"><table><thead><tr>${columnHeaders}</tr></thead><tbody>${body}</tbody></table></div></section>`;
}

async function renderReports() {
  const data = await api("/reports/sales"); const s = data.summary || {};
  const monthly = data.monthly || []; const max = Math.max(1, ...monthly.map(row => Number(row.total || 0)));
  const chart = monthly.slice(-6).map((row, index, list) => `<div class="chart-col"><span class="chart-value">${money(row.total,true)}</span><div class="chart-bar ${index===list.length-1?"current":""}" style="height:${Math.max(3,Number(row.total||0)/max*140)}px"></div><span class="chart-label">${escapeHtml(row.label.split(" ")[0])}</span></div>`).join("");
  const customers = (data.topCustomers || []).map((row,index) => `<div class="report-item"><span class="report-rank">${index+1}</span><div class="report-name">${escapeHtml(row.customer)}<div class="table-sub">${number(row.orders)} orders</div></div><div class="report-meta">${money(row.revenue)}</div></div>`).join("") || `<div class="empty-state">No customer sales yet.</div>`;
  return `${heading("Sales reports",pageIntro("reports"))}${staleBanner()}<div class="grid report-summary"><div class="panel report-card"><span>Orders this month</span><strong>${number(s.orders)}</strong></div><div class="panel report-card"><span>Sales this month</span><strong>${money(s.revenue,true)}</strong></div><div class="panel report-card"><span>Average order value</span><strong>${money(s.averageOrderValue,true)}</strong></div></div><div class="grid dashboard-grid"><section class="panel"><div class="panel-heading"><div><h2>Monthly sales</h2><p>Order value · rolling 12 months</p></div></div><div class="chart">${chart}</div></section><section class="panel"><div class="panel-heading"><div><h2>Top customers</h2><p>By recorded order value</p></div></div><div class="report-list">${customers}</div></section></div>`;
}

async function renderAssistant() {
  const suggestions = await api("/ai/reorder-suggestions");
  const cards = suggestions.slice(0,4).map(item => `<button class="suggestion" data-prompt="Which items should we reorder first?">${escapeHtml(item.name)} · ${number(item.stockOnHand)} on hand, ${number(item.suggestedOrder)} suggested</button>`).join("") || `<div class="empty-state">No products need immediate replenishment.</div>`;
  const messages = state.chat.map(message => `<div class="chat-msg ${message.role}">${escapeHtml(message.text)}</div>`).join("");
  return `${heading("Stockwise Assistant",pageIntro("assistant"))}${staleBanner()}<div class="ai-layout"><section class="ai-hero"><span class="ai-provider">${state.stale ? "Saved snapshot" : "Operations assistant"}</span><h2>Clear answers for the next move.</h2><p>Ask about current operations, or start with the products nearest their reorder point. Recommendations are informational; the assistant does not create or change records.</p></section><section class="panel"><div class="panel-heading"><div><h2>Replenishment watch</h2><p>Based on on-hand stock, reorder points, and recent sales history</p></div><button class="link-button" data-nav-inline="inventory">Inventory →</button></div><div class="suggestions">${cards}</div></section><section class="chat-card"><div class="panel-heading"><div><h2>Ask Stockwise</h2><p>Data-backed questions about sales and inventory</p></div></div><div class="chat-messages" id="chat-messages">${messages || `<div class="chat-msg bot">I can summarize the current dashboard and recommend which items to review. What would you like to know?</div>`}</div><form class="chat-compose" id="assistant-form"><textarea name="question" placeholder="For example: Which items are at risk of stockout?" required></textarea><button class="btn btn-primary" type="submit">Ask <span>↑</span></button></form></section></div>`;
}

function wirePage() {
  document.querySelectorAll("[data-nav-inline]").forEach(button => button.addEventListener("click", () => { state.page=button.dataset.navInline; state.search=""; renderShell(); }));
  document.querySelectorAll("[data-open-form]").forEach(button => button.addEventListener("click", () => openForm(button.dataset.openForm)));
  document.querySelectorAll("[data-adjust-stock]").forEach(button => button.addEventListener("click", () => openForm("stock", { productId: Number(button.dataset.adjustStock), productName: button.dataset.productName })));
  document.querySelectorAll("[data-pay-invoice]").forEach(button => button.addEventListener("click", () => openForm("payment", { id: Number(button.dataset.payInvoice), balance: Number(button.dataset.balance) })));
  document.querySelectorAll("[data-receive-po]").forEach(button => button.addEventListener("click", () => openForm("receive", { id: Number(button.dataset.receivePo) })));
  document.querySelectorAll("[data-prompt]").forEach(button => button.addEventListener("click", () => askAssistant(button.dataset.prompt)));
  const search = document.getElementById("module-search");
  if (search) search.addEventListener("input", debounce(async () => {
    state.search = search.value; await renderPage();
    const current = document.getElementById("module-search");
    if (current) { current.focus(); current.setSelectionRange(state.search.length, state.search.length); }
  }, 250));
  const assistantForm = document.getElementById("assistant-form");
  if (assistantForm) assistantForm.addEventListener("submit", event => { event.preventDefault(); const question = new FormData(assistantForm).get("question"); askAssistant(question); });
  document.querySelectorAll("[data-action='retry']").forEach(button => button.addEventListener("click", renderPage));
}
function debounce(fn, delay) { let timer; return (...args) => { clearTimeout(timer); timer = setTimeout(() => fn(...args), delay); }; }

async function openForm(type, extra = {}) {
  let modal = document.getElementById("modal-root");
  if (!modal) { modal = document.createElement("div"); modal.id = "modal-root"; document.body.append(modal); }
  let content = "";
  try {
    if (["order", "purchase"].includes(type)) {
      const people = await api(type === "order" ? "/customers" : "/suppliers");
      const products = await api("/products");
      const options = type === "order" ? people.map(x=>`<option value="${x.id}">${escapeHtml(x.name)}</option>`).join("") : people.map(x=>`<option value="${x.id}">${escapeHtml(x.name)}</option>`).join("");
      const productOptions = products.map(x=>`<option value="${x.id}">${escapeHtml(x.name)} — ${number(x.stockOnHand)} available</option>`).join("");
      if (!people.length || !products.length) throw new Error(`Add at least one ${type === "order" ? "customer" : "supplier"} and product first.`);
      content = `<div class="field full"><label>${type === "order" ? "Customer" : "Supplier"}</label><select name="partyId" required>${options}</select></div><div class="field full"><label>Product</label><select name="productId" required>${productOptions}</select></div><div class="field"><label>Quantity</label><input name="quantity" type="number" min="1" value="1" required></div>${type === "purchase" ? `<div class="field"><label>Expected delivery</label><input name="expectedDate" type="date" value="${new Date(Date.now()+7*86400000).toISOString().slice(0,10)}" required></div>` : `<div class="field"><label>Order date</label><input value="${new Intl.DateTimeFormat("en-US",{dateStyle:"medium"}).format(new Date())}" disabled></div>`}`;
    } else if (type === "product") {
      content = `<div class="form-grid"><div class="field"><label>SKU</label><input name="sku" required maxlength="48" placeholder="e.g. ST-2048"></div><div class="field"><label>Product name</label><input name="name" required></div><div class="field"><label>Category</label><input name="category" required placeholder="Packaging"></div><div class="field"><label>Unit</label><input name="unit" required value="unit"></div><div class="field"><label>Sale price</label><input name="unitPrice" type="number" min="0" step="0.01" required></div><div class="field"><label>Unit cost</label><input name="unitCost" type="number" min="0" step="0.01" required></div><div class="field"><label>Opening stock</label><input name="stockOnHand" type="number" min="0" value="0" required></div><div class="field"><label>Reorder point</label><input name="reorderPoint" type="number" min="0" value="10" required></div></div>`;
    } else if (["customer", "supplier"].includes(type)) {
      content = `<div class="form-grid"><div class="field full"><label>${type === "customer" ? "Customer" : "Supplier"} name</label><input name="name" required></div><div class="field"><label>Email</label><input name="email" type="email"></div><div class="field"><label>Phone</label><input name="phone"></div><div class="field"><label>City</label><input name="city"></div>${type === "customer" ? `<div class="field"><label>Credit limit</label><input name="creditLimit" type="number" min="0" step="0.01" value="0"></div>` : `<div class="field"><label>Lead time (days)</label><input name="leadTimeDays" type="number" min="1" value="7"></div>`}</div>`;
    } else if (type === "stock") {
      content = `<div class="field"><label>Product</label><input value="${escapeHtml(extra.productName)}" disabled></div><div class="field" style="margin-top:12px"><label>Change in units</label><input name="quantityDelta" type="number" required placeholder="Use a negative number to remove stock"></div><div class="field" style="margin-top:12px"><label>Reason for count adjustment</label><textarea name="reason" required placeholder="Cycle count, damaged goods, correction…"></textarea></div><input type="hidden" name="productId" value="${extra.productId}">`;
    } else if (type === "payment") {
      content = `<div class="field"><label>Remaining balance</label><input value="${money(extra.balance)}" disabled></div><div class="field" style="margin-top:12px"><label>Payment amount</label><input name="amount" type="number" min="0.01" max="${extra.balance}" step="0.01" value="${extra.balance}" required></div><div class="field" style="margin-top:12px"><label>Reference</label><input name="reference" placeholder="Bank transfer, receipt number…"></div>`;
    } else if (type === "receive") {
      const purchase = await api(`/purchase-orders/${extra.id}`);
      content = `<div class="field-note">Enter the quantity received for each open line. Receipt updates available stock and writes an inventory movement for every product.</div><div style="display:grid;gap:12px;margin-top:14px">${(purchase.items||[]).filter(item=>Number(item.quantityReceived)<Number(item.quantityOrdered)).map(item=>`<div class="field"><label>${escapeHtml(item.name)} · ${escapeHtml(item.sku)} (${number(item.quantityOrdered-item.quantityReceived)} remaining)</label><input name="line-${item.lineId}" data-line-id="${item.lineId}" type="number" min="0" max="${item.quantityOrdered-item.quantityReceived}" value="${item.quantityOrdered-item.quantityReceived}"></div>`).join("") || "No open lines."}</div>`;
    }
  } catch (error) { showToast(error.message, "error"); return; }
  const names = { order:"Create sales order", purchase:"New purchase order", product:"Add product", customer:"Add customer", supplier:"Add supplier", stock:"Adjust stock count", payment:"Record payment", receive:"Receive goods" };
  modal.innerHTML = `<div class="modal-backdrop"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title"><header class="modal-header"><div><h2 id="modal-title">${names[type]}</h2><p>Northstar Supply Co. · Changes are recorded in the audit trail.</p></div><button class="modal-close" data-close-modal aria-label="Close">×</button></header><form id="record-form"><div class="modal-body"><div class="form-grid">${content}</div><div id="form-error" class="field-note" style="color:#b9554d;margin-top:10px"></div></div><footer class="modal-footer"><button class="btn" type="button" data-close-modal>Cancel</button><button class="btn btn-primary" type="submit">${type === "receive" ? "Post receipt" : type === "payment" ? "Record payment" : "Save"}</button></footer></form></section></div>`;
  modal.querySelectorAll("[data-close-modal]").forEach(button => button.addEventListener("click", closeModal));
  modal.querySelector(".modal-backdrop").addEventListener("click", event => { if (event.target.classList.contains("modal-backdrop")) closeModal(); });
  modal.querySelector("#record-form").addEventListener("submit", event => submitForm(event, type, extra));
}
function closeModal() { document.getElementById("modal-root")?.remove(); }

async function submitForm(event, type, extra) {
  event.preventDefault(); const form = new FormData(event.currentTarget); const button = event.currentTarget.querySelector("button[type='submit']");
  button.disabled = true; button.textContent = "Saving…";
  let path = "", payload = {}, method = "POST", offlineType = "";
  switch (type) {
    case "order": path = "/orders"; payload = { customerId: Number(form.get("partyId")), items: [{ productId: Number(form.get("productId")), quantity: Number(form.get("quantity")) }] }; offlineType = "CREATE_SALES_ORDER"; break;
    case "purchase": path = "/purchase-orders"; payload = { supplierId: Number(form.get("partyId")), expectedDate: form.get("expectedDate"), items: [{ productId: Number(form.get("productId")), quantity: Number(form.get("quantity")) }] }; break;
    case "product": path = "/products"; payload = { sku: form.get("sku"), name: form.get("name"), category: form.get("category"), unit: form.get("unit"), unitPrice: Number(form.get("unitPrice")), unitCost: Number(form.get("unitCost")), stockOnHand: Number(form.get("stockOnHand")), reorderPoint: Number(form.get("reorderPoint")) }; break;
    case "customer": path = "/customers"; payload = { name: form.get("name"), email: form.get("email") || null, phone: form.get("phone") || null, city: form.get("city") || null, creditLimit: Number(form.get("creditLimit")) }; break;
    case "supplier": path = "/suppliers"; payload = { name: form.get("name"), email: form.get("email") || null, phone: form.get("phone") || null, city: form.get("city") || null, leadTimeDays: Number(form.get("leadTimeDays")) }; break;
    case "stock": path = "/stock/adjustments"; payload = { productId: Number(form.get("productId")), quantityDelta: Number(form.get("quantityDelta")), reason: form.get("reason") }; offlineType = "ADJUST_STOCK"; break;
    case "payment": path = `/invoices/${extra.id}/payments`; payload = { amount: Number(form.get("amount")), reference: form.get("reference") || null }; break;
    case "receive": path = `/purchase-orders/${extra.id}/receive`; payload = { items: [...event.currentTarget.querySelectorAll("[data-line-id]")].filter(input=>Number(input.value)>0).map(input=>({ lineId: Number(input.dataset.lineId), quantityReceived: Number(input.value) })) }; break;
  }
  try {
    if (!navigator.onLine && offlineType && !DEMO_MODE) {
      await enqueueOffline(offlineType, payload); showToast("Saved on this device. It will sync when you’re online.", "offline-toast");
    } else if (!navigator.onLine && !DEMO_MODE) throw new Error("You’re offline. Reconnect before saving this record.");
    else { await api(path, { method, body: payload }); showToast("Saved successfully."); }
    closeModal(); state.search = ""; await renderPage();
  } catch (error) {
    const errorNode = document.getElementById("form-error"); if (errorNode) errorNode.textContent = error.message;
    button.disabled = false; button.textContent = type === "receive" ? "Post receipt" : type === "payment" ? "Record payment" : "Save";
  }
}

async function enqueueOffline(type, payload) {
  const operation = { operationKey: crypto.randomUUID(), organizationId: state.user.organizationId, actorId: state.user.id, type, payload, queuedAt: new Date().toISOString() };
  await dbPut("outbox", operation);
}
async function syncOutbox() {
  if (!navigator.onLine || !state.token) return;
  const operations = (await dbAll("outbox")).filter(item => item.organizationId === state.user?.organizationId && item.actorId === state.user?.id); if (!operations.length) return;
  try {
    const result = await api("/sync/push", { method: "POST", body: { operations: operations.map(({ operationKey, type, payload }) => ({ operationKey, type, payload })) } });
    for (const item of result) if (["applied", "already_applied"].includes(item.status)) await dbDelete("outbox", item.operationKey);
    showToast(`${result.length} offline ${result.length === 1 ? "change" : "changes"} synced.`);
    renderPage();
  } catch (error) { showToast(`Offline sync paused: ${error.message}`, "offline-toast"); }
}

async function askAssistant(question) {
  const text = String(question || "").trim(); if (!text) return;
  state.chat.push({ role: "user", text }); state.chat.push({ role: "bot", text: "Thinking…", pending: true });
  await renderPage();
  try {
    const response = await api("/ai/assistant", { method: "POST", body: { question: text } });
    const last = state.chat[state.chat.length - 1]; last.text = response.answer; delete last.pending;
  } catch (error) { const last = state.chat[state.chat.length - 1]; last.text = error.message; delete last.pending; }
  await renderPage();
  const messages = document.getElementById("chat-messages"); if (messages) messages.scrollTop = messages.scrollHeight;
}

async function boot() {
  if (DEMO_MODE) {
    state.user = demoUser();
    state.token = "browser-demo-token";
    await renderShell();
    return;
  }
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("/service-worker.js").catch(() => {});
  if (state.token) {
    const claims = tokenClaims();
    if (!navigator.onLine && claims) state.user = await dbGet("profile", `org:${claims.organizationId}:user:${claims.id}`).catch(() => null);
    if (!state.user && navigator.onLine) {
      try { state.user = await api("/auth/me"); await dbPut("profile", state.user, profileKey(state.user)); }
      catch (error) { safeSessionRemove("stockwise-token"); state.token = null; }
    }
  }
  if (state.user) { await renderShell(); syncOutbox(); }
  else renderLogin();
}
boot();
