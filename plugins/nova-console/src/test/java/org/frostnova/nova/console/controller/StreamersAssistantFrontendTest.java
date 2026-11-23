package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

class StreamersAssistantFrontendTest {
    private void run(String name) throws Exception {
        FrontendFixture.runFromModule("src/test/resources/frontend/streamers-assistant-fixture.mjs",
                "智能助手", Map.of("ASSISTANT_CASE", name));
    }

    @Test
    @DisplayName("失败说明不能被当成模型结论，状态变化会禁用输入")
    void failuresHaveNoLabelOrCopy() throws Exception {
        run("failures");
    }

    @Test
    @DisplayName("输入框为空时点例子只能填入问题，不能直接扣一次提问")
    void examplesFillTheDraftWithoutAsking() throws Exception {
        run("examples");
    }

    @Test
    @DisplayName("备忘支持增删改，过期和其他主播的备忘不进入空输入示例")
    void memoPanelEditsEntriesAndUsesTheCurrentStreamerInExamples() throws Exception {
        run("memos");
    }

    @Test
    @DisplayName("备忘读不出来时面板能确认清空重建，取消不发送请求，期间仍然能提问")
    void unreadableMemoPanelOffersConfirmedResetWithoutBlockingQuestions() throws Exception {
        run("unreadableMemo");
    }

    @Test
    @DisplayName("点击下一问按钮应带上同一主播的对话并直接提交选中的问题")
    void followUpButtonsSubmitTheSelectedQuestion() throws Exception {
        run("followUps");
    }

    @Test
    @DisplayName("追问只带最近 10 轮有回答的对话")
    void historyContainsOnlyTenSuccessfulTurns() throws Exception {
        run("history");
    }

    @Test
    @DisplayName("一条带大段空白的回答让下一次追问报错")
    void historyKeepsTheAnswerAfterLeadingWhitespace() throws Exception {
        run("historyWhitespace");
    }

    @Test
    @DisplayName("弹幕中的网页片段按纯文本显示，不执行网页代码")
    void remoteTextCannotBecomeMarkup() throws Exception {
        run("text");
    }

    @Test
    @DisplayName("图在回答下方，点击可以放大，下一问不会把图片回传给模型")
    void showsChartPreviewAndOpensFullImage() throws Exception {
        run("charts");
    }

    @Test
    @DisplayName("复制和手动复制都保留人工智能提示句")
    void copiesAlwaysIncludeNotice() throws Exception {
        run("copy");
    }

    @Test
    @DisplayName("成员看不到花费和设置入口，不可用原因合并重复提示")
    void membersHaveNoCostsOrSettingsLinks() throws Exception {
        run("member");
    }

    @Test
    @DisplayName("总开关关闭或读取失败时隐藏成员入口，管理员仍有入口")
    void headerEntryFollowsRoleAndEnabledState() throws Exception {
        run("entry");
    }
}
