package com.batyrbek.finance.provider.sec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecQuarterlyEarningsMapperTest {
    @Test
    void selectsDiscreteQuarterFactsAndIgnoresYearToDateTotals() throws Exception {
        JsonNode facts = new ObjectMapper().readTree("""
                {"facts":{"us-gaap":{
                  "Revenues":{"units":{"USD":[
                    {"start":"2025-01-01","end":"2025-03-31","val":20,"fp":"Q1","accn":"0000123456-25-000001","form":"10-Q","filed":"2025-05-01"},
                    {"start":"2025-04-01","end":"2025-06-30","val":25,"fp":"Q2","accn":"0000123456-25-000002","form":"10-Q","filed":"2025-08-01"},
                    {"start":"2025-01-01","end":"2025-06-30","val":45,"fp":"Q2","accn":"0000123456-25-000002","form":"10-Q","filed":"2025-08-01"}
                  ]}},
                  "EarningsPerShareDiluted":{"units":{"USD/shares":[
                    {"start":"2025-04-01","end":"2025-06-30","val":0.5,"fp":"Q2","accn":"0000123456-25-000002","form":"10-Q","filed":"2025-08-01"}
                  ]}}
                }}}
                """);

        var earnings = SecQuarterlyEarningsMapper.map("TEST", 123456, facts);

        assertThat(earnings.reported()).hasSize(2);
        assertThat(earnings.reported().getFirst().revenue()).isEqualTo(25.0);
        assertThat(earnings.reported().getFirst().reportedEps()).isEqualTo(0.5);
        assertThat(earnings.reported().getFirst().fiscalPeriod()).isEqualTo("Q2");
        assertThat(earnings.expectedDate()).isNull();
    }
}
