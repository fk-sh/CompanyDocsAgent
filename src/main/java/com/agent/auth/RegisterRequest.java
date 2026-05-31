package com.agent.auth;

import lombok.Data;

@Data
public class RegisterRequest {
    private String username;
    private String password;
    private String name;
    private String gender;
    private String phone;
    private String department;
}
