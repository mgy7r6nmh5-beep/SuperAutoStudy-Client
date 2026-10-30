-- ============================================================
--  2026-10-30  章节级断点续刷
--
--  背景：current_chapter_index 存的是「平铺章节列表里的第几个」。
--  超星一旦调整章节顺序（增删 / 重排），同一个序号就指到别的章去了。
--  而且一轮跑完仍有待完成任务点时，只能把指针归零、从第 0 章线性重扫，
--  已经通过的章也要重新拉一遍 cards 页。
--
--  chapter_id      : 存真正的章节 id，续刷点不再随章节顺序漂移
--  failed_chapters : 本轮没通过服务端校验的章节 id（逗号分隔），
--                    下一轮优先只重刷这些章
--
--  老库执行本文件即可；新库直接用 th_wk_log.sql / tihai.sql 建表。
-- ============================================================

ALTER TABLE `th_wk_log`
  MODIFY COLUMN `current_chapter_index` int(11) NULL DEFAULT 0 COMMENT '当前章节序号（第几个，从0开始）';

ALTER TABLE `th_wk_log`
  ADD COLUMN `chapter_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL
      COMMENT '当前章节id（非序号，防章节顺序变化错位）' AFTER `current_chapter_index`,
  ADD COLUMN `failed_chapters` varchar(1024) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL
      COMMENT '本轮未通过校验的章节id，逗号分隔' AFTER `chapter_id`;
