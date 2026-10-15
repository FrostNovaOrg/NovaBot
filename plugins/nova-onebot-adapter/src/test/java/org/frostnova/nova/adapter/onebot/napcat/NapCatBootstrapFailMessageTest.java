package org.frostnova.nova.adapter.onebot.napcat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.script.ScriptEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NapCat 引导页出错时，服务端回的提示原样显示成文字
 * <p>
 * 抓的用户故障：换取凭据失败时，服务端提示里带尖括号，页面被搅乱，返回链接指错——
 * 提示被当成标记拼进页面，里面的 {@code <a>} 成了一个真链接，「返回 NovaBot 控制台」那个链接反倒没有地址。
 * <p>
 * 跑的是引导页里<b>真正交付的那段 {@code fail()}</b>，从页面原文里取出来，
 * 放在一个只认这几个元素的假页面上。假页面给 {@code innerHTML} 的写入按标记拆开，
 * 所以把提示拼进标记的写法在这里会长出多余的链接。
 */
@DisplayName("NapCat 引导页的出错提示按文字显示")
class NapCatBootstrapFailMessageTest {
    private static final String CONSOLE = "/novabot/config";

    /**
     * 只认 #title、#msg、#steps 三处，与引导页里 fail() 碰到的元素一致
     */
    private static final String FAKE_PAGE = """
            function decode(s) {
              return s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&amp;/g, '&');
            }
            function El(tag) {
              var self = this;
              this.tagName = tag;
              this.nodes = [];
              this.className = '';
              this.href = null;
              Object.defineProperty(this, 'textContent', {
                get: function () {
                  return self.nodes.map(function (n) {
                    return typeof n === 'string' ? n : (n.tagName === 'br' ? '\\n' : n.textContent);
                  }).join('');
                },
                set: function (v) { self.nodes = [String(v)]; }
              });
              Object.defineProperty(this, 'innerHTML', {
                set: function (html) {
                  self.nodes = [];
                  var re = /<a\\b([^>]*)>([\\s\\S]*?)<\\/a>|<br\\s*\\/?>|<[^>]*>|([^<]+)/g, m;
                  while ((m = re.exec(html)) !== null) {
                    if (m[0].indexOf('<a') === 0) {
                      var a = new El('a');
                      var h = /href="([^"]*)"/.exec(m[1]);
                      if (h) { a.href = decode(h[1]); }
                      a.textContent = decode(m[2].replace(/<[^>]*>/g, ''));
                      self.nodes.push(a);
                    } else if (m[0].indexOf('<br') === 0) {
                      self.nodes.push(new El('br'));
                    } else if (m[3] !== undefined) {
                      self.nodes.push(decode(m[3]));
                    }
                  }
                }
              });
            }
            El.prototype.appendChild = function (child) { this.nodes.push(child); return child; };
            El.prototype.anchors = function () {
              return this.nodes.filter(function (n) { return typeof n !== 'string' && n.tagName === 'a'; });
            };
            var elements = { '#title': new El('h1'), '#msg': new El('p'), '#steps': new El('div') };
            var document = { createElement: function (tag) { return new El(tag); } };
            var $ = function (s) {
              if (s === '#msg a') { return elements['#msg'].anchors()[0] || null; }
              return elements[s];
            };
            """;

    private ScriptEngine engine;

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("build.sh")) && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根目录");
    }

    @BeforeEach
    void loadFailFromThePage() throws Exception {
        String page = Files.readString(repositoryRoot()
                .resolve("plugins/nova-onebot-adapter/src/main/resources/config-ui-pages/napcat-bootstrap.html"),
                StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("(?m)^  function fail\\(text\\) \\{[\\s\\S]*?^  }$").matcher(page);
        assertTrue(matcher.find(), "引导页里没找到 fail()，这一格什么也没量");

        engine = new org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory()
                .getScriptEngine("--language=es6");
        engine.eval(FAKE_PAGE);
        engine.eval("var CONSOLE = '" + CONSOLE + "';");
        engine.eval(matcher.group());
    }

    private String eval(String script) throws Exception {
        return String.valueOf(engine.eval(script));
    }

    @Test
    @DisplayName("取凭据断网不再卡在第一步，验证断网不当成凭据无效")
    void offlineBootstrapExplainsConnectionFailure() throws Exception {
        Path root = repositoryRoot();
        Path fixture = root.resolve("plugins/nova-onebot-adapter/src/test/resources/frontend/napcat-bootstrap-fixture.mjs");
        Process process = new ProcessBuilder("node", fixture.toString())
                .directory(root.toFile()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "NapCat 引导页检查超时");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        System.out.println("NapCat 引导页连接失败检查：" + output);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("跑了 7 格"), "没有完成引导页检查：" + output);
    }

    @Test
    @DisplayName("提示里带标记时原样显示成文字，返回链接仍指向控制台")
    void markupInMessageStaysText() throws Exception {
        String message = "<a href=\"x\">点我</a>";
        engine.put("message", message);
        engine.eval("fail(message);");

        assertTrue(eval("elements['#msg'].textContent").contains(message),
                "提示没有原样显示，被当成标记拼进了页面: " + eval("elements['#msg'].textContent"));
        assertEquals("1", eval("elements['#msg'].anchors().length"),
                "提示框里只该有返回控制台那一个链接，提示里的标记长成了链接");
        assertEquals(CONSOLE, eval("elements['#msg'].anchors()[0].href"), "返回链接没指向控制台");
        assertEquals("返回 NovaBot 控制台", eval("elements['#msg'].anchors()[0].textContent"));
        assertEquals("NapCat 自动登录失败", eval("elements['#title'].textContent"));
        assertEquals("err", eval("elements['#msg'].className"));
    }
}
