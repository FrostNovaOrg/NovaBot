# 破坏跑案卷：3e9dffd

> 一条从没红过的判据，就还没有人证明过它拦得住什么。

本件由 `docs/break-run/breakrun.py` 实跑生成，原始记录在同目录 `3e9dffd.json`，
用 `docs/break-run/render.py` 渲染成本文。每个变体三样俱全：**改在哪里 / 红了哪几条
（含 surefire 报的原话）/ 时间戳**。

- 被测版本：**`3e9dffd`**。开跑前核过工作区干净；每个变体是一处单点替换，跑完
  `git checkout --` 还原，下一个变体从干净树重来
- 每次跑之前删掉 `starbot-core/target/surefire-reports`，**不让上一次的 XML 冒充这一次**
- 判据数口径：surefire 报告里的 `<testcase>` 元素数。**不能用 `tests="..."` 属性**——
  判据都写在 `@Nested` 里的类，那个属性恒为 0
- 每个变体跑的是**整个 starbot-core 模块**（553 条），不是只跑相关的几个类。
  这样才看得出「它只红了该红的，别的一条没动」
- 跑完时间：2026-08-20 17:56:58 +0800

## 一、验尺

数红的那把尺自己先在一个已知样本上量过：临时写入一个「三绿夹一红」的判据类
（红的排第三位，绿的在 surefire 里写成自闭合的 `<testcase .../>`），跑完即删。

| 项 | 值 |
|---|---|
| 样本里应当红的 | `BreakRunRulerControlTest.gammaRedOnPurpose` |
| 尺子实际报的 | `BreakRunRulerControlTest.gammaRedOnPurpose` |
| 当时全量 | 557 条（基线 553 + 样本 4） |
| 结论 | ✅ 数对了，名字没串行 |

尺子另外三项自证（读数取自 `3e9dffd.json` 的 `验尺` 段）：

| 自证的是什么 | 读数 | 结论 |
|---|---|---|
| 分类器取的是真类名，不被 `@DisplayName` 带偏 | 报 `BreakRunRulerControlTest` | ✅ 没被带偏 |
| 单跑管道跑得起来（真类名） | 实跑 4 条 | ✅ 跑得起来 |
| 单跑管道分得清「断了」与「不红了」（展示名） | 跑不起来（编译失败或 -Dtest=验尺用的样本 没匹配到任何类） | ✅ 认出「断了」 |

> 第三行是**失败闭合**那一条：`-Dtest=` 拿展示名去跑会匹配到 0 个类，
> 而 0 条判据**不算「全绿」**——不这么判，管道一断就会伪装成「都好着呢」。
>
> 夹具的 `@DisplayName` 是**故意**取得和类名不一样的，否则这两行自证等于没做。


## 二、基线

没动过的树：**553 条，红 0 条。**

## 三、一览

| 变体 | 改在哪里 | 落点 | 红了几条 | 起 | 止 |
|---|---|---|---:|---|---|
| **U** | 撞闸文案不再写回满速率 | `NapCatBootstrapController.java` | 1 | 17:51:36 | 17:53:04 |
| **X** | 回满时把桶整个填满，而不是只回一个额度 | `NapCatCredentialService.java` | 1 | 17:53:04 | 17:54:30 |
| **Y** | 把回满窗口拉长一倍（速率减半） | `NapCatCredentialService.java` | 2 | 17:54:30 | 17:56:58 |

## 四、逐个变体

### 变体 U　撞闸文案不再写回满速率

- 落点：`starbot-core/src/main/java/com/starlwr/bot/core/config/ui/napcat/NapCatBootstrapController.java`
- 开跑前预期打红：文案判据应红：等多久没了数，使用者不知道该等多久才算等够
- 时间：2026-08-20 17:51:36 +0800 → 2026-08-20 17:53:04 +0800
- 全量 553 条，本批红 **1** 条

  - `NapCatBootstrapControllerTest.throttledCopyPointsAtTheConfigAndStatesTheRefillRate`
    > 文案里的回满速率必须等于 MINT_WINDOW ÷ MINT_BURST 算出来的那个数：换取凭据过于频繁，请稍等一会儿再试。若一直换不出来，多半是 NapCat 的 token 或二次验证密钥配得不对 ==> expected: <true> but was: <false>

### 变体 X　回满时把桶整个填满，而不是只回一个额度

- 落点：`starbot-core/src/main/java/com/starlwr/bot/core/config/ui/napcat/NapCatCredentialService.java`
- 开跑前预期打红：theBucketRefillsLinearlyNotAllAtOnce 应红，且 theBucketRefillsSoThereIsNoLockout 应照绿：拨满一整窗时两种实现读数相同，只有拨不满一窗才分得出
- 时间：2026-08-20 17:53:04 +0800 → 2026-08-20 17:54:30 +0800
- 全量 553 条，本批红 **1** 条

  - `NapCatCredentialServiceTest.theBucketRefillsLinearlyNotAllAtOnce`
    > 回来的应当只有一个额度、不是一整桶 —— 放行说明这是到点重置不是线性回填 ==> expected: <THROTTLED> but was: <OK>

### 变体 Y　把回满窗口拉长一倍（速率减半）

- 落点：`starbot-core/src/main/java/com/starlwr/bot/core/config/ui/napcat/NapCatCredentialService.java`
- 开跑前预期打红：throttledCopyPointsAtTheConfigAndStatesTheRefillRate 应红（文案里那个数由常量算出，窗口一改它就不再是 75）＋ theBucketRefillsLinearlyNotAllAtOnce 应红（75 秒回不来一个）
- 时间：2026-08-20 17:54:30 +0800 → 2026-08-20 17:56:58 +0800
- 全量 553 条，本批红 **2** 条

  - `NapCatBootstrapControllerTest.throttledCopyPointsAtTheConfigAndStatesTheRefillRate`
    > 今天的标定下回满速率是 75 秒（5 分钟 ÷ 4）：换取凭据过于频繁，请稍等一会儿再试（额度约每 150 秒回一次）。若一直换不出来，多半是 NapCat 的 token 或二次验证密钥配得不对 ==> expected: <true> but was: <false>
  - `NapCatCredentialServiceTest.theBucketRefillsLinearlyNotAllAtOnce`
    > 满 75 秒该回来一个额度 ==> expected: <OK> but was: <THROTTLED>

## 五、合计

- 变体 **3** 个
- 没能让任何判据变红的：**0** 个 （无）
- 编译失败的：**0** 个 （无）
- 这 5 个类里被红过的判据：**2** 条
- 其中**本批新增的 27 条**：被本跑的 3 个变体红过 **2** 条

> ⚠️ **本跑是挑着跑的**（3/25 个变体），所以「新增判据被红过几条」这一格**不构成覆盖率结论**——没被红到的多半是这次压根没跑它的那个变体。整跑的覆盖率见整跑的案卷。

被红过的判据逐条（← 后面是把它弄红的变体）：

- `NapCatBootstrapControllerTest.throttledCopyPointsAtTheConfigAndStatesTheRefillRate` ← U、Y
- `NapCatCredentialServiceTest.theBucketRefillsLinearlyNotAllAtOnce` ← X、Y
