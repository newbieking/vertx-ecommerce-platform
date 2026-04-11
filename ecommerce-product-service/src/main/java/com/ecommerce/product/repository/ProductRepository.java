package com.ecommerce.product.repository;

import com.ecommerce.common.model.Product;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.mysqlclient.MySQLPool;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ProductRepository {

    private static final Logger logger = LoggerFactory.getLogger(ProductRepository.class);
    private static final String CACHE_PREFIX = "product:";
    private static final long CACHE_TTL_SECONDS = 300; // 5分钟缓存

    private final MySQLPool mysql;
    private final RedisAPI redis;
    private boolean connected = false;

    public ProductRepository(Vertx vertx, JsonObject config) {
        // MySQL 配置
        JsonObject dbConfig = config.getJsonObject("database", new JsonObject()).getJsonObject("mysql", new JsonObject());

        MySQLConnectOptions connectOptions = new MySQLConnectOptions()
                .setHost(dbConfig.getString("host", "localhost"))
                .setPort(dbConfig.getInteger("port", 3306))
                .setDatabase(dbConfig.getString("database", "ecommerce_products"))
                .setUser(dbConfig.getString("user", "ecommerce"))
                .setPassword(dbConfig.getString("password", "eco123"));

        PoolOptions poolOptions = new PoolOptions()
                .setMaxSize(10);

        this.mysql = MySQLPool.pool(vertx, connectOptions, poolOptions);

        // Redis 配置
        JsonObject redisConfig = config.getJsonObject("database", new JsonObject()).getJsonObject("redis", new JsonObject());

        RedisOptions redisOptions = new RedisOptions()
                .setConnectionString("redis://" + redisConfig.getString("host", "localhost") + ":" + redisConfig.getInteger("port", 6379));

        Redis redisClient = Redis.createClient(vertx, redisOptions);
        this.redis = RedisAPI.api(redisClient);
    }

    public Future<Void> init() {
        Promise<Void> promise = Promise.promise();

        // 测试连接
        mysql.query("SELECT 1")
                .execute()
                .onSuccess(r -> {
                    connected = true;
                    logger.info("✅ MySQL connected");
                    promise.complete();
                })
                .onFailure(err -> {
                    logger.error("❌ MySQL connection failed", err);
                    promise.fail(err);
                });

        return promise.future();
    }

    public boolean isConnected() {
        return connected;
    }

    // ========== 查询方法（带缓存）==========

    /**
     * 根据 SKU 查询（先查缓存，再查数据库）
     */
    public Future<Product> findBySku(String sku) {
        String cacheKey = CACHE_PREFIX + sku;

        // 1. 先查 Redis
        return redis.get(cacheKey)
                .compose(cached -> {
                    if (cached != null) {
                        logger.debug("Cache hit: {}", sku);
                        return Future.succeededFuture(Product.fromJson(new JsonObject(cached.toString())));
                    }

                    // 2. 缓存未命中，查 MySQL
                    logger.debug("Cache miss, querying DB: {}", sku);
                    return mysql.preparedQuery("SELECT * FROM products WHERE sku = ? AND status = 1")
                            .execute(Tuple.of(sku))
                            .compose(rows -> {
                                if (rows.size() == 0) {
                                    return Future.succeededFuture(null);
                                }

                                Product product = Product.fromRow(rows.iterator().next());

                                // 3. 写入缓存
                                return redis.setex(cacheKey, String.valueOf(CACHE_TTL_SECONDS), product.toJson().encode())
                                        .map(v -> product);
                            });
                });
    }

    /**
     * 分页查询列表（不走缓存，数据变化快）
     */
    public Future<PagedResult<Product>> findAll(int page, int size, String category) {
        int offset = (page - 1) * size;

        StringBuilder sql = new StringBuilder("SELECT * FROM products WHERE status = 1");
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM products WHERE status = 1");
        Tuple params = Tuple.tuple();

        if (category != null && !category.isEmpty()) {
            sql.append(" AND category = ?");
            countSql.append(" AND category = ?");
            params.addString(category);
        }

        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        params.addInteger(size).addInteger(offset);

        // 并行执行查询和计数
        Future<RowSet<Row>> dataQuery = mysql.preparedQuery(sql.toString()).execute(params);
        Future<RowSet<Row>> countQuery = mysql.preparedQuery(countSql.toString())
                .execute(category != null ? Tuple.of(category) : Tuple.tuple());

        return Future.all(dataQuery, countQuery)
                .map(composite -> {
                    List<Product> products = new ArrayList<>();
                    composite.resultAt(0);
                    for (Row row : (RowSet<Row>) composite.resultAt(0)) {
                        products.add(Product.fromRow(row));
                    }

                    long total = ((RowSet<Row>) composite.resultAt(1)).iterator().next().getLong(0);
                    return new PagedResult<>(products, total, page, size);
                });
    }

    /**
     * 按分类查询
     */
    public Future<List<Product>> findByCategory(String category) {
        return mysql.preparedQuery("SELECT * FROM products WHERE category = ? AND status = 1")
                .execute(Tuple.of(category))
                .map(rows -> {
                    List<Product> list = new ArrayList<>();
                    for (Row row : rows) {
                        list.add(Product.fromRow(row));
                    }
                    return list;
                });
    }

    // ========== 修改方法（清缓存）==========

    public Future<Long> save(Product product) {
        return mysql.preparedQuery(
                        "INSERT INTO products (sku, name, description, price, stock, category, status) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .execute(Tuple.of(
                        product.getSku(),
                        product.getName(),
                        product.getDescription(),
                        product.getPrice(),
                        product.getStock(),
                        product.getCategory(),
                        product.getStatus()))
                .map(rows -> rows.property(MySQLClient.LAST_INSERTED_ID));
    }

    public Future<Boolean> update(String sku, JsonObject updates) {
        // 构建动态 SQL
        StringBuilder sql = new StringBuilder("UPDATE products SET ");
        Tuple params = Tuple.tuple();

        if (updates.containsKey("name")) {
            sql.append("name = ?, ");
            params.addString(updates.getString("name"));
        }
        if (updates.containsKey("price")) {
            sql.append("price = ?, ");
            params.addString(updates.getString("price"));
        }
        if (updates.containsKey("stock")) {
            sql.append("stock = ?, ");
            params.addInteger(updates.getInteger("stock"));
        }
        if (updates.containsKey("status")) {
            sql.append("status = ?, ");
            params.addInteger(updates.getInteger("status"));
        }

        // 移除末尾逗号
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE sku = ?");
        params.addString(sku);

        return mysql.preparedQuery(sql.toString())
                .execute(params)
                .compose(result -> {
                    if (result.rowCount() > 0) {
                        // 删除缓存
                        return redis.del(List.of(CACHE_PREFIX + sku)).map(true);
                    }
                    return Future.succeededFuture(false);
                });
    }

    /**
     * 更新库存（供订单服务调用，原子操作）
     */
    public Future<Boolean> updateStock(String sku, int delta) {
        // 乐观锁：确保库存不会负数
        String sql = delta > 0
                ? "UPDATE products SET stock = stock + ? WHERE sku = ?"
                : "UPDATE products SET stock = stock + ? WHERE sku = ? AND stock >= ?";

        Tuple params = delta > 0
                ? Tuple.of(-delta, sku)  // 扣减时 delta 为负数
                : Tuple.of(-delta, sku, -delta); // 扣减需要检查库存充足

        return mysql.preparedQuery(sql).execute(params)
                .compose(result -> {
                    boolean success = result.rowCount() > 0;
                    if (success) {
                        // 删除缓存，下次查询时重建
                        return redis.del(List.of(CACHE_PREFIX + sku)).map(true);
                    }
                    return Future.succeededFuture(false);
                });
    }

    // 分页结果包装类
    public static class PagedResult<T> {
        public final List<T> list;
        public final long total;
        public final int page;
        public final int size;

        public PagedResult(List<T> list, long total, int page, int size) {
            this.list = list;
            this.total = total;
            this.page = page;
            this.size = size;
        }
    }
}