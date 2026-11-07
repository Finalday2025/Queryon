-- Queryon 示例数据库初始化 · 第 2 步：创建表、主外键、索引与中文元数据
-- 需连接到 queryon_sample 后执行（见 01_create_database.sql 头部说明）。
-- 表名、字段名与中文业务名严格沿用 05 第 8.1／8.2 节，不另造同义词（Q25、AGENTS 3）。
-- 行列级权限由系统按提问者身份注入（05 第 8.4 节），不在数据库层另建一套 RLS 规则，避免口径或权限分裂。
-- 两类角色样本由应用层 Actor 承载，脚本不创建数据库角色、不写入任何口令。

-- ---------------------------------------------------------------- 地区维表
CREATE TABLE dim_region (
    region_id       INTEGER PRIMARY KEY,
    region_name     VARCHAR(64)  NOT NULL,
    province        VARCHAR(64)  NOT NULL,
    city            VARCHAR(64)  NOT NULL,
    sales_territory VARCHAR(32)  NOT NULL
);

-- ---------------------------------------------------------------- 客户维表
CREATE TABLE dim_customer (
    customer_id     INTEGER PRIMARY KEY,
    customer_name   VARCHAR(64)  NOT NULL,
    region_id       INTEGER      NOT NULL REFERENCES dim_region (region_id),
    customer_level  VARCHAR(32),
    register_date   DATE         NOT NULL,
    phone           VARCHAR(32),
    email           VARCHAR(128)
);

-- ---------------------------------------------------------------- 商品维表
CREATE TABLE dim_product (
    product_id      INTEGER PRIMARY KEY,
    product_name    VARCHAR(64)  NOT NULL,
    category        VARCHAR(32)  NOT NULL,
    brand           VARCHAR(64)  NOT NULL,
    list_price      NUMERIC(12, 2) NOT NULL,
    cost            NUMERIC(12, 2) NOT NULL
);

-- ---------------------------------------------------------------- 订单主表
CREATE TABLE fact_order (
    order_id        VARCHAR(32) PRIMARY KEY,
    customer_id     INTEGER      NOT NULL REFERENCES dim_customer (customer_id),
    region_id       INTEGER      NOT NULL REFERENCES dim_region (region_id),
    order_date      DATE         NOT NULL,
    order_status    VARCHAR(16)  NOT NULL CHECK (order_status IN ('paid', 'refunded', 'cancelled')),
    pay_amount      NUMERIC(12, 2) NOT NULL,
    refund_amount   NUMERIC(12, 2) NOT NULL,
    freight_amount  NUMERIC(12, 2) NOT NULL,
    discount_amount NUMERIC(12, 2)
);

-- ---------------------------------------------------------------- 订单明细
CREATE TABLE fact_order_item (
    item_id         INTEGER PRIMARY KEY,
    order_id        VARCHAR(32)  NOT NULL REFERENCES fact_order (order_id),
    product_id      INTEGER      NOT NULL REFERENCES dim_product (product_id),
    quantity        INTEGER      NOT NULL CHECK (quantity > 0),
    unit_price      NUMERIC(12, 2) NOT NULL,
    item_amount     NUMERIC(12, 2) NOT NULL
);

-- ---------------------------------------------------------------- 索引
CREATE INDEX idx_fact_order_date      ON fact_order (order_date);
CREATE INDEX idx_fact_order_status    ON fact_order (order_status);
CREATE INDEX idx_fact_order_customer  ON fact_order (customer_id);
CREATE INDEX idx_fact_order_region    ON fact_order (region_id);
CREATE INDEX idx_fact_item_order      ON fact_order_item (order_id);
CREATE INDEX idx_fact_item_product    ON fact_order_item (product_id);
CREATE INDEX idx_dim_product_category ON dim_product (category);
CREATE INDEX idx_dim_region_territory ON dim_region (sales_territory);

-- ---------------------------------------------------------------- 中文业务元数据（05 第 8.1／8.2 节）
COMMENT ON TABLE dim_region       IS '地区维表：地区下钻与行级权限切分依据';
COMMENT ON TABLE dim_customer     IS '客户维表：客户主体（全部为假数据）';
COMMENT ON TABLE dim_product      IS '商品维表：商品与品类维表';
COMMENT ON TABLE fact_order       IS '订单主表：金额拆分用于制造口径歧义';
COMMENT ON TABLE fact_order_item  IS '订单明细：支撑品类拆解与销量口径';

COMMENT ON COLUMN dim_region.sales_territory      IS '销售大区：行级权限切分依据';
COMMENT ON COLUMN dim_region.province             IS '省份';
COMMENT ON COLUMN dim_customer.customer_name      IS '客户名称：客户主体名称（假数据）';
COMMENT ON COLUMN dim_customer.customer_level     IS '客户等级：客户分层';
COMMENT ON COLUMN dim_customer.register_date      IS '注册日期';
COMMENT ON COLUMN dim_customer.phone              IS '手机号：敏感列，仅全量角色可见';
COMMENT ON COLUMN dim_customer.email              IS '邮箱：敏感列，仅全量角色可见';
COMMENT ON COLUMN dim_product.category            IS '商品品类：一级类目';
COMMENT ON COLUMN dim_product.brand               IS '品牌';
COMMENT ON COLUMN dim_product.list_price          IS '标价：商品标价';
COMMENT ON COLUMN dim_product.cost                IS '成本价：敏感列，仅全量角色可见';
COMMENT ON COLUMN fact_order.order_date           IS '下单日期：时间口径基准日期，非支付日期';
COMMENT ON COLUMN fact_order.order_status         IS '订单状态：枚举 paid／refunded／cancelled';
COMMENT ON COLUMN fact_order.pay_amount           IS '实付金额：用户实际支付金额，含运费、未扣后续退款';
COMMENT ON COLUMN fact_order.refund_amount        IS '退款金额：已审批退款金额，未退款为 0';
COMMENT ON COLUMN fact_order.freight_amount       IS '运费：单独列示，不计入商品成交额';
COMMENT ON COLUMN fact_order.discount_amount      IS '优惠金额：优惠分摊金额';
COMMENT ON COLUMN fact_order_item.quantity        IS '购买数量：明细行件数';
COMMENT ON COLUMN fact_order_item.unit_price      IS '成交单价：明细行单价';
COMMENT ON COLUMN fact_order_item.item_amount     IS '明细金额：明细行金额';

-- 口径基线（05 第 8.3 节）由语义层认证指标定义承载，不在此处建视图或物化视图，
-- 避免问数／图表／报表／API 另立一套口径。
