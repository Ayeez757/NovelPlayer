-- NovelPlayer MySQL 初始化脚本。
-- 本地首次启动后端前执行一次。
-- 本脚本负责创建数据库、应用用户和授权。
-- 业务表结构由后端启动时的 Flyway 迁移脚本创建。

-- 创建应用数据库，统一使用 utf8mb4 以支持中文和表情等特殊字符。
CREATE DATABASE IF NOT EXISTS novel_player
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

-- localhost 用于本机开发，% 用于容器或局域网访问。
CREATE USER IF NOT EXISTS 'novel'@'localhost' IDENTIFIED BY 'novel_pass';
CREATE USER IF NOT EXISTS 'novel'@'%' IDENTIFIED BY 'novel_pass';

-- 只授权 novel_player 库，避免应用账号拥有过宽权限。
GRANT ALL PRIVILEGES ON novel_player.* TO 'novel'@'localhost';
GRANT ALL PRIVILEGES ON novel_player.* TO 'novel'@'%';

FLUSH PRIVILEGES;
