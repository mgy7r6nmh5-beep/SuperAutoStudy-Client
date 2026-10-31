package com.tihai.startup;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 跨域来源解析。
 *
 * <p>仓库自带配置不再要求必须写 {@code cors.allow.origins}：
 * 未配置、空列表、空字符串或全空白项时回退到四个本机来源；
 * 显式配置则完全替换默认值，不与默认合并。
 *
 * <p>由于 {@code CorsConfig} 固定开启 {@code allowCredentials}，
 * 这里拒绝任何通配符来源（浏览器规范不允许 {@code *} 与凭据同时使用）。
 */
@Component
public class CorsOriginResolver {

    public static final List<String> DEFAULT_ORIGINS = Collections.unmodifiableList(Arrays.asList(
            "http://127.0.0.1:3030",
            "http://localhost:3030",
            "http://127.0.0.1:3080",
            "http://localhost:3080"
    ));

    private static final Pattern ORIGIN_PATTERN =
            Pattern.compile("^https?://([^/:\\s]+)(?::(\\d{1,5}))?$", Pattern.CASE_INSENSITIVE);

    public CorsOriginSet resolve(List<String> configured) {
        List<String> cleaned = new ArrayList<>();
        if (configured != null) {
            for (int i = 0; i < configured.size(); i++) {
                String raw = configured.get(i);
                if (raw == null) {
                    continue;
                }
                String item = raw.trim();
                if (item.isEmpty()) {
                    continue;
                }
                if (item.contains("*")) {
                    throw new CorsConfigException("跨域配置不允许通配符与凭据同时启用（第 "
                            + (i + 1) + " 项: " + item + "）");
                }
                Matcher matcher = ORIGIN_PATTERN.matcher(item);
                if (!matcher.matches()) {
                    throw new CorsConfigException("第 " + (i + 1)
                            + " 项跨域来源格式非法: " + item + "，期望 http(s)://主机[:端口]");
                }
                String portText = matcher.group(2);
                if (portText != null) {
                    int port = Integer.parseInt(portText);
                    if (port < 1 || port > 65535) {
                        throw new CorsConfigException("第 " + (i + 1)
                                + " 项跨域来源端口非法: " + item + "，端口需在 1~65535");
                    }
                }
                if (!cleaned.contains(item)) {
                    cleaned.add(item);
                }
            }
        }
        if (cleaned.isEmpty()) {
            return new CorsOriginSet(DEFAULT_ORIGINS, CorsOriginSet.OriginSource.DEFAULT);
        }
        return new CorsOriginSet(cleaned, CorsOriginSet.OriginSource.EXPLICIT);
    }
}