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


CREATE TABLE IF NOT EXISTS user_profiles (
    id          VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '主键',
    user_id     VARCHAR(64)  NOT NULL COMMENT '用户ID',
    preferences JSON         COMMENT '用户偏好(JSON): {city, language, interests, ...}',
    raw_notes   TEXT         COMMENT '原始提取记录，供LLM增量合并',
    version     INT          NOT NULL DEFAULT 1 COMMENT '版本号，每次更新+1',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE INDEX uk_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户画像表';
