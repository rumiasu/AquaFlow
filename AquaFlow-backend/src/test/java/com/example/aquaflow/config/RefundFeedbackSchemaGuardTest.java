package com.example.aquaflow.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RefundFeedbackSchemaGuardTest {
    List<Map<String,Object>> columns() {
        var rows=new ArrayList<Map<String,Object>>();
        Map.of("refund_type",24,"refund_id",0,"responsible_station_id",0,"actor_key",40,"idempotency_key",64,"request_digest",64).forEach((n,len)->{
            var r=new HashMap<String,Object>();r.put("column_name",n);r.put("data_type",len==0?"bigint":"varchar");r.put("is_nullable","YES");r.put("character_maximum_length",len);r.put("collation_name","utf8mb4_bin");rows.add(r);
        });return rows;
    }
    void check(List<Map<String,Object>> cols,List<Map<String,Object>> keys,String engine) {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenAnswer(c->{String s=c.getArgument(0);return s.contains("statistics") ? keys : s.contains("information_schema.tables") ? List.of(Map.of("engine",engine)) : cols;});
        new RefundFeedbackSchemaGuard(jdbc).verifySchema();verify(jdbc,never()).execute(anyString());
    }
    List<Map<String,Object>> keys() {return new ArrayList<>(List.of(Map.of("column_name","actor_key"),Map.of("column_name","refund_type"),Map.of("column_name","refund_id"),Map.of("column_name","idempotency_key")));}
    @Test void exactAdditiveStructureIsAcceptedWithoutWriting() {assertDoesNotThrow(()->check(columns(),keys(),"InnoDB"));}
    @ParameterizedTest @ValueSource(strings={"refund_type","refund_id","responsible_station_id","actor_key","idempotency_key","request_digest"})
    void missingColumnIsRejected(String name) {var c=columns();c.removeIf(r->name.equals(r.get("column_name")));assertThrows(IllegalStateException.class,()->check(c,keys(),"InnoDB"));}
    @Test void prefixKeyWrongOrderOrMissingUniquenessIsRejected() {
        var prefix=keys();prefix.set(0,Map.of("column_name","actor_key","sub_part",10));assertThrows(IllegalStateException.class,()->check(columns(),prefix,"InnoDB"));
        var reordered=keys();Collections.swap(reordered,0,1);assertThrows(IllegalStateException.class,()->check(columns(),reordered,"InnoDB"));assertThrows(IllegalStateException.class,()->check(columns(),List.of(),"InnoDB"));
    }
    @Test void CaseInsensitiveKeyOrShortDigestOrNonTransactionalEngineIsRejected() {
        var c=columns();c.stream().filter(r->"idempotency_key".equals(r.get("column_name"))).findFirst().orElseThrow().put("collation_name","utf8mb4_0900_ai_ci");assertThrows(IllegalStateException.class,()->check(c,keys(),"InnoDB"));
        var shortDigest=columns();shortDigest.stream().filter(r->"request_digest".equals(r.get("column_name"))).findFirst().orElseThrow().put("character_maximum_length",32);assertThrows(IllegalStateException.class,()->check(shortDigest,keys(),"InnoDB"));assertThrows(IllegalStateException.class,()->check(columns(),keys(),"MyISAM"));
    }
}
