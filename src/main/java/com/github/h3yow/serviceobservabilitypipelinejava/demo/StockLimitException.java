package com.github.h3yow.serviceobservabilitypipelinejava.demo;

import com.github.h3yow.serviceobservabilitypipelinejava.observability.exception.BusinessRejectedException;

/** 库存不足导致的业务拒绝，reason 固定为白名单内的 STOCK_LIMIT。 */
public class StockLimitException extends BusinessRejectedException {
    public StockLimitException(String itemId) {
        super("STOCK_LIMIT", "item " + itemId + " exceeds stock limit");
    }
}
