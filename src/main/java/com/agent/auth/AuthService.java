package com.agent.auth;

import com.agent.user.UserEntity;
import com.agent.user.UserRole;
import com.agent.user.UserService;
import com.agent.user.UserStatus;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserService userService;
    private final PasswordService passwordService;
    private final JwtService jwtService;

    public AuthService(UserService userService, PasswordService passwordService, JwtService jwtService) {
        this.userService = userService;
        this.passwordService = passwordService;
        this.jwtService = jwtService;
    }

    public CurrentUser register(RegisterRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("姓名不能为空");
        }
        if (request.getDepartment() == null || request.getDepartment().isBlank()) {
            throw new IllegalArgumentException("部门不能为空");
        }
        if (request.getPhone() == null || request.getPhone().isBlank()) {
            throw new IllegalArgumentException("手机号不能为空");
        }
        if (userService.findByUsername(request.getUsername()) != null) {
            throw new IllegalArgumentException("用户名已存在");
        }

        UserEntity user = new UserEntity();
        user.setUsername(request.getUsername().trim());
        user.setPasswordHash(passwordService.hash(request.getPassword()));
        user.setName(request.getName().trim());
        user.setGender(request.getGender() == null || request.getGender().isBlank() ? "UNKNOWN" : request.getGender());
        user.setPhone(request.getPhone().trim());
        user.setDepartment(request.getDepartment().trim());
        user.setRole(UserRole.USER.name());
        user.setStatus(UserStatus.ACTIVE.name());
        return userService.toCurrentUser(userService.create(user));
    }

    public AuthResponse login(LoginRequest request) {
        String account = request.getAccount();
        if ((account == null || account.isBlank()) && request.getUsername() != null) {
            account = request.getUsername();
        }
        UserEntity user = userService.findByAccount(account);
        if (user == null || !passwordService.matches(request.getPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("账号或密码错误");
        }
        if (!UserStatus.ACTIVE.name().equals(user.getStatus())) {
            throw new ForbiddenException("账号已被禁用");
        }
        userService.updateLoginTime(user.getId());
        CurrentUser currentUser = userService.toCurrentUser(user); // 将用户实体转换为当前用户对象
        return AuthResponse.builder()
                .token(jwtService.generate(user.getId(), user.getRole())) // 生成JWT令牌
                .user(currentUser) // 将当前用户对象添加到响应中
                .build();
    }

    // 解析JWT令牌，返回当前用户信息
    public CurrentUser resolveToken(String token) {
        String userId = jwtService.parseUserId(token); // 从令牌中解析用户ID
        UserEntity user = userService.findById(userId); // 从数据库中查询用户
        if (user == null || !UserStatus.ACTIVE.name().equals(user.getStatus())) {
            throw new UnauthorizedException("账号不存在或不可用");
        }
        return userService.toCurrentUser(user); // 将用户实体转换为当前用户对象
    }
}
