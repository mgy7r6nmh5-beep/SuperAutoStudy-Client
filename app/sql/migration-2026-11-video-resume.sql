-- 老库升级：th_wk_log 增加视频断点两列（只跑一次）
-- 新库无需执行：app/sql/th_wk_log.sql 与 tihai.sql 已包含这两列。
-- 客户端启动时也会自动补列（client/server.py 的 SCHEMA_PATCHES），
-- 本脚本供手工迁移或核查使用。

ALTER TABLE th_wk_log
    ADD COLUMN video_position_seconds int(11) NULL DEFAULT NULL
        COMMENT '本地上次成功上报的视频位置（秒）' AFTER failed_chapters,
    ADD COLUMN video_position_job_id varchar(64) NULL DEFAULT NULL
        COMMENT '位置所属任务点 jobId' AFTER video_position_seconds;