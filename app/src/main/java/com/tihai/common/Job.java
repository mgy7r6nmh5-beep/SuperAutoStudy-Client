package com.tihai.common;

import lombok.Data;

/**
 * @Copyright : DuanInnovator
 * @Description : 任务点集合
 * @Author : DuanInnovator
 * @CreateTime : 2025/3/1
 * @Link : <a href="https://github.com/DuanInnovator/SuperAutotudy">...</a>
 **/
@Data
public class Job {

    /**
     * 任务点标题
     */
    private String title;

    private String name;


    /**
     * 任务点类型
     */
    private String type;

    /**
     * 任务点id
     */
    private String id;

    /**
     * id
     */
    private String jobId;

    /**
     * objectId
     */
    private String objectId;

    /**
     * token
     */
    private String jToken;
    /**
     * mid
     */
    private String mid;

    /**
     * info
     */
    private String otherInfo;

    /**
     * enc
     */
    private String enc;

    /**
     * aid
     */
    private String aid;

    /**
     * 附件时长（秒）。超星新版播放器上报时必须带 &attDuration=，
     * 服务端用它和 playingTime 比对，缺了会「进度存下来了但不计分」。
     */
    private Integer attDuration;

    /**
     * 附件时长的签名串（cards 接口的 attachments[].attDurationEnc）
     */
    private String attDurationEnc;

    /**
     * 人脸抓拍签名串（cards 接口的 attachments[].videoFaceCaptureEnc）
     */
    private String videoFaceCaptureEnc;

    /**
     * 是否已通过服务端校验（cards 接口的 attachments[].isPassed）。
     *
     * <p>刷课主流程拿到的任务点已被过滤掉 {@code isPassed=true} 的（没必要重刷），
     * 所以那里恒为 false。只有客户端只读接口（ApiController）走
     * {@code Decode.decodeCourseCard(html, true)} 时才可能为 true。
     */
    private Boolean passed;

    /**
     * 已播放位置（毫秒，cards 接口的 attachments[].playTime）。仅只读接口使用。
     */
    private Long playTime;
}

