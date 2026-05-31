package com.agent.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
//CurrentUser 是“当前登录用户”的简化对象，不包含密码等敏感信息
public class CurrentUser {
    private String id;
    private String username;
    private String name;
    private String gender;
    private String phone;
    private String department;
    private String role;
}
