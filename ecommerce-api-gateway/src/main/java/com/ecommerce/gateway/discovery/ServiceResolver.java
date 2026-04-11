package com.ecommerce.gateway.discovery;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.servicediscovery.Record;
import io.vertx.servicediscovery.ServiceDiscovery;
import io.vertx.servicediscovery.ServiceDiscoveryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class ServiceResolver {

    private static final Logger logger = LoggerFactory.getLogger(ServiceResolver.class);

    private final Vertx vertx;
    private final ServiceDiscovery discovery;
    private final Random random = new Random();

    // 本地缓存：服务名 -> 可用实例列表
    private final ConcurrentHashMap<String, List<ServiceInstance>> serviceCache = new ConcurrentHashMap<>();

    public ServiceResolver(Vertx vertx, JsonObject config) {
        this.vertx = vertx;

        ServiceDiscoveryOptions options = new ServiceDiscoveryOptions()
                .setBackendConfiguration(new JsonObject()
                        .put("host", config.getString("consul.host", "localhost"))
                        .put("port", config.getInteger("consul.port", 8500)));

        this.discovery = ServiceDiscovery.create(vertx, options);

        // 定期刷新服务列表
        vertx.setPeriodic(5000, id -> refreshServices());
    }

    /**
     * 解析服务地址（带负载均衡）
     */
    public Future<ServiceInstance> resolve(String serviceName) {
        List<ServiceInstance> instances = serviceCache.get(serviceName);

        if (instances == null || instances.isEmpty()) {
            // 缓存未命中，实时查询
            return queryAndCache(serviceName)
                    .compose(list -> {
                        if (list.isEmpty()) {
                            return Future.failedFuture("Service not found: " + serviceName);
                        }
                        return Future.succeededFuture(selectInstance(list));
                    });
        }

        // 随机负载均衡（可改为轮询/加权）
        return Future.succeededFuture(selectInstance(instances));
    }

    /**
     * 查询 Consul 并更新缓存
     */
    private Future<List<ServiceInstance>> queryAndCache(String serviceName) {
        return discovery.getRecords(record -> record.getName().equals(serviceName))
                .map(records -> {
                    List<ServiceInstance> instances = new ArrayList<>();
                    for (Record record : records) {
                        JsonObject location = record.getLocation();
                        String host = location.getString("host");
                        int port = location.getInteger("port");
                        instances.add(new ServiceInstance(record.getRegistration(), host, port, record.getMetadata()));
                    }
                    serviceCache.put(serviceName, instances);
                    logger.debug("Refreshed {} instances for service: {}", instances.size(), serviceName);
                    return instances;
                });
    }

    private void refreshServices() {
        // 异步刷新所有已知服务
        serviceCache.keySet().forEach(this::queryAndCache);
    }

    private ServiceInstance selectInstance(List<ServiceInstance> instances) {
        // 随机选择（简单负载均衡）
        ServiceInstance instance = instances.get(random.nextInt(instances.size()));
        logger.debug("proxy request to {} with instance id {}", instance.getUrl(), instance.registration);
        return instance;
    }

    public void close() {
        discovery.close();
    }

    // 服务实例信息
    public static class ServiceInstance {
        public final String registration;
        public final String host;
        public final int port;
        public final JsonObject metadata;

        public ServiceInstance(String registration, String host, int port, JsonObject metadata) {
            this.registration = registration;
            this.host = host;
            this.port = port;
            this.metadata = metadata;
        }

        public String getUrl() {
            return "http://" + host + ":" + port;
        }

        @Override
        public String toString() {
            return getUrl();
        }
    }
}