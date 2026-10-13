package com.tihai.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * @Copyright : DuanInnovator
 * @Description : 学习配置
 * @Author : DuanInnovator
 * @CreateTime : 2025/4/15
 * @Link : <a href="https://github.com/DuanInnovator/TiHaiWuYou-Admin/tree/mine-admin">...</a>
 **/
@Data
@Configuration
@ConfigurationProperties(prefix = "study.chaoxing")
public class StudyProperties {
    /** 学习速度 **/
    private Integer speed;

    /**
     * 视频上报的墙钟间隔（毫秒）——两条上报之间真实等多久。
     *
     * <p>背景（2026-10-11 实测）：超星服务端有<b>按账号</b>的频率风控。
     * 之前实现「100 毫秒连发 5000 条」直接把账号打到风控，
     * log 接口开始返回 HTTP 202 + 「【9010】操作异常，请输入图片中的验证码」，
     * 之后所有上报全被拒（视频进度存下来了但永远不计分）。
     * 实测 3 秒一条时第 4 条就被拦。所以这里必须给足间隔。
     */
    private Integer videoReportPaceMs = 10000;

    /**
     * 视频上报的播放进度步长（秒）。
     *
     * <p>超星播放器自己的节奏来自 cards 页 defaults 的 reportTimeInterval（实测 = 60），
     * 运行时优先用服务端下发的值，这个只做兜底。
     */
    private Integer videoReportStepSeconds = 60;

    /** 单个视频最多重复「完整播放」几遍。服务端偶尔要第二遍才落分。 */
    private Integer videoMaxRounds = 3;

    /**
     * 「只刷视频」模式的课程 ID 列表。
     *
     * <p>命中该列表的课程，只处理 {@code video} 类型任务点；
     * {@code document / read / workid(做题/章节测验) / hyperlink} 全部跳过并打日志。
     * 只影响列表内的课程，其它课程行为不变。
     */
    private List<String> videoOnlyCourseIds = new ArrayList<>();

    /**
     * 全局跳过的任务点类型（对所有课程生效）。
     *
     * <p>例：填 {@code ["workid"]} 表示所有课程都不刷「做题 / 章节测验」
     * （{@code JobTypeConstant.QUESTION = "workid"}），只刷视频 / 文档等其它任务点。
     */
    private List<String> skipJobTypes = new ArrayList<>();

    /**
     * 启动时把最终生效的值打到日志里。
     *
     * <p>这些值有三个来源（yml → 客户端命令行覆盖 → 代码默认），优先级不好肉眼判断。
     * 之前踩过一次坑：客户端把列表清空后以为已经生效，其实 yml 里的值还在。
     * 打一行出来，谁覆盖谁一目了然。
     */
    @PostConstruct
    public void dumpEffective() {
        System.out.println("[StudyProperties] 生效配置: video-report-pace-ms=" + videoReportPaceMs
                + " step-seconds=" + videoReportStepSeconds
                + " max-rounds=" + videoMaxRounds
                + " skip-job-types=" + skipJobTypes
                + " video-only-course-ids=" + videoOnlyCourseIds);
    }
}

