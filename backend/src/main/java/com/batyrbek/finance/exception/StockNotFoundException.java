package com.batyrbek.finance.exception;

public class StockNotFoundException extends RuntimeException {
    public StockNotFoundException(String ticker) { super("No public company was found for ticker " + ticker + "."); }
}
