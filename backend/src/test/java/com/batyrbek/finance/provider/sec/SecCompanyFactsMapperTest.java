package com.batyrbek.finance.provider.sec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecCompanyFactsMapperTest {
    @Test
    void usesTheLatestFilingAccessionForEachAnnualPeriodAndLinksIt() throws Exception {
        JsonNode facts = new ObjectMapper().readTree("""
                {"facts":{"us-gaap":{
                  "Revenues":{"units":{"USD":[
                    {"start":"2024-01-01","end":"2024-12-31","val":100,"accn":"0000123456-25-000001","form":"10-K","filed":"2025-02-01"},
                    {"start":"2024-01-01","end":"2024-12-31","val":110,"accn":"0000123456-26-000002","form":"10-K","filed":"2026-02-01"},
                    {"start":"2025-01-01","end":"2025-12-31","val":150,"accn":"0000123456-26-000002","form":"10-K","filed":"2026-02-01"},
                    {"start":"2025-10-01","end":"2025-12-31","val":45,"accn":"0000123456-26-000003","form":"10-Q","filed":"2026-01-01"}
                  ]}},
                  "NetIncomeLoss":{"units":{"USD":[
                    {"start":"2024-01-01","end":"2024-12-31","val":11,"accn":"0000123456-26-000002","form":"10-K","filed":"2026-02-01"},
                    {"start":"2025-01-01","end":"2025-12-31","val":15,"accn":"0000123456-26-000002","form":"10-K","filed":"2026-02-01"}
                  ]}},
                  "NetCashProvidedByUsedInOperatingActivities":{"units":{"USD":[
                    {"start":"2025-01-01","end":"2025-12-31","val":30,"accn":"0000123456-26-000002","form":"10-K","filed":"2026-02-01"}
                  ]}},
                  "PaymentsToAcquirePropertyPlantAndEquipment":{"units":{"USD":[
                    {"start":"2025-01-01","end":"2025-12-31","val":5,"accn":"0000123456-26-000002","form":"10-K","filed":"2026-02-01"}
                  ]}}
                }}}
                """);

        var result = SecCompanyFactsMapper.map("TEST", 123456, facts);

        assertThat(result.annual()).hasSize(2);
        assertThat(result.annual().getFirst().revenue()).isEqualTo(110);
        assertThat(result.annual().getLast().revenueGrowth()).isCloseTo(150.0 / 110.0 - 1,
                org.assertj.core.data.Offset.offset(0.0001));
        assertThat(result.annual().getLast().freeCashFlow()).isEqualTo(25);
        assertThat(result.annual().getLast().filingUrl())
                .isEqualTo("https://www.sec.gov/Archives/edgar/data/123456/000012345626000002/0000123456-26-000002-index.htm");
        assertThat(result.source()).isEqualTo("SEC EDGAR");
    }
}
