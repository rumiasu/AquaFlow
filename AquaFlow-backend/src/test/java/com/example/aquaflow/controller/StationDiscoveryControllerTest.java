package com.example.aquaflow.controller;

import com.example.aquaflow.config.WebMvcConfig;
import com.example.aquaflow.interceptor.AuthInterceptor;
import com.example.aquaflow.interceptor.RateLimitInterceptor;
import com.example.aquaflow.interceptor.PublicRateLimitInterceptor;
import com.example.aquaflow.service.StationDiscoveryService;
import com.example.aquaflow.vo.StationDiscoveryVO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真 MVC 参数绑定、序列化及路径注册；不启动 Spring 数据源、不访问数据库。 */
class StationDiscoveryControllerTest {
    @Test void bothPublicAliasesBindLocationAndRegionAndReturnCompatibleArray() throws Exception {
        var service = mock(StationDiscoveryService.class);
        var station = new StationDiscoveryVO();
        station.setId(7L); station.setName("合成站"); station.setAddress("山东省济南市合成地址"); station.setStatus(1);
        when(service.discover(new BigDecimal("36"), new BigDecimal("117"), "山东省", "济南市"))
                .thenReturn(List.of(station));
        var controller = new StationController();
        ReflectionTestUtils.setField(controller, "stationDiscoveryService", service);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (String path : List.of("/api/stations/discover", "/api/station/discover")) {
            mvc.perform(get(path).param("lat", "36").param("lng", "117").param("province", "山东省").param("city", "济南市"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data[0].id").value(7)).andExpect(jsonPath("$.data[0].name").value("合成站"))
                    .andExpect(jsonPath("$.data[0].address").value("山东省济南市合成地址"))
                    .andExpect(jsonPath("$.data[0].status").value(1));
        }
        verify(service, times(2)).discover(new BigDecimal("36"), new BigDecimal("117"), "山东省", "济南市");
    }
    @Test void nonNumericCoordinatesCannotReachDiscovery() throws Exception {
        var service = mock(StationDiscoveryService.class);
        var controller = new StationController();
        ReflectionTestUtils.setField(controller, "stationDiscoveryService", service);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (String value : List.of("NaN", "Infinity", "unknown"))
            mvc.perform(get("/api/stations/discover").param("lat", value).param("lng", "117"))
                    .andExpect(status().is4xxClientError());
        verifyNoInteractions(service);
    }
    @Test void discoveryAliasesAreAnonymousButSharePublicRateLimit() {
        var config = new WebMvcConfig();
        var auth = mock(AuthInterceptor.class);
        var publicLimit = mock(PublicRateLimitInterceptor.class);
        ReflectionTestUtils.setField(config, "authInterceptor", auth);
        ReflectionTestUtils.setField(config, "publicRateLimitInterceptor", publicLimit);
        ReflectionTestUtils.setField(config, "rateLimitInterceptor", mock(RateLimitInterceptor.class));
        var registry = new ExposedRegistry(); config.addInterceptors(registry);
        for (String path : List.of("/api/stations/discover", "/api/station/discover")) {
            var request = new MockHttpServletRequest("GET", path); ServletRequestPathUtils.parseAndCache(request);
            boolean limited = false;
            for (Object entry : registry.entries()) {
                if (entry instanceof MappedInterceptor mapped) {
                    if (mapped.getInterceptor() == auth) assertFalse(mapped.matches(request));
                    if (mapped.getInterceptor() == publicLimit) { assertTrue(mapped.matches(request)); limited = true; }
                }
            }
            assertTrue(limited);
        }
    }
    private static class ExposedRegistry extends InterceptorRegistry {
        List<Object> entries() { return getInterceptors(); }
    }
}
