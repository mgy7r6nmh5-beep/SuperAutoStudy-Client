package com.tihai.service.superstar.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tihai.domain.chaoxing.SuperStarLog;
import com.tihai.mapper.SuperStarLogMapper;
import com.tihai.service.superstar.SuperStarLogService;
import org.springframework.stereotype.Service;

/**
 * @Copyright : DuanInnovator
 * @Description : 超星学习通-日志服务实现类
 * @Author : DuanInnovator
 * @CreateTime : 2025/3/1
 * @Link : <a href="https://github.com/DuanInnovator/SuperAutotudy">...</a>
 **/
@Service
public class SuperStarLogServiceImpl extends ServiceImpl<SuperStarLogMapper, SuperStarLog> implements SuperStarLogService {

    /**
     * 保存日志
     * @param superStartLog 日志信息
     */
    @Override
    public void saveLog(SuperStarLog superStartLog) {
        // FIX: 原实现是无条件 setId(null) 再 save()，即"每次调用都 INSERT 一条新行"。
        // 但 CourseUtil 在**每个视频刷完**时都用它来写进度，于是一门课刷 N 个视频
        // 就会堆出 N 条重复日志行（实测「线性代数」4 条、「人工智能通识I」2 条），
        // 表会随刷课量无限膨胀。
        // 改成 saveOrUpdate：id 为空 → 插入（登录日志走这条）；id 有值 → 更新原行。
        this.saveOrUpdate(superStartLog);
    }

    /**
     * 更新日志
     * @param superStartLog 日志信息
     */
    @Override
    public void updateLog(SuperStarLog superStartLog) {
        this.updateById(superStartLog);
    }

    /**
     * 根据账号和课程名获取最新的一条日志
     * @param account 账号
     * @param courseName 课程
     * @return 最新一条日志
     */
    @Override
    public SuperStarLog getLatestLogByLoginAccount(String account, String courseName) {
        LambdaQueryWrapper<SuperStarLog> superStartLogLambdaQueryWrapper = new LambdaQueryWrapper<>();
        superStartLogLambdaQueryWrapper.eq(SuperStarLog::getLoginAccount, account);
        superStartLogLambdaQueryWrapper.eq(SuperStarLog::getCourseName, courseName);
        superStartLogLambdaQueryWrapper.orderByDesc(SuperStarLog::getId).last("limit 1");

        return this.getOne(superStartLogLambdaQueryWrapper,false);

    }


}

