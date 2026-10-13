

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for th_wk_user
-- ----------------------------
DROP TABLE IF EXISTS `th_wk_user`;
CREATE TABLE `th_wk_user`  (
  -- FIX: 原始 SQL 漏了 id 列, 而实体 WkUser 上是 @TableId("id"),
  -- MyBatis-Plus 生成 SELECT id, ... 会报 Unknown column 'id' in 'field list'
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `account` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL COMMENT '账号',
  `password` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL COMMENT '密码',
  `school_name` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL COMMENT '学校名称',
  `name` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL COMMENT '姓名',
  `fid` bigint(20) NULL DEFAULT NULL COMMENT '学校id',
  `cookies` text CHARACTER SET utf8 COLLATE utf8_general_ci NULL COMMENT '用户当前cookie',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8 COLLATE = utf8_general_ci COMMENT = '网课-用户信息表' ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
