package com.tihai.properties;

import com.tihai.constant.StudyPolicy;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

/**
 * @Copyright : DuanInnovator
 * @Description : 学习配置
 * @Author : DuanInnovator
 * @CreateTime : 2025/4/15
 * @Link : <a href="https://github.com/DuanInnovator/TiHaiWuYou-Admin/tree/mine-admin">...</a>
 *
 * <p>刷课参数已全部收回 {@link StudyPolicy}：本类不再持有任何可被 yml /
 * 命令行覆盖的字段，只负责在启动时打印固定策略摘要。
 **/
@Data
@Configuration
@ConfigurationProperties(prefix = "study.chaoxing")
public class StudyProperties {

    /**
     * 启动时把固定策略打到日志里。
     */
    @PostConstruct
    public void dumpEffective() {
        System.out.println("[StudyPolicy] 生效策略: " + StudyPolicy.describe());
    }
}