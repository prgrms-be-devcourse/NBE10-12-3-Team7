package com.dongnemarket.auction.dto;

import java.math.BigDecimal;

public class BidRequest {

    private BigDecimal amount;

    protected BidRequest() {
    }

    public BidRequest(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
