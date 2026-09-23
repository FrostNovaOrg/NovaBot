package org.frostnova.nova.core.config.ui;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 控制台关掉之后，把 {@code /config} 下的请求一律打成 404
 *
 * <h2>为什么还要这道</h2>
 * 控制台开着时，{@code ConfigUiSecurityFilter} 罩着 {@code /config}、{@code /config/*}。
 * 开关一关，那道过滤器跟整个注册器一起不装配——而挂在 {@code /config} 下的几个控制器
 * 本身并不认开关，谁够得着端口谁就能调。其中 {@code /config/api/napcat/credential}
 * 会回 NapCat 管理页的登录凭据明文，等于把整台 NapCat 交出去。
 *
 * <h2>与控制器上的开关条件是两层，不是重复</h2>
 * 控制器补开关条件，保证已经知道的接口真的不登记；
 * 这道过滤器兜住以后新加、忘了挂开关的接口。只做前一半，下一个人加一个忘挂开关的
 * 控制器就又漏了；只做这一半，接口只是被挡住、bean 还在，而且谁漏改一个 URL pattern
 * 就漏一片。两层都要。
 *
 * <h2>为什么是 404 而不是 403</h2>
 * 「关掉」的语义是这件东西不在，不是「在、但你不许看」。回 403 等于告诉门外的人
 * 这儿确实有一套管理接口；回 404 与「路径根本不存在」同形。
 *
 * <h2>为什么不合并进 ConfigUiSecurityFilter</h2>
 * 两道的装配条件互斥（一道开关开、一道开关关），合并成一道就得在过滤器里读配置分叉，
 * 而那道过滤器已经管着口令、令牌、CSRF、协议闸好几件事。分开放各自只做一件事，
 * 装配条件由注解写在登记器上，不用再抄一份配置键去比对。
 */
public class ConfigUiClosedFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        // 不交给后面的链：关掉之后 /config 下的东西一律不在
    }
}
