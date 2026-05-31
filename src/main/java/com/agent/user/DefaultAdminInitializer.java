package com.agent.user;

import com.agent.auth.PasswordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.init.default-admin.enabled", havingValue = "true", matchIfMissing = true)
@Order(100)
public class DefaultAdminInitializer implements CommandLineRunner {

    private final UserService userService;
    private final PasswordService passwordService;

    public DefaultAdminInitializer(UserService userService, PasswordService passwordService) {
        this.userService = userService;
        this.passwordService = passwordService;
    }

    @Override
    public void run(String... args) {
        try {
            long adminCount = userService.countByRole(UserRole.ADMIN);
            if (adminCount > 0) {
                log.info("Default admin check: {} admin user(s) already exist, skipping initialization", adminCount);
                return;
            }
            UserEntity admin = new UserEntity();
            admin.setUsername(getEnvOrDefault("app.init.default-admin.username", "admin"));
            admin.setName(getEnvOrDefault("app.init.default-admin.name", "系统管理员"));
            admin.setGender("UNKNOWN");
            admin.setPhone("");
            admin.setDepartment(getEnvOrDefault("app.init.default-admin.department", "技术部"));
            admin.setRole(UserRole.ADMIN.name());
            String defaultPassword = getEnvOrDefault("app.init.default-admin.password", "admin123");
            admin.setPasswordHash(passwordService.hash(defaultPassword));
            admin.setStatus(UserStatus.ACTIVE.name());

            UserEntity created = userService.create(admin);

            log.info("==================== DEFAULT ADMIN CREATED ====================");
            log.info("  Username: {}", created.getUsername());
            log.info("  Password: {}", defaultPassword);
            log.info("  Name:    {}", created.getName());
            log.info("  Role:    {}", created.getRole());
            log.info("  ⚠️  Please change the default password after first login!");
            log.info("============================================================");
        } catch (Exception e) {
            log.warn("Failed to initialize default admin (may already exist or DB not ready): {}", e.getMessage());
        }
    }

    private String getEnvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key.replace(".", "_").toUpperCase().replace("-", "_"));
        if (value != null && !value.isBlank()) return value;
        value = System.getProperty(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
