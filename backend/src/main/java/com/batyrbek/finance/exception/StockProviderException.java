package com.batyrbek.finance.exception;

public class StockProviderException extends RuntimeException {
    public StockProviderException(String message) { super(message); }
    public StockProviderException(String message, Throwable cause) { super(message, cause); }
}
