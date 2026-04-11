package com.ecommerce.product;

import com.ecommerce.common.config.ConfigLoader;
import com.ecommerce.common.discovery.ServiceRegistrar;
import com.ecommerce.common.model.ApiResponse;
import com.ecommerce.common.model.Product;
import com.ecommerce.product.repository.ProductRepository;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Launcher;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

public class ProductServiceApplication extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(ProductServiceApplication.class);

    private ServiceRegistrar registrar;
    private ProductRepository productRepo;

    public static void main(String[] args) {
        Launcher.executeCommand("run", ProductServiceApplication.class.getName());
    }

    @Override
    public void start(Promise<Void> startPromise) {
        ConfigLoader.load(vertx)
                .onSuccess(config -> {
                    // 初始化数据库和缓存
                    this.productRepo = new ProductRepository(vertx, config);

                    productRepo.init()
                            .compose(v -> startServer(config))
                            .compose(server -> {
                                // 注册到 Consul
                                this.registrar = new ServiceRegistrar(vertx, config);
                                int port = server.actualPort();
                                String host = config.getJsonObject("service").getString("host", "localhost");

                                JsonObject metadata = new JsonObject()
                                        .put("version", "1.0.0")
                                        .put("type", "product");

                                return registrar.registerHttpService("product-service", host, port, metadata);
                            })
                            .onSuccess(v -> {
                                logger.info("✅ Product Service started successfully");
                                startPromise.complete();
                            })
                            .onFailure(startPromise::fail);
                })
                .onFailure(startPromise::fail);
    }

    private Future<HttpServer> startServer(JsonObject config) {
        int port = config.getJsonObject("http", new JsonObject())
                .getInteger("port", 8082);

        Router router = Router.router(vertx);
        router.route().handler(LoggerHandler.create());
        router.route().handler(BodyHandler.create());

        // 健康检查
        router.get("/health").handler(this::healthCheck);

        // 商品接口（公开查询，修改需认证 - 由网关控制）
        router.get("/api/products").handler(this::listProducts);
        router.get("/api/products/:sku").handler(this::getProduct);
        router.get("/api/products/category/:category").handler(this::getByCategory);

        // 管理接口（实际应由网关鉴权后开放）
        router.post("/api/products").handler(this::createProduct);
        router.put("/api/products/:sku").handler(this::updateProduct);
        router.patch("/api/products/:sku/stock").handler(this::updateStock);

        // 错误处理
        router.errorHandler(500, ctx -> {
            logger.error("Error", ctx.failure());
            ctx.response().setStatusCode(500)
                    .end(ApiResponse.error("Internal error").toJson().encode());
        });

        return vertx.createHttpServer()
                .requestHandler(router)
                .listen(port);
    }

    // ========== API Handlers ==========

    private void healthCheck(RoutingContext ctx) {
        JsonObject health = new JsonObject()
                .put("status", "UP")
                .put("service", "product-service")
                .put("database", productRepo.isConnected() ? "UP" : "DOWN");

        ctx.response()
                .putHeader("content-type", "application/json")
                .end(ApiResponse.success(health).toJson().encode());
    }

    /**
     * 获取商品列表（支持分页）
     * GET /api/products?page=1&size=10&category=手机数码
     */
    private void listProducts(RoutingContext ctx) {
        int page = Integer.parseInt(ctx.request().getParam("page", "1"));
        int size = Integer.parseInt(ctx.request().getParam("size", "10"));
        String category = ctx.request().getParam("category");

        productRepo.findAll(page, size, category)
                .onSuccess(result -> {
                    JsonObject response = new JsonObject()
                            .put("products", result.list.stream()
                                    .map(Product::toJson)
                                    .collect(java.util.stream.Collectors.toList()))
                            .put("total", result.total)
                            .put("page", page)
                            .put("size", size);

                    ctx.response()
                            .putHeader("content-type", "application/json")
                            .end(ApiResponse.success(response).toJson().encode());
                })
                .onFailure(err -> ctx.response().setStatusCode(500)
                        .end(ApiResponse.error(err.getMessage()).toJson().encode()));
    }

    /**
     * 获取单个商品（带缓存）
     * GET /api/products/SKU001
     */
    private void getProduct(RoutingContext ctx) {
        String sku = ctx.pathParam("sku");

        productRepo.findBySku(sku)
                .onSuccess(product -> {
                    if (product == null) {
                        ctx.response().setStatusCode(404)
                                .end(ApiResponse.error("Product not found").toJson().encode());
                        return;
                    }
                    ctx.response()
                            .putHeader("content-type", "application/json")
                            .end(ApiResponse.success(product.toJson()).toJson().encode());
                })
                .onFailure(err -> ctx.response().setStatusCode(500)
                        .end(ApiResponse.error(err.getMessage()).toJson().encode()));
    }

    /**
     * 按分类查询
     */
    private void getByCategory(RoutingContext ctx) {
        String category = ctx.pathParam("category");

        productRepo.findByCategory(category)
                .onSuccess(products -> {
                    JsonArray array = new JsonArray();
                    products.forEach(p -> array.add(p.toJson()));

                    ctx.response()
                            .putHeader("content-type", "application/json")
                            .end(ApiResponse.success(new JsonObject().put("products", array)).toJson().encode());
                })
                .onFailure(err -> ctx.response().setStatusCode(500)
                        .end(ApiResponse.error(err.getMessage()).toJson().encode()));
    }

    /**
     * 创建商品
     */
    private void createProduct(RoutingContext ctx) {
        JsonObject body = ctx.body().asJsonObject();

        Product product = new Product();
        product.setSku(body.getString("sku"));
        product.setName(body.getString("name"));
        product.setDescription(body.getString("description"));
        product.setPrice(new BigDecimal(body.getString("price")));
        product.setStock(body.getInteger("stock", 0));
        product.setCategory(body.getString("category"));
        product.setStatus(body.getInteger("status", 1));

        productRepo.save(product)
                .onSuccess(id -> {
                    product.setId(id);
                    productRepo.findBySku(product.getSku()).onSuccess(it -> {
                        ctx.response().setStatusCode(201)
                                .putHeader("content-type", "application/json")
                                .end(ApiResponse.success(it.toJson()).toJson().encode());
                    });
                })
                .onFailure(err -> ctx.response().setStatusCode(400)
                        .end(ApiResponse.error(err.getMessage()).toJson().encode()));
    }

    /**
     * 更新商品
     */
    private void updateProduct(RoutingContext ctx) {
        String sku = ctx.pathParam("sku");
        JsonObject body = ctx.body().asJsonObject();

        productRepo.update(sku, body)
                .onSuccess(updated -> {
                    if (!updated) {
                        ctx.response().setStatusCode(404)
                                .end(ApiResponse.error("Product not found").toJson().encode());
                        return;
                    }
                    ctx.response()
                            .putHeader("content-type", "application/json")
                            .end(ApiResponse.success("Updated").toJson().encode());
                })
                .onFailure(err -> ctx.response().setStatusCode(500)
                        .end(ApiResponse.error(err.getMessage()).toJson().encode()));
    }

    /**
     * 更新库存（供订单服务调用）
     */
    private void updateStock(RoutingContext ctx) {
        String sku = ctx.pathParam("sku");
        int delta = ctx.body().asJsonObject().getInteger("delta", 0);

        productRepo.updateStock(sku, delta)
                .onSuccess(success -> {
                    if (!success) {
                        ctx.response().setStatusCode(400)
                                .end(ApiResponse.error("Insufficient stock or product not found").toJson().encode());
                        return;
                    }
                    ctx.response()
                            .putHeader("content-type", "application/json")
                            .end(ApiResponse.success("Stock updated").toJson().encode());
                })
                .onFailure(err -> ctx.response().setStatusCode(500)
                        .end(ApiResponse.error(err.getMessage()).toJson().encode()));
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        if (registrar != null) {
            registrar.unregister().onComplete(v -> stopPromise.complete());
        } else {
            stopPromise.complete();
        }
    }
}