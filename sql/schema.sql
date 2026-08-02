-- =============================================
-- 福洛游戏 (FuluoGame) 数据库建表语句
-- 数据库名: fuluogame
-- =============================================

CREATE DATABASE IF NOT EXISTS fuluogame
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE fuluogame;

-- =============================================
-- 1. 用户表
-- =============================================
CREATE TABLE IF NOT EXISTS `user` (
    `id`          BIGINT       NOT NULL COMMENT '用户ID',
    `phone`       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    `password`    VARCHAR(128) DEFAULT NULL COMMENT '密码（BCrypt加密）',
    `username`    VARCHAR(64)  DEFAULT NULL COMMENT '用户名',
    `avatar`      VARCHAR(512) DEFAULT NULL COMMENT '头像URL',
    `gender`      TINYINT      DEFAULT 0  COMMENT '性别: 0=保密 1=男 2=女',
    `birthday`    DATE         DEFAULT NULL COMMENT '生日',
    `status`      TINYINT      DEFAULT 0  COMMENT '状态: 0=正常 1=禁用',
    `role`        VARCHAR(20)  DEFAULT 'user' COMMENT '角色: user=普通用户 admin=管理员',
    `posts_public`     TINYINT DEFAULT 1 COMMENT '帖子公开: 1=是 0=否',
    `liked_public`     TINYINT DEFAULT 1 COMMENT '点赞公开',
    `following_public` TINYINT DEFAULT 1 COMMENT '关注列表公开',
    `followers_public` TINYINT DEFAULT 1 COMMENT '粉丝列表公开',

    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';
-- =============================================
-- 2. 帖子表
-- =============================================
CREATE TABLE IF NOT EXISTS `post` (
    `id`          BIGINT       NOT NULL COMMENT '帖子ID',
    `user_id`     BIGINT       NOT NULL COMMENT '作者ID',
    `title`       VARCHAR(200) NOT NULL COMMENT '帖子标题',
    `content`     TEXT         DEFAULT NULL COMMENT '帖子内容',
    `images`      VARCHAR(2048) DEFAULT NULL COMMENT '图片URL（JSON数组）',
    `likes`       INT          DEFAULT 0  COMMENT '点赞数',
    `comments`    INT          DEFAULT 0  COMMENT '评论数',
    `views`       INT          DEFAULT 0  COMMENT '浏览数',
    `shares`      INT          DEFAULT 0  COMMENT '分享数',
    `topic_names` VARCHAR(512) DEFAULT NULL COMMENT '话题名称JSON数组',
    `status`      TINYINT      DEFAULT 0  COMMENT '状态: 0=正常 1=删除 2=审核中 3=拒绝',
    `moderation_desc` VARCHAR(255) DEFAULT NULL COMMENT '审核拒绝原因',
    `moderation_words` VARCHAR(255) DEFAULT NULL COMMENT '审核命中敏感词',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_create_time` (`create_time`),
    KEY `idx_status_create` (`status`, `create_time`),
    KEY `idx_user_status_create` (`user_id`, `status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子表';

-- =============================================
-- 3. 评论表
-- =============================================
CREATE TABLE IF NOT EXISTS `comment` (
    `id`          BIGINT    NOT NULL COMMENT '评论ID',
    `post_id`     BIGINT    NOT NULL COMMENT '帖子ID',
    `user_id`     BIGINT    NOT NULL COMMENT '评论者ID',
    `content`     TEXT      NOT NULL COMMENT '评论内容',
    `parent_id`   BIGINT    DEFAULT 0  COMMENT '父评论ID: 0=一级评论',
    `root_id`     BIGINT    DEFAULT 0  COMMENT '根评论ID: 0=一级评论, >0=归属某根评论',
    `likes`       INT       DEFAULT 0  COMMENT '点赞数',
    `status`      TINYINT   DEFAULT 0  COMMENT '状态: 0=正常 1=删除 2=审核中 3=拒绝',
    `moderation_desc` VARCHAR(255) DEFAULT NULL COMMENT '审核拒绝原因',
    `moderation_words` VARCHAR(255) DEFAULT NULL COMMENT '审核命中敏感词',
    `create_time` DATETIME  DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_post_id` (`post_id`),
    KEY `idx_root` (`root_id`, `status`, `create_time`),
    KEY `idx_user_create` (`user_id`, `create_time`),
    KEY `idx_status_create` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论表';

-- =============================================
-- 4. 点赞表（表名 likes 避免 MySQL 保留字冲突）
-- =============================================
CREATE TABLE IF NOT EXISTS `likes` (
    `id`          BIGINT    NOT NULL COMMENT '点赞ID',
    `user_id`     BIGINT    NOT NULL COMMENT '用户ID',
    `target_type` TINYINT   NOT NULL COMMENT '目标类型: 1=帖子 2=评论',
    `target_id`   BIGINT    NOT NULL COMMENT '目标ID',
    `status`      TINYINT   DEFAULT 1  COMMENT '状态: 1=已点赞 0=已取消',
    `create_time` DATETIME  DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_target` (`user_id`, `target_type`, `target_id`),
    KEY `idx_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='点赞表';

-- =============================================
-- 5. 收藏表
-- =============================================
CREATE TABLE IF NOT EXISTS `favorite` (
    `id`          BIGINT    NOT NULL COMMENT '收藏ID',
    `user_id`     BIGINT    NOT NULL COMMENT '用户ID',
    `post_id`     BIGINT    NOT NULL COMMENT '帖子ID',
    `status`      TINYINT   DEFAULT 1  COMMENT '状态: 1=已收藏 0=已取消',
    `create_time` DATETIME  DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_post` (`user_id`, `post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收藏表';

-- =============================================
-- 6. 关注表
-- =============================================
CREATE TABLE IF NOT EXISTS `follow` (
    `id`          BIGINT    NOT NULL COMMENT '关注ID',
    `follower_id` BIGINT    NOT NULL COMMENT '关注者ID',
    `followed_id` BIGINT    NOT NULL COMMENT '被关注者ID',
    `status`      TINYINT   DEFAULT 1  COMMENT '状态: 1=已关注 0=已取关',
    `create_time` DATETIME  DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_follow` (`follower_id`, `followed_id`),
    KEY `idx_followed_id` (`followed_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='关注表';

-- =============================================
-- 7. 用户统计表（预计算，增量更新，避免跨表 COUNT）
-- =============================================
CREATE TABLE IF NOT EXISTS `user_stat` (
    `user_id`   BIGINT NOT NULL COMMENT '用户ID',
    `posts`     BIGINT DEFAULT 0 COMMENT '帖子数',
    `following` BIGINT DEFAULT 0 COMMENT '关注数',
    `followers` BIGINT DEFAULT 0 COMMENT '粉丝数',
    `likes`     BIGINT DEFAULT 0 COMMENT '获赞数',
    PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户统计表';

-- =============================================
-- 8. 话题表
-- =============================================
CREATE TABLE IF NOT EXISTS `topic` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '话题ID',
    `name` VARCHAR(50) NOT NULL COMMENT '话题名称',
    `total_views` BIGINT DEFAULT 0 COMMENT '总浏览数',
    `total_posts` BIGINT DEFAULT 0 COMMENT '讨论数',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_total_views` (`total_views`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='话题表';

-- =============================================
-- 9. 帖子-话题关联表
-- =============================================
CREATE TABLE IF NOT EXISTS `post_topic` (
    `id`       BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `post_id`  BIGINT NOT NULL COMMENT '帖子ID',
    `topic_id` BIGINT NOT NULL COMMENT '话题ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_post_topic` (`post_id`, `topic_id`),
    KEY `idx_topic_id` (`topic_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子话题关联表';

-- =============================================
-- 10. 轮播图表
-- =============================================
CREATE TABLE IF NOT EXISTS `banner` (
    `id`          BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `image_url`   VARCHAR(512) NOT NULL COMMENT '轮播图URL',
    `link_url`    VARCHAR(512) DEFAULT '' COMMENT '跳转链接',
    `sort`        INT DEFAULT 0 COMMENT '排序（越小越靠前）',
    `status`      TINYINT DEFAULT 1 COMMENT '状态: 1=启用 0=禁用',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_status_sort` (`status`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='轮播图表';

-- =============================================
-- 11. 公告表
-- =============================================
CREATE TABLE IF NOT EXISTS `announcement` (
    `id`          BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`       VARCHAR(200) DEFAULT NULL COMMENT '公告标题',
    `content`     VARCHAR(2000) NOT NULL COMMENT '公告内容',
    `image_url`   VARCHAR(512) DEFAULT '' COMMENT '图片URL',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公告表';
-- Run once against the shared fuluogame database before starting fuluo-notification.
CREATE TABLE IF NOT EXISTS `notification` (
                                              `id`            BIGINT       NOT NULL COMMENT '通知ID',
                                              `event_id`      VARCHAR(96)  NOT NULL COMMENT 'MQ事件幂等ID',
                                              `recipient_id`  BIGINT       NOT NULL COMMENT '接收者ID',
                                              `actor_id`      BIGINT       DEFAULT NULL COMMENT '触发者ID，系统通知为空',
                                              `type`          VARCHAR(32)  NOT NULL COMMENT 'COMMENT/REPLY/POST_LIKE/COMMENT_LIKE/FOLLOW/SYSTEM',
                                              `target_type`   VARCHAR(16)  DEFAULT NULL COMMENT 'POST/COMMENT/USER/SYSTEM',
                                              `target_id`     BIGINT       DEFAULT NULL COMMENT '目标ID',
                                              `post_id`       BIGINT       DEFAULT NULL COMMENT '关联帖子ID',
                                              `actor_name`    VARCHAR(64)  DEFAULT NULL COMMENT '触发者名称快照',
                                              `actor_avatar`  VARCHAR(512) DEFAULT NULL COMMENT '触发者头像快照',
                                              `target_title`  VARCHAR(200) DEFAULT NULL COMMENT '目标标题快照',
                                              `content`       VARCHAR(500) DEFAULT NULL COMMENT '评论或系统消息摘要',
                                              `is_read`       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已读',
                                              `read_time`     DATETIME     DEFAULT NULL COMMENT '已读时间',
                                              `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                              PRIMARY KEY (`id`),
                                              UNIQUE KEY `uk_notification_event` (`event_id`),
                                              KEY `idx_notification_recipient_time` (`recipient_id`, `create_time`),
                                              KEY `idx_notification_recipient_type_time` (`recipient_id`, `type`, `create_time`),
                                              KEY `idx_notification_recipient_unread` (`recipient_id`, `is_read`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户通知表';
