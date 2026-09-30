package com.github.h3yow.serviceobservabilitypipelinejava.demo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 下单请求体。
 *
 * @param itemId 商品 ID，必填且长度受限（非法值 → INVALID_ARGUMENT）
 * @param mode   演示用故障注入开关：risk / downstream / timeout / async，其余为正常路径
 */
public record OrderRequest(
        @NotBlank(message = "itemId must not be blank")
        @Size(max = 32, message = "itemId too long")
        String itemId,
        String mode) {
}
