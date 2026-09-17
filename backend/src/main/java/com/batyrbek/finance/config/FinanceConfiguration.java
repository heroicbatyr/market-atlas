package com.batyrbek.finance.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import com.batyrbek.finance.dto.CompanyFundamentals;
import com.batyrbek.finance.dto.CompanyFinancials;
import com.batyrbek.finance.dto.StockHistory;
import com.batyrbek.finance.dto.StockQuote;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class FinanceConfiguration {
    @Bean
    WebClient stockWebClient(@Value("${stock.provider.base-url}") String baseUrl) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();
    }

    @Bean
    WebClient massiveWebClient(@Value("${stock.massive.base-url:https://api.massive.com}") String baseUrl) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();
    }

    @Bean
    Cache<String, StockQuote> quoteCache() {
        return Caffeine.newBuilder().maximumSize(250).expireAfterWrite(Duration.ofHours(24)).build();
    }

    @Bean
    Cache<String, StockQuote> staleQuoteCache() {
        return Caffeine.newBuilder().maximumSize(250).expireAfterWrite(Duration.ofDays(2)).build();
    }

    @Bean
    Cache<String, CompanyFundamentals> fundamentalsCache() {
        return Caffeine.newBuilder().maximumSize(250).expireAfterWrite(Duration.ofHours(24)).build();
    }

    @Bean
    Cache<String, CompanyFundamentals> staleFundamentalsCache() {
        return Caffeine.newBuilder().maximumSize(250).expireAfterWrite(Duration.ofDays(30)).build();
    }

    @Bean
    Cache<String, CompanyFinancials> financialsCache() {
        return Caffeine.newBuilder().maximumSize(250).expireAfterWrite(Duration.ofDays(7)).build();
    }

    @Bean
    Cache<String, CompanyFinancials> staleFinancialsCache() {
        return Caffeine.newBuilder().maximumSize(250).expireAfterWrite(Duration.ofDays(60)).build();
    }

    @Bean
    Cache<String, StockHistory> historyCache() {
        return Caffeine.newBuilder().maximumSize(250).expireAfterWrite(Duration.ofHours(24)).build();
    }

    @Bean
    Cache<String, StockHistory> staleHistoryCache() {
        return Caffeine.newBuilder().maximumSize(250).expireAfterWrite(Duration.ofDays(30)).build();
    }

    @Bean
    CorsFilter corsFilter(@Value("${stock.cors.allowed-origins}") String origins) {
        List<String> allowedOrigins = Arrays.stream(origins.split(",")).map(String::trim).filter(origin -> !origin.isBlank()).toList();
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Accept", "Content-Type"));
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/stocks/**", configuration);
        source.registerCorsConfiguration("/api/finance/**", configuration);
        return new CorsFilter(source);
    }
}
