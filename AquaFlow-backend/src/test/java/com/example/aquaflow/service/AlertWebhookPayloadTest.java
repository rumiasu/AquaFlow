package com.example.aquaflow.service;

import com.example.aquaflow.entity.AlertLog;
import com.example.aquaflow.service.impl.AlertServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Strict JSON round-trip, not contains(): no external channel or database is involved. */
class AlertWebhookPayloadTest {
    @ParameterizedTest @ValueSource(strings={"WECOM","DINGTALK","RAW"})
    void everyJsonChannelPreservesControlCharacters(String format) throws Exception {
        var service=new AlertServiceImpl();ReflectionTestUtils.setField(service,"webhookFormat",format);
        String content="中文\"\\\n\r\t\b\f\u0000末尾";
        var row=new AlertLog();row.setTitle("合成告警");row.setSource("source\n\t");row.setContent(content);row.setLevel("ERROR");
        String text="【系统故障】"+row.getTitle()+"\nsource: "+row.getSource()+"\n"+row.getContent();
        String body=ReflectionTestUtils.invokeMethod(service,"buildWebhookBody",row,text);
        var json=new ObjectMapper().readTree(body);
        if(format.equals("RAW")) {
            assertEquals(content,json.path("content").asText());assertEquals(row.getSource(),json.path("source").asText());assertEquals(row.getTitle(),json.path("title").asText());
        } else {assertEquals("text",json.path("msgtype").asText());assertEquals(text,json.path("text").path("content").asText());}
    }
    @Test void serverChanKeepsItsFormContract() {
        var service=new AlertServiceImpl();ReflectionTestUtils.setField(service,"webhookFormat","SERVERCHAN");
        String body=ReflectionTestUtils.invokeMethod(service,"buildWebhookBody",new AlertLog(),"标题中文\n正文\"\\\t");
        assertEquals("title=标题中文&desp=正文\"\\\t",URLDecoder.decode(body,StandardCharsets.UTF_8));
        assertEquals("application/x-www-form-urlencoded",ReflectionTestUtils.invokeMethod(service,"webhookContentType"));
    }
}
