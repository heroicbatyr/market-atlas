package com.batyrbek.finance.exception;

public class UnsupportedInstrumentException extends RuntimeException {
    public UnsupportedInstrumentException(String symbol) {
        super(symbol + " is not an active U.S. common stock supported by Market Atlas.");
    }
}
