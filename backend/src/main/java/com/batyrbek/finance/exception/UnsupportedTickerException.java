package com.batyrbek.finance.exception;

public class UnsupportedTickerException extends RuntimeException {
    public UnsupportedTickerException(String ticker) {
        super(ticker + " is not in the currently supported research universe.");
    }
}
