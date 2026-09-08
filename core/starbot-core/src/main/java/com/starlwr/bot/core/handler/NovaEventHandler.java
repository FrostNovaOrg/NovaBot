package com.starlwr.bot.core.handler;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.event.NovaExternalBaseEvent;
import com.starlwr.bot.core.model.HandlerOption;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushMessageHandler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 事件处理器接口，推送配置中配置的事件处理器实现均应实现此接口，并使用 {@link Component} 等注解注册至 Spring 容器中
 * <p>
 * 旧名 {@code StarBotEventHandler} 仍认得（见同包下的别名接口），既有实现经别名接口同样满足本接口。
 * <p>
 * 继承 {@link PushMessageHandler} 只为让推送消息挂得住这个实例：模型那一侧只认那个空接口，
 * 不认识本接口的任何一个方法，于是读推送配置这件事不必连带背上整条推送链路。
 */
public interface NovaEventHandler extends PushMessageHandler {
    /**
     * 处理事件
     * @param baseEvent 事件
     * @param pushMessage 推送消息
     */
    void handle(NovaExternalBaseEvent baseEvent, PushMessage pushMessage);

    /**
     * 获取事件处理器处理的事件类型
     * @return 事件类型
     */
    Class<? extends NovaExternalBaseEvent> getEventType();

    /**
     * 获取事件处理器默认参数
     * <p>
     * <b>每次调用都必须返回新的实例。</b>数据源加载推送配置时会把使用者自定义的参数
     * 直接写进该返回值，此处若返回缓存或静态实例，一个推送目标的自定义参数会串到其他
     * 目标上，且重载配置后依然残留。
     * @return 默认参数
     */
    JSONObject getDefaultParams();

    /**
     * 曾经发过、如今已被取代的默认值
     * <p>
     * <b>这是「改默认值」这件事唯一能落到已有配置上的路。</b>使用者的推送参数是整份存下来的，
     * 里面那串模板十有八九就是当初界面预填的默认值；不认得旧默认值的话，改了
     * {@link #getDefaultParams()} 也只对<b>今后新建的推送</b>生效，而现有的推送一条也不会变——
     * 于是同一份代码在两台实例上表现不同，差别只在于配置是哪天建的。
     * <p>
     * 判据是<b>一字不差</b>：存着的值与其中某一版完全相同才算「使用者没改过」，
     * 随之跟到新默认；<b>差一个空格都算改过，原样不动</b>。方向是刻意的——
     * 迁错的那一次会安静地覆盖掉使用者写了很久的模板，而不迁的那一次只是没跟上默认值。
     * <p>
     * 旧值<b>只进不出</b>：一版旧默认值从这张表里删掉之后，还留在那一版上的配置就此永远不迁。
     * @return 键 → 该键历史上发过的默认值，按发出的先后排列；默认无
     */
    default Map<String, List<String>> supersededDefaults() {
        return Map.of();
    }

    /**
     * 这个处理器曾经用过的全类名
     * <p>
     * <b>处理器的全类名是存出去的值。</b>使用者的 {@code datasource.json} 里逐条写着它，
     * 改过的默认模板也按它分键存着。因此把处理器换个包、换个类名，在本仓里不是重构：
     * 核心按全类名精确认处理器，认不出来的那一条<b>不发也不报错</b>——
     * 推送安静地停了，自定义模板安静地回到出厂默认，而使用者看到的只是「怎么不推了」。
     * <p>
     * 搬过家的处理器在这里报上自己的旧名，旧配置就仍读得回来。旧名走的是一张
     * <b>与主表分开</b>的别名表：主表只放真类名，配置界面的勾选项、随包示例、保存前校验
     * 一律按主表来，旧名不会被当成一个还在的处理器重新发出去。
     * <p>
     * 旧名<b>只进不出</b>：从这张表里删掉之后，还写着那个名字的配置就此再也认不出来。
     * 一个旧名同时被两个处理器认领时按后登记的算并打 error——那是搬家搬错了，
     * 不是一种可以静默处理的情形。
     * @return 旧全类名，按用过的先后排列；默认无
     */
    default List<String> legacyClassNames() {
        return List.of();
    }

    /**
     * 展示名称，例如「开播通知」
     * <p>
     * 配置界面据此渲染勾选项，使用者不必接触处理器的全限定类名——那本是实现细节，
     * 不该出现在面向使用者的界面上。
     * <p>
     * 以下几个方法均为默认方法：既有的第三方处理器无需改动即可继续工作，
     * 只是在界面上显示为类名而已。
     * @return 展示名称
     */
    default String displayName() {
        return getClass().getSimpleName();
    }

    /**
     * 一句话说明该处理器在什么时候推送什么
     * @return 说明
     */
    default String description() {
        return "";
    }

    /**
     * 所属平台，例如 bilibili，用于界面按平台分组
     * @return 平台名，未声明时为空
     */
    default String platform() {
        return "";
    }

    /**
     * 消息模板中可用的占位符，例如 {uname}
     * @return 占位符列表
     */
    default List<String> placeholders() {
        return List.of();
    }

    /**
     * 上面那些占位符里，哪几个展开成整行的附件（图片）
     * <p>
     * 模板编辑器把一条消息画成一串块，而附件块与文字块在屏幕上是两种东西：
     * 文字块混在行里，附件块独占一行、预览里画成图片占位。
     * <b>哪个占位符是附件只有处理器自己知道</b>——它展开成的是
     * {@code {image_url=…}} 还是一段文字，界面看不见。
     * <p>
     * 不声明就当文字块：界面把它画在行里、预览里按占位符名字显示，
     * 一个没声明的附件顶多是预览画得不像，而<b>猜错的方向</b>（界面按名字认出
     * {@code {cover}} 这类名字）会让第三方插件的同名占位符跟着变成图片。
     * @return 附件型占位符，须是 {@link #placeholders()} 的子集；默认无
     */
    default List<String> attachmentPlaceholders() {
        return List.of();
    }

    /**
     * 消息模板之外的可配置参数，供配置界面渲染
     * <p>
     * 声明了才配得到：不声明的参数依旧只能在 {@code datasource.json} 里手写，
     * 而使用者无从知道有哪些键、取值范围是多少。界面按 {@link HandlerOption} 的
     * 类型通用渲染，本身不认识任何具体参数名，因此第三方插件自带的参数同样可配。
     * @return 可配置参数列表，默认无
     */
    default List<HandlerOption> options() {
        return List.of();
    }
}
