use novel_player;

-- Flyway V1：创建 NovelPlayer 的初始业务表。
-- database/init-mysql.sql 只负责建库和授权，表结构版本由 Flyway 管理。

CREATE TABLE novel_project (
    id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    source_text LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE novel_chapter (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    chapter_index INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    content LONGTEXT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_novel_chapter_project
        FOREIGN KEY (project_id) REFERENCES novel_project (id)
        ON DELETE CASCADE,
    UNIQUE KEY uk_novel_chapter_project_index (project_id, chapter_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

# 剧本生成任务表
CREATE TABLE generation_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_stage VARCHAR(64) NOT NULL,
    error_message TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_generation_job_project
        FOREIGN KEY (project_id) REFERENCES novel_project (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE generation_stage_result (
    id BIGINT NOT NULL AUTO_INCREMENT,
    job_id BIGINT NOT NULL,
    stage_name VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_hash VARCHAR(128) NULL,
    output_json LONGTEXT NULL,
    error_message TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_generation_stage_job
        FOREIGN KEY (job_id) REFERENCES generation_job (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE script_document (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    document_json LONGTEXT NOT NULL,
    yaml_content LONGTEXT NOT NULL,
    validation_status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_script_document_project
        FOREIGN KEY (project_id) REFERENCES novel_project (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
