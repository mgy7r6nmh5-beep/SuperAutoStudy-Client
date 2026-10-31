package com.tihai.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * @Copyright : DuanInnovator
 * @Description : 跨域允许地址配置
 * @Author : DuanInnovator
 * @CreateTime : 2025/3/5
 * @Link : <a href="https://github.com/DuanInnovator/SuperAutotudy">...</a>
 **/
@Configuration
@ConfigurationProperties(prefix = "cors.allow")
@Data
public class CorsProperties {
    /**
     * 允许跨域的域名。
     * 默认空集合：由 CorsOriginResolver 回退到本机四个默认来源，避免未配置时 NPE。
     */
    private List<String> origins = new ArrayList<>();
}

