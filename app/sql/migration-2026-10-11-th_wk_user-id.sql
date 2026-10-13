-- ============================================================================
-- 迁移脚本：给 th_wk_user 补主键 id
--
-- 背景
--   官方 sql/tihai.sql 建的 th_wk_user 表**没有 id 列**，但实体
--   com.tihai.domain.chaoxing.WkUser 上标了 @TableId(value = "id")。
--   MyBatis-Plus 于是生成
--       SELECT id, account, password, school_name, name, fid, cookies
--       FROM th_wk_user WHERE account = ?
--   MySQL 直接报：
--       java.sql.SQLSyntaxErrorException: Unknown column 'id' in 'field list'
--   表现是前端点「获取课程列表」永远返回：
--       {"msg":"系统异常","code":201,"subCode":404}
--   （404 是 GlobalException 兜底 catch(Exception) 的固定 subCode）
--
-- 适用
--   已经用旧版 SQL 建过表、且表里还没有 id 列的情况。
--   全新部署直接用修好的 sql/tihai.sql 即可，不需要跑这个。
--
-- 执行
--   mysql57\bin\mysql.exe -u root -pSuperAuto2026 tihai < migration-2026-10-11-th_wk_user-id.sql
-- ============================================================================

ALTER TABLE `th_wk_user`
  ADD COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;

-- 校验（应看到 id 在第一位、Key=PRI、Extra=auto_increment）
-- SHOW COLUMNS FROM th_wk_user;
