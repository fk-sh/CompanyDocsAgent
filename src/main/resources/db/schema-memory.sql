CREATE TABLE IF NOT EXISTS agent_sessions (
    id          VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '会话唯一标识',
    user_id     VARCHAR(64)  DEFAULT ''  COMMENT '关联用户ID',
    title       VARCHAR(512) DEFAULT ''  COMMENT '会话标题（自动提取首条用户问题）',
    status      VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '会话状态: ACTIVE/ARCHIVED',
    metadata    JSON         COMMENT '自定义元数据',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话表';


CREATE TABLE IF NOT EXISTS agent_messages (
    id           VARCHAR(64)   NOT NULL PRIMARY KEY COMMENT '消息唯一标识',
    session_id   VARCHAR(64)   NOT NULL COMMENT '所属会话ID',
    role         VARCHAR(16)   NOT NULL COMMENT '消息角色: SYSTEM/USER/ASSISTANT/TOOL',
    content      MEDIUMTEXT    NOT NULL COMMENT '消息正文',
    tool_call_id VARCHAR(128)  DEFAULT NULL COMMENT '工具调用ID（TOOL角色时）',
    token_count  INT           DEFAULT 0  COMMENT '预估Token数',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_session_id (session_id),
    INDEX idx_session_created (session_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息表';


CREATE TABLE IF NOT EXISTS users (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '用户ID',
    username        VARCHAR(64)  NOT NULL COMMENT '用户名',
    password_hash   VARCHAR(255) NOT NULL COMMENT '加密后的密码',
    name            VARCHAR(64)  NOT NULL COMMENT '姓名',
    gender          VARCHAR(16)  DEFAULT 'UNKNOWN' COMMENT '性别: MALE/FEMALE/UNKNOWN',
    phone           VARCHAR(32)  DEFAULT '' COMMENT '电话',
    department      VARCHAR(128) NOT NULL COMMENT '所属部门',
    role            VARCHAR(32)  NOT NULL DEFAULT 'USER' COMMENT '角色: USER/ADMIN',
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/DISABLED/DELETED',
    last_login_at   DATETIME     DEFAULT NULL COMMENT '最后登录时间',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE INDEX uk_username (username),
    UNIQUE INDEX uk_phone (phone),
    INDEX idx_department (department),
    INDEX idx_role (role),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户表';


CREATE TABLE IF NOT EXISTS documents (
    id              VARCHAR(64)   NOT NULL PRIMARY KEY COMMENT '文档ID',
    file_name       VARCHAR(512)  NOT NULL COMMENT '文件名',
    file_type       VARCHAR(32)   DEFAULT '' COMMENT '文件类型',
    file_size       BIGINT        DEFAULT 0 COMMENT '文件大小',
    file_path       VARCHAR(1024) DEFAULT '' COMMENT '文件存储路径',
    uploader_id     VARCHAR(64)   NOT NULL COMMENT '上传人ID',
    uploader_name   VARCHAR(64)   DEFAULT '' COMMENT '上传人姓名',
    department      VARCHAR(128)  NOT NULL COMMENT '上传人部门',
    visibility      VARCHAR(32)   NOT NULL COMMENT 'DEPARTMENT/COMPANY',
    status          VARCHAR(32)   NOT NULL DEFAULT 'PROCESSING' COMMENT 'PROCESSING/READY/FAILED/DISABLED/DELETED',
    chunk_count     INT           DEFAULT 0 COMMENT '切片数量',
    task_id         VARCHAR(64)   DEFAULT '' COMMENT '摄入任务ID',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_uploader_id (uploader_id),
    INDEX idx_department (department),
    INDEX idx_visibility (visibility),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档元数据表';
