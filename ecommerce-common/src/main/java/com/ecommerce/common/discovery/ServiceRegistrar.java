package com.ecommerce.common.discovery;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.servicediscovery.Record;
import io.vertx.servicediscovery.ServiceDiscovery;
import io.vertx.servicediscovery.ServiceDiscoveryOptions;
import io.vertx.servicediscovery.types.HttpEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServiceRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(ServiceRegistrar.class);

    private final Vertx vertx;
    private final ServiceDiscovery discovery;
    private Record currentRecord;

    public ServiceRegistrar(Vertx vertx, JsonObject config) {
        this.vertx = vertx;

        // Consul 后端配置
        ServiceDiscoveryOptions options = new ServiceDiscoveryOptions()
                .setBackendConfiguration(new JsonObject()
                        .put("backend", "consul")
                        .put("serviceName", config.getJsonObject("service").getString("name"))
                        .put("host", config.getJsonObject("consul").getString("host", "localhost"))
                        .put("port", config.getJsonObject("consul").getInteger("port", 8500))
                        .put("scanInterval", 2000)); // 健康检查间隔

        this.discovery = ServiceDiscovery.create(vertx, options);
    }

    /**
     * 注册 HTTP 服务
     */
    public Future<Void> registerHttpService(String name, String host, int port, JsonObject metadata) {
        // 构建服务记录
        Record record = HttpEndpoint.createRecord(
                name,           // 服务名：user-service
                host,           // 主机
                port,           // 端口
                "/",            // 根路径
                metadata        // 元数据：版本、区域等
        );

        // 添加健康检查（Consul 会定期调用）
        record.setMetadata(metadata.put("health-check", "/health"));

        return discovery.publish(record)
                .onSuccess(r -> {
                    this.currentRecord = r;
                    logger.info("✅ Service registered: {} at {}:{}, registration: {}",
                            name, host, port, r.getRegistration());
                })
                .onFailure(err -> {
                    logger.error("❌ Failed to register service: {}", name, err);
                })
                .mapEmpty();
    }

    /**
     * 注销服务（优雅停机）
     */
    public Future<Void> unregister() {
        if (currentRecord == null) {
            return Future.succeededFuture();
        }

        return discovery.unpublish(currentRecord.getRegistration())
                .onSuccess(v -> logger.info("✅ Service unregistered: {}", currentRecord.getName()))
                .onFailure(err -> logger.error("❌ Failed to unregister service", err)).eventually(v -> {
                    discovery.close();
                    return Future.succeededFuture();
                });
    }

    public ServiceDiscovery getDiscovery() {
        return discovery;
    }
}