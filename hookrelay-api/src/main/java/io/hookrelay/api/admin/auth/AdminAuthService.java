package io.hookrelay.api.admin.auth;

import io.hookrelay.api.security.jwt.JwtService;
import io.hookrelay.common.admin.AdminUser;
import io.hookrelay.common.admin.AdminUserRepository;
import io.hookrelay.common.admin.AdminUserStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuthService {

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AdminAuthService(
            AdminUserRepository adminUserRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * Looks up by email across all tenants — at login time the caller hasn't
     * proven which tenant they belong to yet, so this must run unfiltered.
     */
    @Transactional(readOnly = true)
    public String login(String email, String password) {
        AdminUser adminUser = adminUserRepository.findByEmail(email)
                .filter(user -> user.getStatus() == AdminUserStatus.ACTIVE)
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        if (!passwordEncoder.matches(password, adminUser.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }
        return jwtService.issue(adminUser);
    }
}
