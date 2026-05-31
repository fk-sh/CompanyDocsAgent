package com.agent.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("users")
public class UserEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String username;

    @TableField("password_hash")
    private String passwordHash;

    private String name;

    private String gender;

    private String phone;

    private String department;

    private String role;

    private String status;

    @TableField("last_login_at")
    private LocalDateTime lastLoginAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
