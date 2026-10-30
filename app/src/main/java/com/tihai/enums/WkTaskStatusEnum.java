package com.tihai.enums;

/**
 * @Copyright : DuanInnovator
 * @Description : 网课任务状态枚举
 * @Author : DuanInnovator
 * @CreateTime : 2025/3/1
 * @Link :<a href="https://github.com/DuanInnovator/SuperAutoStudy">...</a>
 **/
public enum WkTaskStatusEnum {
    PENDING(0,"待处理"),
    PROCESSING(1,"进行中"),
    QUEUE(2,"队列中"),
    // 3 = 历史值。考试/答题功能已整体移除，当前代码不会再产生这个状态；
    // 保留枚举是为了让 StuckTaskRecover 仍能识别旧库里残留的 3 并重置为待处理。
    EXAM(3,"考试中"),
    FINISHED(4,"已完成"),
    ABNORMAL(5,"异常"),
    PAUSED(6,"暂停中");

    private final Integer code;
    private final String status;

    WkTaskStatusEnum(Integer code,String status){
        this.code = code;
        this.status = status;
    }

    public Integer getCode() {
        return code;
    }

    public String getStatus() {
        return status;
    }

}

