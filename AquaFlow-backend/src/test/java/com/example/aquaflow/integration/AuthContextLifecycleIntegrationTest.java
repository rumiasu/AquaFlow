package com.example.aquaflow.integration;

import com.example.aquaflow.interceptor.AuthInterceptor;
import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.util.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证拦截器的**请求上下文生命周期**（返工契约 §5 的 V12，返工项 R7）。
 *
 * <p>缺陷形状：{@code preHandle} 先把身份写进 {@code AuthContext}（ThreadLocal），
 * 再在白名单外 {@code return false}；而 Spring **只对"preHandle 曾返回 true"的拦截器**
 * 回调 {@code afterCompletion} ⇒ 被拒绝的请求把身份留在了复用的工作线程上，
 * 下一条落在拦截器管辖范围之外的请求会读到**上一个人的身份**。</p>
 *
 * <p>本类直接用 {@code MockHttpServletRequest} 调真实的 {@code AuthInterceptor}：
 * 三种收尾路径（业务拒绝、响应写失败、未认证）都必须把上下文清空，而正常引导路径必须可用。</p>
 */
@DisplayName("认证拦截器 · 上下文生命周期（V12）")
class AuthContextLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AuthInterceptor authInterceptor;

    private MockHttpServletRequest request(String method, String uri, String token) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        if (token != null) {
            req.addHeader("Authorization", "Bearer " + token);
        }
        return req;
    }

    /** 模拟"上一条请求在同一线程上留下的身份"。 */
    private void seedStaleIdentityOnThisThread() {
        AuthContext.set(new AuthContext.AuthUser(424242L, "staff", "STATION_MANAGER", 1L, null));
    }

    @Test
    @DisplayName("V12a：UNSELECTED 白名单外被拒后，AuthContext 必须为空（不能留下上一条请求的身份）")
    void rejectedUnselectedRequestClearsContext() throws Exception {
        String token = unselectedStaffToken(-1001L, "openid-lifecycle");
        seedStaleIdentityOnThisThread();

        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean proceed = authInterceptor.preHandle(request("GET", "/api/orders", token), res, new Object());

        assertFalse(proceed, "未选身份的会话不得访问业务端点");
        assertEquals(200, res.getStatus(), "业务拒绝仍是 HTTP 200（客户端一律判 body code）");
        assertTrue(res.getContentAsString().contains("请先完成身份选择"), "拒绝文案，实际=" + res.getContentAsString());
        assertNull(AuthContext.get(), "★ 拒绝路径必须清空 AuthContext —— 否则线程复用时下一条请求会读到上一个人的身份");
    }

    @Test
    @DisplayName("V12b：响应写出失败（客户端提前断开）同样必须清空 AuthContext")
    void responseWriteFailureAlsoClearsContext() {
        String token = unselectedStaffToken(-1002L, "openid-write-fail");
        seedStaleIdentityOnThisThread();

        HttpServletResponse broken = new MockHttpServletResponse() {
            @Override
            public PrintWriter getWriter() throws java.io.UnsupportedEncodingException {
                // MockHttpServletResponse 的 getWriter() 只声明了 UnsupportedEncodingException
                //（IOException 的子类，正是"响应写出失败"这一族），所以这里用它来模拟断管。
                throw new java.io.UnsupportedEncodingException("Broken pipe（模拟客户端提前断开）");
            }
        };
        MockHttpServletRequest req = request("GET", "/api/orders", token);

        assertThrows(IOException.class, () -> authInterceptor.preHandle(req, broken, new Object()),
                "写出失败会从 preHandle 抛出（同样没有 afterCompletion 兜底）");
        assertNull(AuthContext.get(), "★ 异常路径也必须清空 AuthContext");
    }

    @Test
    @DisplayName("V12c：未认证请求被拒后上下文为空，且不会继承旧身份")
    void unauthenticatedRequestDoesNotInheritStaleIdentity() throws Exception {
        seedStaleIdentityOnThisThread();

        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean proceed = authInterceptor.preHandle(request("GET", "/api/orders", null), res, new Object());

        assertFalse(proceed, "没有 Authorization 头必须拒绝");
        assertEquals(401, res.getStatus(), "未认证是本仓唯一返回真 401 的场景");
        assertNull(AuthContext.get(), "★ 旧身份必须先被清掉，未认证请求不得继承它");
    }

    @Test
    @DisplayName("V12d：正常引导路径可用 —— 白名单内的 UNSELECTED 会话放行，身份是本次 token 的")
    void allowedUnselectedPathStillWorks() throws Exception {
        String token = unselectedStaffToken(-1003L, "openid-guide");
        seedStaleIdentityOnThisThread();   // 就算线程上有旧身份，也必须被本次请求覆盖

        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean proceed = authInterceptor.preHandle(request("POST", "/api/auth/select-role", token), res, new Object());

        assertTrue(proceed, "选身份是引导流程，必须放行");
        assertNotNull(AuthContext.get(), "放行后上下文里应当是本次请求的身份");
        assertEquals(-1003L, AuthContext.getUserId(), "身份必须是本次 token 的 userId（占位负数）");
        assertEquals("UNSELECTED", AuthContext.getRole());
        assertEquals("openid-guide", AuthContext.getPendingOpenid(), "待绑定 openid 只能来自 token claims");

        // 请求收尾：afterCompletion 负责正常路径的清理
        authInterceptor.afterCompletion(request("POST", "/api/auth/select-role", token), res, new Object(), null);
        assertNull(AuthContext.get(), "正常收尾后也必须清空");
    }
}
