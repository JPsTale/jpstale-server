package org.jpstale.server.web.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 开启 Sa-Token 的**注解鉴权**（否则 {@code @SaCheckLogin} / {@code @SaCheckRole} 全是摆设）。
 *
 * <p>
 * 注解鉴权在 Spring 里**不会自动生效** —— 需要注册 {@code SaInterceptor}
 * （或引入 {@code sa-token-spring-aop} 走 AOP）。本项目两者都没有，于是注解一直没拦任何东西。
 *
 * <p>
 * 实测证据（2026-09-21，真启服务）：
 * <ul>
 *   <li>匿名 {@code GET /api/admin/info}（方法上**只有** {@code @SaCheckRole("admin")}）→ <b>200</b></li>
 *   <li>匿名 {@code GET /api/admin/maps}（注解 **且** 方法体里显式 {@code StpUtil.checkLogin()}）→ <b>401</b></li>
 * </ul>
 * 即"看着有门、其实没门"。同样地，{@code ClanController} 四个只有 {@code @SaCheckLogin} 的端点
 * 当时是**匿名可调**的；本类生效后它们才真正要求登录。
 *
 * <p>
 * 这里用 {@code new SaInterceptor()}（**不传 auth 函数**）：它只做注解校验，不额外做路由级登录校验 ——
 * 哪些接口要登录、要什么角色，仍由各 Controller 上的注解就近声明，不在本类里另起一张路径表
 * （那正是"同一个判定写两份、改一处就漂"的来源）。
 *
 * <p>
 * ⚠ CORS 预检（OPTIONS）不受影响：Spring 对 preflight 走 {@code getCorsHandlerExecutionChain}，
 * 只套 CORS 拦截器，本拦截器不参与。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor()).addPathPatterns("/**");
    }
}
