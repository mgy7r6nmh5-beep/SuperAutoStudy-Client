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
     * 当前章节id（不是序号）。
     *
     * <p>{@code currentChapterIndex} 存的是「平铺章节列表里的第几个」，超星一旦
     * 调整章节顺序（新增 / 删除 / 重排），同一个序号就指到别的章去了。
     * 存 id 才能保证续刷停在真正该停的那一章。
     *
     * <p>一轮扫完但课没刷完时，指针会被归零（见 SuperStarTaskServiceImpl 里的
     * 「顶到末尾就归零」），这时 id 也要一起清掉，否则下一轮又会按 id 找回
     * 原来的位置。所以同样用 IGNORED 策略，允许写 NULL。
     */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String chapterId;

    /**
     * 本轮没通过服务端校验的章节id，逗号分隔。
     *
     * <p>下一轮优先只重刷这些章，而不是把整门课从第 0 章线性重扫一遍。
     * 允许被清成 NULL（整轮全部通过时），所以要 IGNORED 策略。
     */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String failedChapters;

    /**
     * 本地上次成功上报的视频位置（秒）。断点续刷默认开启，重启后从这里继续。
     * 允许清空（切换到别的任务点时），所以用 IGNORED 策略。
     */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Long videoPositionSeconds;

    /**
     * 上面的位置属于哪个任务点（jobId）。任务点变了就只采用平台位置，避免串用。
     */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String videoPositionJobId;

    /**
     * 当前进度
     */
    private BigDecimal currentProgress;

    /**
     * 状态  0-待处理，1-进行中，2-队列中，3-历史值(已废弃)，4-已完成，5-异常
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

