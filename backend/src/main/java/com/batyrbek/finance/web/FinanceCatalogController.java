package com.batyrbek.finance.web;

import java.util.List;

import com.batyrbek.finance.dto.SupportedStock;
import com.batyrbek.finance.service.SupportedStockCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/finance")
public class FinanceCatalogController {
    private final SupportedStockCatalog catalog;

    public FinanceCatalogController(SupportedStockCatalog catalog) { this.catalog = catalog; }

    @GetMapping("/supported-stocks")
    public List<SupportedStock> supportedStocks() { return catalog.all(); }
}
