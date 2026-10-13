package com.tihai.controller;

import com.tihai.dubbo.pojo.course.Course;
import com.tihai.common.R;
import com.tihai.domain.chaoxing.WkUser;
import com.tihai.dubbo.dto.WKUserDTO;
import com.tihai.enums.BizCodeEnum;
import com.tihai.service.superstar.SuperStarLoginService;
import com.tihai.utils.CourseUtil;
import ma.glasnost.orika.MapperFacade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

/**
 * @Copyright : DuanInnovator
 * @Description : 超星-学习通获取用户课程
 * @Author : DuanInnovator
 * @CreateTime : 2025/3/4
 * @Link : <a href="https://github.com/DuanInnovator/SuperAutotudy">...</a>
 **/
@RestController
@RequestMapping("/chaoxing")
public class SuperStarCourseController {

    @Autowired
    private SuperStarLoginService loginService;
    @Autowired
    private CourseUtil courseUtil;

    @Autowired
    private MapperFacade mapperFacade;


    /**
     * 超星-学习通获取用户课程
     * @param userDTO 用户信息
     * @return 课程列表
     */
    @PostMapping("/course")
    public R getCourseList(@RequestBody WKUserDTO userDTO) throws IOException {
        String cookies = loginService.login(mapperFacade.map(userDTO, WkUser.class),false);
        if(cookies!=null){
            // 用分叉会话：登录接口如果直接写共享单例的 cookie，会覆盖正在跑的
            // 刷课线程（它们也在用同一个 CourseUtil），轻则拉错账号数据，
            // 重则把别人的课程刷了。cookies 已经由 login() 落库，无需保留在单例上。
            CourseUtil session = courseUtil.forkSession();
            session.setCookies(cookies);
            session.setAccount(userDTO.getAccount());
            List<Course> courseList = session.getCourseList();
            return R.ok().put(courseList);

        }else {
            return R.error(BizCodeEnum.ACCOUNT_OR_PASSWORD_ERROR.getCode(), BizCodeEnum.ACCOUNT_OR_PASSWORD_ERROR.getMsg());
        }

    }



}

