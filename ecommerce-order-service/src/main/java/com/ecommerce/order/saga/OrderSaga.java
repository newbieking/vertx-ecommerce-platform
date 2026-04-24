package com.ecommerce.order.saga;

import com.ecommerce.common.model.Order;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Saga 模式：长事务拆分为多个本地事务，失败时反向补偿
 *
 * 正向流程：
 * 1. 创建订单 (本地) -> 2. 校验商品 (远程) -> 3. 预占库存 (远程) -> 4. 创建支付 (远程)
 *
 * 补偿流程（任意步骤失败）：
 * 4. 取消支付 -> 3. 释放库存 -> 2. 无需补偿 -> 1. 取消订单
 */
public class OrderSaga {

    private static final Logger logger = LoggerFactory.getLogger(OrderSaga.class);

    private final Vertx vertx;
    private final EventBus eventBus;
    private final List<SagaStep> steps = new ArrayList<>();
    private final List<SagaStep> compensations = new ArrayList<>();

    public OrderSaga(Vertx vertx) {
        this.vertx = vertx;
        this.eventBus = vertx.eventBus();
    }

    /**
     * 添加 Saga 步骤
     * @param name 步骤名称
     * @param action 正向操作
     * @param compensation 补偿操作（失败时执行）
     */
    public OrderSaga addStep(String name,
                             Function<JsonObject, Future<JsonObject>> action,
                             Function<JsonObject, Future<JsonObject>> compensation) {
        steps.add(new SagaStep(name, action));
        if (compensation != null) {
            compensations.add(0, new SagaStep(name + "-compensate", compensation)); // 反向插入
        }
        return this;
    }

    /**
     * 执行 Saga
     */
    public Future<JsonObject> execute(JsonObject context) {
        Promise<JsonObject> promise = Promise.promise();

        executeStep(0, context, promise);

        return promise.future();
    }

    private void executeStep(int index, JsonObject context, Promise<JsonObject> promise) {
        if (index >= steps.size()) {
            // 全部成功
            logger.info("✅ Saga completed successfully");
            promise.complete(context.put("success", true));
            return;
        }

        SagaStep step = steps.get(index);
        logger.info("▶️ Executing step: {}", step.name);

        step.action.apply(context)
                .onSuccess(result -> {
                    // 合并结果到上下文
                    context.mergeIn(result);
                    logger.info("✅ Step success: {}", step.name);
                    // 执行下一步
                    executeStep(index + 1, context, promise);
                })
                .onFailure(err -> {
                    logger.error("❌ Step failed: {} - {}", step.name, err.getMessage());
                    // 触发补偿
                    compensate(index - 1, context)
                            .onComplete(v -> promise.fail(
                                    new RuntimeException("Saga failed at step: " + step.name, err)));
                });
    }

    /**
     * 执行补偿（反向操作）
     */
    private Future<Void> compensate(int lastSuccessIndex, JsonObject context) {
        Promise<Void> promise = Promise.promise();

        if (lastSuccessIndex < 0 || compensations.isEmpty()) {
            promise.complete();
            return promise.future();
        }

        List<Future<?>> futures = new ArrayList<>();

        // 只补偿已成功的步骤（反向执行）
        for (int i = 0; i <= lastSuccessIndex && i < compensations.size(); i++) {
            SagaStep comp = compensations.get(i);
            logger.info("↩️ Compensating: {}", comp.name);

            Future<JsonObject> f = comp.action.apply(context)
                    .onSuccess(v -> logger.info("✅ Compensation success: {}", comp.name))
                    .onFailure(err -> logger.error("⚠️ Compensation failed: {} - {}", comp.name, err.getMessage()))
                    .mapEmpty();

            futures.add(f);
        }

        return Future.all(futures).mapEmpty();
    }

    private static class SagaStep {
        final String name;
        final Function<JsonObject, Future<JsonObject>> action;

        SagaStep(String name, Function<JsonObject, Future<JsonObject>> action) {
            this.name = name;
            this.action = action;
        }
    }
}