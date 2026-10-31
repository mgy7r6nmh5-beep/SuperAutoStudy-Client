package com.tihai.startup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单机模式判定结果，供启动日志与就绪声明使用。
 */
public final class StandaloneModeState {

    private final boolean standalone;
    private final boolean nacosConfigEnabled;
    private final boolean nacosDiscoveryEnabled;
    private final boolean dubboExportEnabled;
    private final List<String> ignoredLegacyKeys;

    public StandaloneModeState(boolean standalone,
                               boolean nacosConfigEnabled,
                               boolean nacosDiscoveryEnabled,
                               boolean dubboExportEnabled,
                               List<String> ignoredLegacyKeys) {
        this.standalone = standalone;
        this.nacosConfigEnabled = nacosConfigEnabled;
        this.nacosDiscoveryEnabled = nacosDiscoveryEnabled;
        this.dubboExportEnabled = dubboExportEnabled;
        this.ignoredLegacyKeys = Collections.unmodifiableList(
                ignoredLegacyKeys == null ? new ArrayList<>() : new ArrayList<>(ignoredLegacyKeys));
    }

    public boolean isStandalone() {
        return standalone;
    }

    public boolean isNacosConfigEnabled() {
        return nacosConfigEnabled;
    }

    public boolean isNacosDiscoveryEnabled() {
        return nacosDiscoveryEnabled;
    }

    public boolean isDubboExportEnabled() {
        return dubboExportEnabled;
    }

    public List<String> getIgnoredLegacyKeys() {
        return ignoredLegacyKeys;
    }

    public String onOff(boolean enabled) {
        return enabled ? "on" : "off";
    }

    public String describe() {
        return "standalone=" + standalone
                + " nacosConfig=" + onOff(nacosConfigEnabled)
                + " nacosDiscovery=" + onOff(nacosDiscoveryEnabled)
                + " dubboExport=" + onOff(dubboExportEnabled);
    }
}