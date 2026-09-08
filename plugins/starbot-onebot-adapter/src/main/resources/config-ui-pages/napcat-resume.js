/*
 * NapCat 会话失效自动续登（续登层）
 *
 * 形态：外层同源包装页托住 iframe，盯着内层的路径；一旦看到它落到登录路由，
 * 就重换一把凭据写回 localStorage 并重载内层。
 *
 * 为什么要盯路径而不是盯请求：
 * 凭据过期时 NapCat 的拦截器清 localStorage 并 reload，SPA 启动后守卫走
 * navigate("/web_login", {replace:true}) —— 那是 history.replaceState，**不发任何请求**。
 * 所以反代看不见它，只有同源的父页读得到 iframe.contentWindow.location.pathname。
 *
 * 🔴 这里只放「续登」。「识别层还认不认得出登录路由」那件事在服务端
 *    （NapCatRouteWitness）：放在这个页面里，既要跟 public,max-age=86400 搏斗，
 *    又只在有人打开页面时才跑，而静默失效恰恰发生在没人打开的时候。
 */
(function (global) {
    'use strict';

    /**
     * NapCat 的登录路由。
     * 🔴 必须与服务端 NapCatRouteWitness.ROUTE_LITERAL 是同一个值，有判据钉着：
     *    两边不一致时，见证盯的是一个没人用的字面量，于是它永远绿、
     *    而这里的识别照样在下一次 NapCat 升级时悄悄失效。
     */
    var LOGIN_ROUTE = 'web_login';

    var Action = {
        /** 什么都别做（读不到内层，或续登后的那次重载还没落地，或已经停手了） */
        WAIT: 'WAIT',
        /** 内层不在登录页，这一轮到此为止，计数归零 */
        RESET: 'RESET',
        /** 换一把凭据并重载内层 */
        RENEW: 'RENEW',
        /** 换过一次还是回到登录页 —— 停手，回落到现状 */
        GIVE_UP: 'GIVE_UP'
    };

    /**
     * 这个路径是不是登录页
     *
     * 🔴 按**最后一段全等**判断，不用 indexOf。子串匹配在这个项目里已经栽过四次
     *    （最近一次是数动态 id 时把 rid= 也吃了进去）。用子串的话，
     *    NapCat 哪天加一个 /web_login_history 之类的路由，这里就会把它误判成登录页，
     *    然后对着一个正常页面反复换凭据。
     */
    function isLoginPath(path) {
        if (typeof path !== 'string' || path === '') {
            return false;
        }
        var clean = path.split('?')[0].split('#')[0];
        while (clean.length > 1 && clean.charAt(clean.length - 1) === '/') {
            clean = clean.slice(0, -1);
        }
        return clean.slice(clean.lastIndexOf('/') + 1) === LOGIN_ROUTE;
    }

    /**
     * 纯函数：只看「内层现在在哪」和「这一轮换过没有」，决定下一步做什么。
     *
     * 抽成纯函数不是为了好看，是为了它**能被判据真的跑一遍**：
     * 一段代码会不会循环，只有喂给它一串输入跑一遍才知道，grep 看不出来。
     *
     * @param state {{renewed:boolean, reloading:boolean, stopped:boolean}}
     * @param path  内层当前路径；读不到时传 null
     */
    function decide(state, path) {
        if (state.stopped) {
            return Action.WAIT;
        }
        if (path === null || path === undefined) {
            return Action.WAIT;
        }
        if (state.reloading) {
            // 续登之后那次重载还在路上。此时内层多半还停在登录页，
            // 若不等它落地就判，会把「正在救」误判成「救不回来」，于是一次机会都不给
            return Action.WAIT;
        }
        if (!isLoginPath(path)) {
            return Action.RESET;
        }
        if (state.renewed) {
            // 边界⑤：换过一次还是回到登录页，说明不是凭据过期那么简单。
            // 再换只会重复同一个结果，并且把请求打向 NapCat 的登录接口
            return Action.GIVE_UP;
        }
        return Action.RENEW;
    }

    /**
     * 决定 + 记账。碰浏览器的事都不在这里做。
     */
    function step(state, path) {
        var action = decide(state, path);
        if (action === Action.RESET) {
            // 内层回到了正常页面，说明上一次续登真的救回来了 —— 下一次失效可以再救一次。
            // 🔴 归零的条件是「确实离开过登录页」，不是「过了多久」：
            //    靠时间归零的话，一个一直卡在登录页的内层会被反复重试
            state.renewed = false;
        } else if (action === Action.RENEW) {
            state.renewed = true;
            state.reloading = true;
        } else if (action === Action.GIVE_UP) {
            state.stopped = true;
        }
        return action;
    }

    function newState() {
        return { renewed: false, reloading: false, stopped: false };
    }

    /**
     * 开始盯着 iframe
     *
     * @param options.frame       iframe 元素
     * @param options.webuiUrl    内层的入口地址
     * @param options.renew       () => Promise<{success, credential, message}>
     * @param options.persist     (credential) => void，把凭据写进 localStorage
     * @param options.onGiveUp    (reason) => void，停手时通知外面
     * @param options.intervalMs  轮询间隔
     */
    function start(options) {
        var state = newState();
        var frame = options.frame;
        var interval = options.intervalMs || 1500;

        frame.addEventListener('load', function () {
            state.reloading = false;
        });

        function currentPath() {
            try {
                return frame.contentWindow.location.pathname;
            } catch (e) {
                // 内层正在导航、或者哪天它不再同源了。两种情况都是「读不到」，
                // 而读不到时该做的事是什么都不做 —— 使用者看到的就是今天的 NapCat 页面
                return null;
            }
        }

        function stop(reason) {
            state.stopped = true;
            clearInterval(timer);
            if (options.onGiveUp) {
                options.onGiveUp(reason);
            }
        }

        function tick() {
            var action = step(state, currentPath());
            if (action === Action.GIVE_UP) {
                stop('renewed_but_still_on_login');
                return;
            }
            if (action !== Action.RENEW) {
                return;
            }
            options.renew().then(function (issued) {
                if (!issued || !issued.success) {
                    // 换不出来（配置不对、或者被本机的速率闸拦下）。
                    // 这里同样只停手，不重试 —— 重试换不来成功，只会把请求量堆上去
                    stop(issued && issued.reason ? issued.reason : 'mint_failed');
                    return;
                }
                options.persist(issued.credential);
                frame.contentWindow.location.replace(options.webuiUrl);
            }, function () {
                stop('mint_error');
            });
        }

        var timer = setInterval(tick, interval);
        return {
            stop: function () { stop('cancelled'); },
            state: state
        };
    }

    global.NapCatResume = {
        LOGIN_ROUTE: LOGIN_ROUTE,
        Action: Action,
        isLoginPath: isLoginPath,
        decide: decide,
        step: step,
        newState: newState,
        start: start
    };
})(typeof globalThis !== 'undefined' ? globalThis : this);
