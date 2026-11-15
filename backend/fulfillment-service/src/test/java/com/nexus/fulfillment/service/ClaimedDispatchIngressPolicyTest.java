package com.nexus.fulfillment.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nexus.common.exception.ApiException;
import org.junit.jupiter.api.Test;

/** Worker bật luôn đóng legacy; required riêng cho phép chuẩn bị/drain toàn fleet trước khi bật worker một instance. */
class ClaimedDispatchIngressPolicyTest {
    @Test
    void defaultsPreserveLegacyAndEitherGateClosesIt() {
        assertDoesNotThrow(new ClaimedDispatchIngressPolicy(false, false)::requireLegacyAllowed);
        for (boolean[] flags : new boolean[][]{{true, false}, {false, true}, {true, true}}) {
            var policy = new ClaimedDispatchIngressPolicy(flags[0], flags[1]);
            var error = assertThrows(ApiException.class, policy::requireLegacyAllowed);
            assertEquals(409, error.getHttpStatus().value()); assertEquals("CLAIMED_DISPATCH_REQUIRED", error.getCode());
        }
    }
}
