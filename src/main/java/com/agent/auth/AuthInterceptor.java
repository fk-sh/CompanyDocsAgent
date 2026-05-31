package com.agent.auth;

import com.agent.user.UserEntity;
import com.agent.user.UserService;
import com.agent.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtService jwtService;
    private final UserService userService;

    public AuthInterceptor(JwtService jwtService, UserService userService) {
        this.jwtService = jwtService;
        this.userService = userService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return true;
        }

        String token = authorization.substring(7);// 提取Bearer 后的令牌
        String userId = jwtService.parseUserId(token);// 解析用户ID

        UserEntity user = userService.findById(userId);// 从数据库查询用户
        if (user == null || UserStatus.DELETED.name().equals(user.getStatus())) {
            return true;
        }

        CurrentUser currentUser = userService.toCurrentUser(user);// 转换为 CurrentUser 对象
        CurrentUserHolder.set(currentUser);// 设置当前用户
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        CurrentUserHolder.clear();// 清除当前用户
    }
}
