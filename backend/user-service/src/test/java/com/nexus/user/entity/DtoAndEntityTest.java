package com.nexus.user.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexus.user.dto.ChangePasswordRequest;
import com.nexus.user.dto.CreateRoleRequest;
import com.nexus.user.dto.ForgotPasswordRequest;
import com.nexus.user.dto.LoginRequest;
import com.nexus.user.dto.RefreshTokenRequest;
import com.nexus.user.dto.RegisterRequest;
import com.nexus.user.dto.ResetPasswordRequest;
import com.nexus.user.dto.UpdateRoleRequest;
import com.nexus.user.dto.UpdateUserRequest;
import com.nexus.user.dto.shared.ApiResponse;
import com.nexus.user.dto.shared.PagedResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

/**
 * Kiểm thử toàn diện các Entity và DTO để đảm bảo tính toàn vẹn và độ phủ mã nguồn.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
class DtoAndEntityTest {

    @Test
    void testUserEntity() {
        UUID id = UUID.randomUUID();
        User u = new User();
        u.setId(id);
        u.setEmail("u@nexus.com");
        u.setPhone("0123456789");
        u.setFullName("User Fullname");
        u.setIdentifyNumber("ID123");
        u.setGender("MALE");
        u.setDateOfBirth(LocalDate.of(2000, 1, 1));
        u.setAddress("Hanoi");
        u.setPasswordHash("hash");
        u.setStatus("ACTIVE");
        u.setDeleted(false);
        u.setCreatedAt(LocalDateTime.now());
        u.setTokenVersion(1);
        u.setLockedUntil(null);
        u.setFailedLoginAttempts(0);

        Role role = new Role(UUID.randomUUID(), "BUYER", "Buyer", "Desc");
        u.addRole(role);
        u.removeRole(null);

        assertEquals(id, u.getId());
        assertEquals("u@nexus.com", u.getEmail());
        assertEquals("0123456789", u.getPhone());
        assertEquals("User Fullname", u.getFullName());
        assertEquals("ID123", u.getIdentifyNumber());
        assertEquals("MALE", u.getGender());
        assertEquals(LocalDate.of(2000, 1, 1), u.getDateOfBirth());
        assertEquals("Hanoi", u.getAddress());
        assertEquals("hash", u.getPasswordHash());
        assertEquals("ACTIVE", u.getStatus());
        assertFalse(u.isDeleted());
        assertNotNull(u.getCreatedAt());
        assertEquals(1, u.getTokenVersion());
        assertEquals(0, u.getFailedLoginAttempts());
        assertEquals(1, u.getRoles().size());

        User u2 = new User();
        u2.setId(id);
        assertEquals(u, u2);
        assertEquals(u.hashCode(), u2.hashCode());

        u.removeRole(role);
        assertEquals(0, u.getRoles().size());
    }

    @Test
    void testRoleAndPrivilegeEntity() {
        UUID pid = UUID.randomUUID();
        Privilege p = new Privilege(pid, "AUTH.LOGIN", "Login");
        assertEquals(pid, p.getId());
        assertEquals("AUTH.LOGIN", p.getCode());
        assertEquals("Login", p.getDescription());

        Privilege p2 = new Privilege();
        p2.setId(pid);
        p2.setCode("AUTH.LOGIN");
        p2.setDescription("Login");
        assertEquals(p, p2);
        assertEquals(p.hashCode(), p2.hashCode());

        UUID rid = UUID.randomUUID();
        Role r = new Role(rid, "ADMIN", "Admin", "Admin desc");
        r.addPrivilege(p);
        assertEquals(rid, r.getId());
        assertEquals("ADMIN", r.getCode());
        assertEquals("Admin", r.getName());
        assertEquals("Admin desc", r.getDescription());
        assertEquals(1, r.getPrivileges().size());

        Role r2 = new Role();
        r2.setId(rid);
        r2.setCode("ADMIN");
        assertEquals(r, r2);
        assertEquals(r.hashCode(), r2.hashCode());

        r.removePrivilege(p);
        r.removePrivilege(null);
        assertEquals(0, r.getPrivileges().size());
    }

    @Test
    void testReputationAndAuditLog() {
        UUID uid = UUID.randomUUID();
        Reputation rep = new Reputation(uid);
        rep.setScore(90);
        rep.setTrustLevel("VIP");
        rep.setTotalTx(10);
        rep.setSuccessTx(9);
        rep.setFailedTx(1);
        rep.setAuctionWon(5);
        rep.setAuctionFailed(0);

        assertEquals(uid, rep.getUserId());
        assertEquals(90, rep.getScore());
        assertEquals("VIP", rep.getTrustLevel());
        assertEquals(10, rep.getTotalTx());
        assertEquals(9, rep.getSuccessTx());
        assertEquals(1, rep.getFailedTx());
        assertEquals(5, rep.getAuctionWon());
        assertEquals(0, rep.getAuctionFailed());

        Reputation rep2 = new Reputation();
        rep2.setUserId(uid);
        assertEquals(rep, rep2);
        assertEquals(rep.hashCode(), rep2.hashCode());

        UUID aid = UUID.randomUUID();
        AuditLog al = new AuditLog(aid, uid, "LOGIN", "Login success", "127.0.0.1", "Chrome", "SUCCESS");
        assertEquals(aid, al.getId());
        assertEquals(uid, al.getUserId());
        assertEquals("LOGIN", al.getAction());
        assertEquals("Login success", al.getDescription());
        assertEquals("127.0.0.1", al.getIpAddress());
        assertEquals("Chrome", al.getUserAgent());
        assertEquals("SUCCESS", al.getStatus());
        assertNotNull(al.getCreatedAt());

        AuditLog al2 = new AuditLog();
        al2.setId(aid);
        assertEquals(al, al2);
        assertEquals(al.hashCode(), al2.hashCode());
    }

    @Test
    void testPasswordResetToken() {
        UUID tid = UUID.randomUUID();
        User u = new User();
        u.setId(UUID.randomUUID());
        LocalDateTime exp = LocalDateTime.now().plusMinutes(10);
        PasswordResetToken token = new PasswordResetToken(tid, u, "hash123", exp);

        assertEquals(tid, token.getId());
        assertEquals(u, token.getUser());
        assertEquals("hash123", token.getTokenHash());
        assertEquals(exp, token.getExpiresAt());
        assertFalse(token.isUsed());
        assertFalse(token.isExpired());

        token.setUsed(true);
        assertTrue(token.isUsed());

        PasswordResetToken t2 = new PasswordResetToken();
        t2.setId(tid);
        assertEquals(token, t2);
        assertEquals(token.hashCode(), t2.hashCode());
    }

    @Test
    void testDtos() {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("reg@nexus.com");
        reg.setPassword("Pass123");
        reg.setFullName("Name");
        reg.setPhone("0987654321");
        reg.setIdentifyNumber("ID001");
        reg.setGender("OTHER");
        reg.setDateOfBirth(LocalDate.of(1999, 12, 31));
        reg.setAddress("HCM");
        assertEquals("reg@nexus.com", reg.getEmail());
        assertEquals("Pass123", reg.getPassword());
        assertEquals("Name", reg.getFullName());
        assertEquals("0987654321", reg.getPhone());
        assertEquals("ID001", reg.getIdentifyNumber());
        assertEquals("OTHER", reg.getGender());
        assertEquals(LocalDate.of(1999, 12, 31), reg.getDateOfBirth());
        assertEquals("HCM", reg.getAddress());

        LoginRequest lr = new LoginRequest();
        lr.setEmail("l@n.com");
        lr.setPassword("p");
        assertEquals("l@n.com", lr.getEmail());
        assertEquals("p", lr.getPassword());

        RefreshTokenRequest rtr = new RefreshTokenRequest();
        rtr.setRefreshToken("tok");
        assertEquals("tok", rtr.getRefreshToken());

        ForgotPasswordRequest fpr = new ForgotPasswordRequest();
        fpr.setEmail("f@n.com");
        assertEquals("f@n.com", fpr.getEmail());

        ResetPasswordRequest rpr = new ResetPasswordRequest();
        rpr.setToken("tok1");
        rpr.setNewPassword("newPass");
        assertEquals("tok1", rpr.getToken());
        assertEquals("newPass", rpr.getNewPassword());

        ChangePasswordRequest cpr = new ChangePasswordRequest();
        cpr.setCurrentPassword("curr");
        cpr.setNewPassword("new");
        assertEquals("curr", cpr.getCurrentPassword());
        assertEquals("new", cpr.getNewPassword());

        UpdateUserRequest uur = new UpdateUserRequest();
        uur.setFullName("FN");
        uur.setPhone("0123");
        uur.setIdentifyNumber("ID2");
        uur.setGender("FEMALE");
        uur.setDateOfBirth(LocalDate.of(2001, 2, 2));
        uur.setAddress("Danang");
        assertEquals("FN", uur.getFullName());
        assertEquals("0123", uur.getPhone());
        assertEquals("ID2", uur.getIdentifyNumber());
        assertEquals("FEMALE", uur.getGender());
        assertEquals(LocalDate.of(2001, 2, 2), uur.getDateOfBirth());
        assertEquals("Danang", uur.getAddress());

        CreateRoleRequest crr = new CreateRoleRequest();
        crr.setCode("C");
        crr.setName("N");
        crr.setDescription("D");
        crr.setPrivilegeCodes(Set.of("P1"));
        assertEquals("C", crr.getCode());
        assertEquals("N", crr.getName());
        assertEquals("D", crr.getDescription());
        assertEquals(1, crr.getPrivilegeCodes().size());

        UpdateRoleRequest urr = new UpdateRoleRequest();
        urr.setName("N2");
        urr.setDescription("D2");
        urr.setPrivilegeCodes(Set.of());
        assertEquals("N2", urr.getName());
        assertEquals("D2", urr.getDescription());
        assertEquals(0, urr.getPrivilegeCodes().size());
    }

    @Test
    void testSharedDtos() {
        ApiResponse<String> resOk = ApiResponse.ok("Hello");
        assertTrue(resOk.isSuccess());
        assertEquals("Hello", resOk.getData());
        assertNotNull(resOk.getTimestamp());
        assertTrue(resOk.getErrors().isEmpty());

        ApiResponse<Void> resErrList = ApiResponse.error(List.of("Err1", "Err2"));
        assertFalse(resErrList.isSuccess());
        assertEquals(2, resErrList.getErrors().size());

        ApiResponse<Void> resErrMsg = ApiResponse.error("Single error");
        assertFalse(resErrMsg.isSuccess());
        assertEquals("Single error", resErrMsg.getErrors().get(0));

        ApiResponse<String> resCustom = new ApiResponse<>();
        resCustom.setSuccess(true);
        resCustom.setData("custom");
        resCustom.setErrors(List.of());
        resCustom.setTimestamp(Instant.now());
        assertTrue(resCustom.isSuccess());
        assertEquals("custom", resCustom.getData());

        PagedResponse<String> pagedEmpty = new PagedResponse<>();
        pagedEmpty.setContent(List.of("Item"));
        pagedEmpty.setPage(0);
        pagedEmpty.setSize(10);
        pagedEmpty.setTotalElements(1L);
        pagedEmpty.setTotalPages(1);
        pagedEmpty.setFirst(true);
        pagedEmpty.setLast(true);
        assertEquals(1, pagedEmpty.getContent().size());
        assertEquals(0, pagedEmpty.getPage());
        assertEquals(10, pagedEmpty.getSize());
        assertEquals(1L, pagedEmpty.getTotalElements());
        assertEquals(1, pagedEmpty.getTotalPages());
        assertTrue(pagedEmpty.isFirst());
        assertTrue(pagedEmpty.isLast());

        PagedResponse<String> fromSpringPage = PagedResponse.of(new PageImpl<>(List.of("A", "B")));
        assertEquals(2, fromSpringPage.getContent().size());
    }
}
