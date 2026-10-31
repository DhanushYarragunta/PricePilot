package com.pricepilot.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResearchCacheTest {
    @Test void writesOnlyAdditiveResearchTableAndDoesNotWritePrices(){
        var jdbc=mock(JdbcTemplate.class);var cache=new ResearchCache(jdbc,new ObjectMapper().findAndRegisterModules());
        var identity=new ResearchReport.Identity(1L,2L,"Acme AX-100","Acme","AX-100",null,"https://retailer.example/1",Map.of(),true,List.of());
        var report=new ResearchReport(identity,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),"Unknown",List.of(),List.of(),Instant.EPOCH,Instant.EPOCH.plusSeconds(3600),false,0);
        cache.save("key",report);
        ArgumentCaptor<String> sql=ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(),eq("key"),eq(1L),eq(2L),anyString(),any(java.sql.Timestamp.class),any(java.sql.Timestamp.class));
        assertThat(sql.getValue()).contains("INSERT INTO product_research","ON CONFLICT(cache_key)")
                .doesNotContain("price_point","UPDATE listing","UPDATE product ");
        verifyNoMoreInteractions(jdbc);
    }
}
