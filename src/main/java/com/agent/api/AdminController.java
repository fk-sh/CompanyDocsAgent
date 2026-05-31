package com.agent.api;

import com.agent.auth.CurrentUser;
import com.agent.auth.CurrentUserHolder;
import com.agent.auth.PasswordService;
import com.agent.document.DocumentEntity;
import com.agent.document.DocumentService;
import com.agent.document.ManagedDocumentStatus;
import com.agent.user.UserEntity;
import com.agent.user.UserMapper;
import com.agent.user.UserRole;
import com.agent.user.UserService;
import com.agent.user.UserStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final UserService userService;
    private final UserMapper userMapper;
    private final DocumentService documentService;
    private final PasswordService passwordService;

    public AdminController(UserService userService, UserMapper userMapper,
                           DocumentService documentService, PasswordService passwordService) {
        this.userService = userService;
        this.userMapper = userMapper;
        this.documentService = documentService;
        this.passwordService = passwordService;
    }

    private void requireAdmin() {
        CurrentUser user = CurrentUserHolder.require();
        if (!UserRole.ADMIN.name().equals(user.getRole())) {
            throw new com.agent.auth.ForbiddenException("需要管理员权限");
        }
    }

    @GetMapping("/users")
    public List<Map<String, Object>> listUsers(@RequestParam(defaultValue = "50") int limit,
                                                @RequestParam(defaultValue = "0") int offset) {
        requireAdmin();
        log.info("GET /admin/users limit={}, offset={}", limit, offset);
        return userService.list(limit, offset).stream()
                .map(this::userToMap)
                .toList();
    }

    @GetMapping("/users/{id}")
    public Map<String, Object> getUser(@PathVariable String id) {
        requireAdmin();
        UserEntity user = userService.findById(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return userToMap(user);
    }

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody Map<String, Object> body) {
        requireAdmin();
        UserEntity user = new UserEntity();
        user.setUsername((String) body.get("username"));
        user.setName((String) body.get("name"));
        user.setGender((String) body.getOrDefault("gender", "UNKNOWN"));
        user.setPhone((String) body.getOrDefault("phone", ""));
        user.setDepartment((String) body.get("department"));
        user.setRole((String) body.getOrDefault("role", UserRole.USER.name()));
        user.setPasswordHash(passwordService.hash((String) body.getOrDefault("password", "123456")));
        user.setStatus(UserStatus.ACTIVE.name());
        UserEntity created = userService.create(user);
        log.info("Admin created user: id={}, username={}", created.getId(), created.getUsername());
        return userToMap(created);
    }

    @PutMapping("/users/{id}")
    public Map<String, Object> updateUser(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireAdmin();
        UserEntity existing = userService.findById(id);
        if (existing == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        UserEntity update = new UserEntity();
        update.setId(id);
        if (body.containsKey("name")) update.setName((String) body.get("name"));
        if (body.containsKey("gender")) update.setGender((String) body.get("gender"));
        if (body.containsKey("phone")) update.setPhone((String) body.get("phone"));
        if (body.containsKey("department")) update.setDepartment((String) body.get("department"));
        if (body.containsKey("role")) update.setRole((String) body.get("role"));
        if (body.containsKey("password")) {
            update.setPasswordHash(passwordService.hash((String) body.get("password")));
        }
        update.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(update);
        log.info("Admin updated user: id={}", id);
        return userToMap(userService.findById(id));
    }

    @PutMapping("/users/{id}/status")
    public Map<String, Object> updateUserStatus(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireAdmin();
        UserEntity existing = userService.findById(id);
        if (existing == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        String status = (String) body.get("status");
        if (status == null) {
            throw new IllegalArgumentException("status 不能为空");
        }
        UserEntity update = new UserEntity();
        update.setId(id);
        update.setStatus(status);
        update.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(update);
        log.info("Admin updated user status: id={}, status={}", id, status);
        return userToMap(userService.findById(id));
    }

    @DeleteMapping("/users/{id}")
    public Map<String, String> deleteUser(@PathVariable String id) {
        requireAdmin();
        UserEntity existing = userService.findById(id);
        if (existing == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        UserEntity update = new UserEntity();
        update.setId(id);
        update.setStatus(UserStatus.DELETED.name());
        update.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(update);
        log.info("Admin deleted user: id={}", id);
        return Map.of("status", "deleted", "userId", id);
    }

    @GetMapping("/documents")
    public List<Map<String, Object>> listAllDocuments(@RequestParam(defaultValue = "50") int limit,
                                                       @RequestParam(defaultValue = "0") int offset) {
        requireAdmin();
        log.info("GET /admin/documents limit={}, offset={}", limit, offset);
        return documentService.listAll(limit, offset).stream()
                .map(this::documentToMap)
                .toList();
    }

    @PutMapping("/documents/{id}/status")
    public Map<String, Object> updateDocumentStatus(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireAdmin();
        DocumentEntity doc = documentService.findById(id);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在");
        }
        String status = (String) body.get("status");
        if (status == null) {
            throw new IllegalArgumentException("status 不能为空");
        }
        ManagedDocumentStatus managedStatus;
        try {
            managedStatus = ManagedDocumentStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的状态: " + status);
        }
        documentService.updateStatus(id, managedStatus, null);
        log.info("Admin updated document status: id={}, status={}", id, status);
        return documentToMap(documentService.findById(id));
    }

    @DeleteMapping("/documents/{id}")
    public Map<String, String> deleteDocument(@PathVariable String id) {
        requireAdmin();
        DocumentEntity doc = documentService.findById(id);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在");
        }
        documentService.updateStatus(id, ManagedDocumentStatus.DELETED, null);
        log.info("Admin deleted document: id={}", id);
        return Map.of("status", "deleted", "documentId", id);
    }

    private Map<String, Object> userToMap(UserEntity user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("username", user.getUsername());
        map.put("name", user.getName());
        map.put("gender", user.getGender());
        map.put("phone", user.getPhone());
        map.put("department", user.getDepartment());
        map.put("role", user.getRole());
        map.put("status", user.getStatus());
        map.put("lastLoginAt", user.getLastLoginAt() != null ? user.getLastLoginAt().toString() : "");
        map.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : "");
        map.put("updatedAt", user.getUpdatedAt() != null ? user.getUpdatedAt().toString() : "");
        return map;
    }

    private Map<String, Object> documentToMap(DocumentEntity doc) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", doc.getId());
        map.put("fileName", doc.getFileName());
        map.put("fileType", doc.getFileType());
        map.put("fileSize", doc.getFileSize());
        map.put("uploaderId", doc.getUploaderId());
        map.put("uploaderName", doc.getUploaderName());
        map.put("department", doc.getDepartment());
        map.put("visibility", doc.getVisibility());
        map.put("status", doc.getStatus());
        map.put("chunkCount", doc.getChunkCount());
        map.put("createdAt", doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : "");
        map.put("updatedAt", doc.getUpdatedAt() != null ? doc.getUpdatedAt().toString() : "");
        return map;
    }
}