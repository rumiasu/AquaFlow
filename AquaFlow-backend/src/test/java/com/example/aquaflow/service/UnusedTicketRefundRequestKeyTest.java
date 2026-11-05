package com.example.aquaflow.service;

import com.example.aquaflow.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 退票请求键的离线契约：ASCII、尾空格、长度；不改写原键。 */
class UnusedTicketRefundRequestKeyTest {

    @Test
    void acceptsExistingLegalKeysAndBoundaryLength() {
        assertDoesNotThrow(() -> UnusedTicketRefundService.requireValidRequestKey("closed-exit"));
        assertDoesNotThrow(() -> UnusedTicketRefundService.requireValidRequestKey("ticket-exit-abc-123"));
        assertDoesNotThrow(() -> UnusedTicketRefundService.requireValidRequestKey("a".repeat(64)));
        // 不 trim：前导空格和内部空格是原键的一部分，只有尾空格会触发 PAD SPACE 歧义。
        assertDoesNotThrow(() -> UnusedTicketRefundService.requireValidRequestKey(" leading"));
        assertDoesNotThrow(() -> UnusedTicketRefundService.requireValidRequestKey("internal key"));
    }

    @Test
    void rejectsNonAsciiInsteadOfLettingDatabaseRejectIt() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> UnusedTicketRefundService.requireValidRequestKey("退票-key"));
        assertEquals("退票请求凭据只能包含 ASCII 字符", error.getMessage());
        assertThrows(BusinessException.class,
                () -> UnusedTicketRefundService.requireValidRequestKey("key\u0080"));
    }

    @Test
    void rejectsTrailingSpaceWithoutSilentlyTrimming() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> UnusedTicketRefundService.requireValidRequestKey("ticket-exit-1 "));
        assertEquals("退票请求凭据末尾不能是空格", error.getMessage());
    }

    @Test
    void rejectsBlankAndOverlongKeys() {
        assertThrows(BusinessException.class, () -> UnusedTicketRefundService.requireValidRequestKey(null));
        assertThrows(BusinessException.class, () -> UnusedTicketRefundService.requireValidRequestKey(""));
        assertThrows(BusinessException.class, () -> UnusedTicketRefundService.requireValidRequestKey("   "));
        assertThrows(BusinessException.class, () -> UnusedTicketRefundService.requireValidRequestKey("a".repeat(65)));
    }
}
