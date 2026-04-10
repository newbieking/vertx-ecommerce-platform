package com.ecommerce.common.config;

import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigLoader {

    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);

    // 环境变量名称
    private static final String ENV_VAR = "ENV";
    private static final String CONFIG_PATH = "CONFIG_PATH";

    public static Future<JsonObject> load(Vertx vertx) {
        String env = System.getenv().getOrDefault(ENV_VAR, "dev");
        String customPath = System.getenv(CONFIG_PATH);

        ConfigRetrieverOptions options = new ConfigRetrieverOptions();

        // 1. 默认配置文件 (conf/config.json)
        String defaultPath = customPath != null ? customPath : "conf/config.json";
        options.addStore(new ConfigStoreOptions()
                .setType("file")
                .setFormat("json")
                .setConfig(new JsonObject()
                        .put("path", defaultPath)
                        .put("optional", true)));

        // 2. 环境特定配置 (conf/config-dev.json)
        String envConfigPath = "conf/config-" + env + ".json";
        options.addStore(new ConfigStoreOptions()
                .setType("file")
                .setFormat("json")
                .setConfig(new JsonObject()
                        .put("path", envConfigPath)
                        .put("optional", true)));

        // 3. 系统属性覆盖 (-Dhttp.port=9090)
        options.addStore(new ConfigStoreOptions()
                .setType("sys"));

        // 4. 环境变量覆盖 (HTTP_PORT=9090)
        options.addStore(new ConfigStoreOptions()
                .setType("env"));

        ConfigRetriever retriever = ConfigRetriever.create(vertx, options);

        return retriever.getConfig()
                .onSuccess(config -> logger.info("Configuration loaded for env: {}", env))
                .onFailure(err -> logger.error("Failed to load configuration", err));
    }
}