package com.hermes.tvlive;

/** 一个频道：名称 + 播放地址 + 分组 + 台标。 */
public class Channel {
    public String name;
    public String url;
    public String group;
    public String logo;

    public Channel(String name, String url, String group, String logo) {
        this.name = name;
        this.url = url;
        this.group = group == null || group.isEmpty() ? "未分组" : group;
        this.logo = logo;
    }

    @Override
    public String toString() {
        return name;
    }
}
