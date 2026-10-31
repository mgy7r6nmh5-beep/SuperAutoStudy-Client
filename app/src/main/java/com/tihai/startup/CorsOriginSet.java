package com.tihai.startup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 解析完成的跨域来源集合。对外只读，避免运行期被修改。
 */
public final class CorsOriginSet {

    public enum OriginSource {
        /** 未配置 / 空 / 全空白，使用内置的本机默认来源。 */
        DEFAULT,
        /** 显式配置，完全替换默认值。 */
        EXPLICIT
    }

    private final List<String> origins;
    private final OriginSource source;

    public CorsOriginSet(List<String> origins, OriginSource source) {
        this.origins = Collections.unmodifiableList(new ArrayList<>(origins));
        this.source = source;
    }

    public List<String> getOrigins() {
        return origins;
    }

    public OriginSource getSource() {
        return source;
    }

    public int count() {
        return origins.size();
    }
}