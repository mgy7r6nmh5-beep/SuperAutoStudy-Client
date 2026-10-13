package com.tihai.constant;

/**
 * @Copyright : DuanInnovator
 * @Description : 任务类型常量
 * @Author : DuanInnovator
 * @CreateTime : 2025/3/1
 * @Link : <a href="https://github.com/DuanInnovator/SuperAutotudy">...</a>
 **/
public class JobTypeConstant {
    public static final String VIDEO = "video";
    public static final String DOCUMENT = "document";
    public static final String READ = "read";
    public static final String QUESTION = "workid";
    /**
     * 超链接任务点。
     * <p>实测（高等数学先修课 6.1 微积分前导 / 6.2 高等数学先修课）：attachment 是
     * {@code {"type":"hyperlink","job":true,"jobid":"1787536685282304",...}}，
     * 上报端点 {@code /ananas/job/hyperlink} 返回 {@code {"msg":"添加考核点成功","status":true}}。
     * 老实现没有这个类型，落到 switch 的 default 被当成「已完成」，
     * 于是超星一直挂着这几个待完成任务点，课程永远刷不完。
     */
    public static final String HYPERLINK = "hyperlink";
}

