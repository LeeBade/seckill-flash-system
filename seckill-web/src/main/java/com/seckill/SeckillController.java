package com.seckill;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.seckill.common.ResultCode;
import com.seckill.engine.SeckillResult;
import com.seckill.gateway.GatewayConstants;
import com.seckill.order.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 秒杀入口 Controller——位于五层 Filter 链之后。
 * <p>
 * 到达此处的请求已通过：IP 限流 → Bloom 过滤 → HMAC 验签 → 水位检查 → 滑动窗口。
 * userId 和 goodsId 从 request attribute 读取（由 DynamicUrlFilter 验签后注入），
 * activityId 和 price 从 Query 参数读取（业务字段，不参与鉴权）。
 * </p>
 * <p>
 * <b>不直接调用 engine.execute()</b>——execute + MQ + Saga rollback 三阶段
 * 由 {@link OrderService#createOrder} 内部原子绑定。外部调用 execute 会破坏
 * Saga 闭合导致双重扣减。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@RestController
public class SeckillController {

    private static final Logger log = LoggerFactory.getLogger(SeckillController.class);

    private final OrderService orderService;

    public SeckillController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/api/seckill")
    @SentinelResource(value = "seckill", fallback = "seckillFallback", blockHandler = "seckillBlocked")
    public Map<String, Object> seckill(HttpServletRequest request,
                                       @RequestParam long activityId,
                                       @RequestParam int price) {
        Long goodsId = (Long) request.getAttribute(GatewayConstants.ATTR_GOODS_ID);
        Long userId = (Long) request.getAttribute(GatewayConstants.ATTR_USER_ID);

        if (goodsId == null || userId == null) {
            return Map.of(
                    "code", ResultCode.TOKEN_INVALID.getCode(),
                    "message", ResultCode.TOKEN_INVALID.getMessage()
            );
        }

        OrderService.CreateOrderResult result = orderService.createOrder(goodsId, userId, activityId, price);
        if (!result.isSuccess()) {
            SeckillResult rejection = result.getRejection();
            int code = switch (rejection) {
                case SOLD_OUT -> ResultCode.SOLD_OUT.getCode();
                case ALREADY_BOUGHT -> ResultCode.ALREADY_BOUGHT.getCode();
                default -> ResultCode.UNKNOWN_ERROR.getCode();
            };
            return Map.of("code", code, "message", rejection.name());
        }

        return Map.of(
                "code", ResultCode.SECKILL_SUCCESS.getCode(),
                "orderId", result.getOrderId(),
                "message", ResultCode.SECKILL_SUCCESS.getMessage()
        );
    }

    public Map<String, Object> seckillFallback(HttpServletRequest request,
                                                long activityId, int price, Throwable t) {
        log.warn("Sentinel fallback triggered", t);
        return Map.of(
                "code", ResultCode.SYSTEM_BUSY.getCode(),
                "message", ResultCode.SYSTEM_BUSY.getMessage()
        );
    }

    public Map<String, Object> seckillBlocked(HttpServletRequest request,
                                               long activityId, int price, BlockException e) {
        log.warn("Sentinel blocked");
        return Map.of(
                "code", ResultCode.SYSTEM_BUSY.getCode(),
                "message", ResultCode.SYSTEM_BUSY.getMessage()
        );
    }
}
