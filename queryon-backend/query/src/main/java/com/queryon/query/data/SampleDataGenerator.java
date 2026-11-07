package com.queryon.query.data;

import com.queryon.semantic.SemanticRegistry;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 内置电商／销售假数据集初始化器（05 第 8.6 节）：5 张表、覆盖连续 12 个自然月并跨年边界，
 * 刻意植入退款单、取消单、零销量品类、空值与重复客户名。固定随机种子，保证评测可重复。
 *
 * <p>运行时由 Java 内存初始化承担；持久化初始化脚本见 {@code bootstrap/src/main/resources/init/*.sql}
 * （经用户明确授权创建，表名／字段／数据规格与此处保持一致，见 {@code 05} 第 8.6 节）。
 */
public final class SampleDataGenerator {

    private static final long SEED = 20261105L;
    private static final int REGION_COUNT = 20;
    private static final int CUSTOMER_COUNT = 60;
    private static final int PRODUCT_COUNT = 80;
    private static final int ORDER_COUNT = 2_000;
    private static final int ITEM_COUNT = 5_000;

    private static final String[] TERRITORIES = {"华东", "华北", "华南", "华中", "西南"};
    private static final String[] LEVELS = {"普通", "银卡", "金卡"};
    private static final String[] CATEGORIES = {"数码", "家居", "服饰", "食品", "图书", "运动"};
    private static final String[] BRANDS = {"示例品牌甲", "示例品牌乙", "示例品牌丙", "示例品牌丁"};
    private static final String ZERO_SALES_CATEGORY = "运动";

    private SampleDataGenerator() {
    }

    public static SampleDataset generate(LocalDate today) {
        Random random = new Random(SEED);
        List<Map<String, Object>> regions = buildRegions();
        List<Map<String, Object>> customers = buildCustomers(random, today);
        List<Map<String, Object>> products = buildProducts(random);
        List<Map<String, Object>> orders = buildOrders(random, today, customers);
        List<Map<String, Object>> items = buildItems(random, orders, products);

        List<Map<String, Object>> ordersWide = new ArrayList<>();
        for (Map<String, Object> order : orders) {
            Map<String, Object> row = new LinkedHashMap<>(order);
            Map<String, Object> region = regions.get(asInt(order.get("region_id")) - 1);
            row.put("sales_territory", region.get("sales_territory"));
            row.put("region_name", region.get("region_name"));
            row.put("province", region.get("province"));
            row.put("city", region.get("city"));
            Map<String, Object> customer = customers.get(asInt(order.get("customer_id")) - 1);
            row.put("customer_name", customer.get("customer_name"));
            row.put("customer_level", customer.get("customer_level"));
            row.put("phone", customer.get("phone"));
            row.put("email", customer.get("email"));
            ordersWide.add(row);
        }

        List<Map<String, Object>> itemsWide = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> row = new LinkedHashMap<>(item);
            Map<String, Object> order = orders.get(asInt(item.get("order_index")));
            row.putAll(order);
            Map<String, Object> region = regions.get(asInt(order.get("region_id")) - 1);
            row.put("sales_territory", region.get("sales_territory"));
            row.put("region_name", region.get("region_name"));
            row.put("province", region.get("province"));
            Map<String, Object> customer = customers.get(asInt(order.get("customer_id")) - 1);
            row.put("customer_name", customer.get("customer_name"));
            row.put("customer_level", customer.get("customer_level"));
            row.put("phone", customer.get("phone"));
            row.put("email", customer.get("email"));
            Map<String, Object> product = products.get(asInt(item.get("product_id")) - 1);
            row.put("product_name", product.get("product_name"));
            row.put("category", product.get("category"));
            row.put("brand", product.get("brand"));
            row.put("list_price", product.get("list_price"));
            row.put("cost", product.get("cost"));
            itemsWide.add(row);
        }

        Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
        tables.put(SemanticRegistry.DIM_REGION, regions);
        tables.put(SemanticRegistry.DIM_CUSTOMER, customers);
        tables.put(SemanticRegistry.DIM_PRODUCT, products);
        tables.put(SemanticRegistry.FACT_ORDER, ordersWide);
        tables.put(SemanticRegistry.FACT_ORDER_ITEM, itemsWide);
        return new SampleDataset(tables);
    }

    private static List<Map<String, Object>> buildRegions() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= REGION_COUNT; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("region_id", i);
            row.put("region_name", "示例地区-" + i);
            row.put("province", "示例省-" + ((i - 1) % TERRITORIES.length + 1));
            row.put("city", "示例市-" + i);
            row.put("sales_territory", TERRITORIES[(i - 1) % TERRITORIES.length]);
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> buildCustomers(Random random, LocalDate today) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= CUSTOMER_COUNT; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("customer_id", i);
            // 刻意制造重复客户名
            row.put("customer_name", "示例客户-" + ((i + 1) / 2));
            row.put("region_id", random.nextInt(REGION_COUNT) + 1);
            // 刻意制造空值
            row.put("customer_level", i % 17 == 0 ? null : LEVELS[i % LEVELS.length]);
            row.put("register_date", today.minusDays(400 + random.nextInt(300)).toString());
            row.put("phone", "138-0000-" + String.format("%04d", i));
            row.put("email", "user" + i + "@example.invalid");
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> buildProducts(Random random) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= PRODUCT_COUNT; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("product_id", i);
            row.put("product_name", "示例商品-" + String.format("%03d", i));
            row.put("category", CATEGORIES[i % CATEGORIES.length]);
            row.put("brand", BRANDS[i % BRANDS.length]);
            double listPrice = 20 + random.nextInt(980) + 0.5;
            row.put("list_price", round(listPrice));
            row.put("cost", round(listPrice * 0.6));
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> buildOrders(Random random, LocalDate today,
                                                         List<Map<String, Object>> customers) {
        LocalDate start = today.withDayOfMonth(1).minusMonths(11);
        int span = (int) ChronoUnit.DAYS.between(start, today.plusDays(1));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= ORDER_COUNT; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("order_index", i - 1);
            row.put("order_id", "ORD-" + String.format("%05d", i));
            Map<String, Object> customer = customers.get(random.nextInt(CUSTOMER_COUNT));
            row.put("customer_id", customer.get("customer_id"));
            row.put("region_id", customer.get("region_id"));
            row.put("order_date", start.plusDays(random.nextInt(span)).toString());
            int statusRoll = random.nextInt(100);
            String status = statusRoll < 80 ? "paid" : (statusRoll < 92 ? "refunded" : "cancelled");
            row.put("order_status", status);
            double payAmount = "cancelled".equals(status) ? 0.0 : 50 + random.nextInt(1950) + 0.25;
            row.put("pay_amount", round(payAmount));
            row.put("refund_amount", "refunded".equals(status) ? round(payAmount * 0.3) : 0.0);
            row.put("freight_amount", round(random.nextInt(20)));
            row.put("discount_amount", i % 23 == 0 ? null : round(random.nextInt(50)));
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> buildItems(Random random, List<Map<String, Object>> orders,
                                                        List<Map<String, Object>> products) {
        List<Map<String, Object>> sellable = products.stream()
                .filter(product -> !ZERO_SALES_CATEGORY.equals(product.get("category")))
                .toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        int itemId = 1;
        // 先为每笔订单各铺一条明细：按商品/品类维度拆解金额时，
        // 订单净营收才有可归属的明细行，各分组之和才会等于订单口径总额。
        // 这也与 03_sample_data.sql 的明细分配保持一致。
        for (Map<String, Object> order : orders) {
            rows.add(buildItemRow(random, itemId++,
                    order, sellable.get(random.nextInt(sellable.size()))));
        }
        while (itemId <= ITEM_COUNT) {
            rows.add(buildItemRow(random, itemId++,
                    orders.get(random.nextInt(orders.size())),
                    sellable.get(random.nextInt(sellable.size()))));
        }
        return rows;
    }

    private static Map<String, Object> buildItemRow(Random random, int itemId,
                                                    Map<String, Object> order,
                                                    Map<String, Object> product) {
        Map<String, Object> row = new LinkedHashMap<>();
        int quantity = 1 + random.nextInt(5);
        double unitPrice = asDouble(product.get("list_price")) * 0.9;
        row.put("item_id", itemId);
        row.put("order_id", order.get("order_id"));
        row.put("order_index", order.get("order_index"));
        row.put("product_id", product.get("product_id"));
        row.put("quantity", quantity);
        row.put("unit_price", round(unitPrice));
        row.put("item_amount", round(unitPrice * quantity));
        return row;
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
