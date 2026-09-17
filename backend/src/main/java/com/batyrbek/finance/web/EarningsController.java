package com.batyrbek.finance.web;

import com.batyrbek.finance.dto.CompanyEarnings;
import com.batyrbek.finance.service.EarningsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stocks")
public class EarningsController {
    private final EarningsService earningsService;

    public EarningsController(EarningsService earningsService) { this.earningsService = earningsService; }

    @GetMapping("/{ticker}/earnings")
    public CompanyEarnings earnings(@PathVariable String ticker) {
        return earningsService.getEarnings(ticker);
    }
}
