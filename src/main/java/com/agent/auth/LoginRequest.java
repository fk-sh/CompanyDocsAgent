package com.agent.auth;

import lombok.Data;

@Data
public class LoginRequest {
    private String account;
    private String username;
    private String password;
}
