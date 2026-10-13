package com.tihai.domain.chaoxing;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * @Copyright : DuanInnovator
 * @Description : 超星学习通-日志信息
 * @Author : DuanInnovator
 * @CreateTime : 2025/3/1
 * @Link : <a href="https://github.com/DuanInnovator/SuperAutotudy">...</a>
 **/
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("th_wk_log")
public class SuperStarLog {
    /**
     * id
     */
    private Long id;

    /**
     * 登录账号
     */
    private String loginAccount;

    /**
     * 课程名称
     */
    private String courseName;

    /**
     * 当前任务点
     */
    private String currentJob;

    /**
     * 当前任务点id
     */
    private Integer currentJobId;

    /**
     * 当前章节索引
     */
    private Integer currentChapterIndex;

    /**
     * 当前进度
     */
    private BigDecimal currentProgress;

    /**
     * 状态  0-待处理，1-进行中，2-队列中，3-考试中，4-已完成，5-异常
     */
    private Integer status;

    /**
     * 错误信息
     *
     * <p>FIX(2026-10-11)：这三个字段必须允许被更新成 NULL。
     * MyBatis-Plus 默认的字段策略是 NOT_NULL —— null 字段会被直接从 UPDATE 的
     * SET 子句里剔除，于是 {@code log.setErrorMessage(null)} 根本清不掉库里的旧值。
     * 后果：上一轮的「未刷完：…」「超星返回 9010…」会一直挂在日志行上，
     * 而控制台是按 error_message 判状态的，于是一轮新任务跑起来后
     * 界面上还显示着上一轮的结论（永远看不到「进行中」，成功了也还显示「未刷完」）。
     */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String errorMessage;

    /**
     * 任务开始时间
     */
    private LocalDateTime startTime;

    /**
     * 任务结束时间
     *
     * <p>同上：一轮新任务开始时要把 end_time 清回 NULL（表示「还在跑」）。
     */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private LocalDateTime endTime;

    /**
     * 备注
     *
     * <p>同上，允许清成 NULL。
     */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String remark;

    /**
     * 机器码
     */
    private String machineNum;
}

