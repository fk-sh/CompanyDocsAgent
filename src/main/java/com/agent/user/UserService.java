package com.agent.user;

import com.agent.auth.CurrentUser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class UserService {

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public UserEntity findById(String id) {
        return userMapper.selectById(id);
    }

    public UserEntity findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, username)
                .last("LIMIT 1"));
    }

    public UserEntity findByAccount(String account) {
        if (account == null || account.isBlank()) {
            return null;
        }
        return userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, account)
                .or()
                .eq(UserEntity::getPhone, account)
                .last("LIMIT 1"));
    }

    public List<UserEntity> list(int limit, int offset) {
        return userMapper.selectList(new LambdaQueryWrapper<UserEntity>()
                .ne(UserEntity::getStatus, UserStatus.DELETED.name())
                .orderByDesc(UserEntity::getCreatedAt)
                .last("LIMIT " + Math.max(1, limit) + " OFFSET " + Math.max(0, offset)));
    }

    public long countByRole(UserRole role) {
        return userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getRole, role.name()));
    }

    public UserEntity create(UserEntity user) {
        user.setStatus(user.getStatus() == null ? UserStatus.ACTIVE.name() : user.getStatus());
        user.setRole(user.getRole() == null ? UserRole.USER.name() : user.getRole());
        user.setGender(user.getGender() == null ? "UNKNOWN" : user.getGender());
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        return user;
    }

    public void updateLoginTime(String id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setLastLoginAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
    }

    public CurrentUser toCurrentUser(UserEntity user) {
        if (user == null) {
            return null;
        }
        return CurrentUser.builder()
                .id(user.getId())
                .username(user.getUsername())
                .name(user.getName())
                .gender(user.getGender())
                .phone(user.getPhone())
                .department(user.getDepartment())
                .role(user.getRole())
                .build();
    }
}
