# 600 功能审计·条目级映射表（v2）

> 状态：2026-10-12 从域级升级为条目级。前版见工作区《600功能审计报告.txt》
> （2026-09-30，域级判定 634 用例 0 失败）。
> 本表数据全部由 `scripts/ledger-sync-tests.py` 从测试源码自动对账生成，可重跑复核。

## 一、对账方法

1. 扫 engine/app 全部 `*Test.kt`：类名 + `@Test fun` 用例名（反引号名与 camelCase 名都收）
2. 与 FEATURE-LEDGER.csv 的 T: 用例条目对账：基准=类名+源用例名（关联用例列）
3. 缺的自动追加（feature_no 续号）；功能条目关联用例列按模块规则回填
4. 幂等：重跑 0 缺 0 填即账实一致

## 二、本轮对账结果（2026-10-12）

| 项 | 数字 |
|---|---|
| 测试源码扫描出用例 | 865 个 |
| 账本已有 T: 用例条目（对账前） | 367 条 |
| 本轮自动补登 T: 用例条目 | 498 条（含 APK 域 6 条新增） |
| 账本 T: 用例条目总数（对账后） | 865 条，与源码 1:1 |
| 功能条目关联用例回填 | 137 条（language 97 + observe 40） |
| 功能条目挂用例覆盖率 | 300/454 -> 437/454（66% -> 96%） |

### 补登大户（对账前零登记或错位的测试类）

- **engine/observe：1 -> 78 条**（OffsetProbeTest 17、UmaToolTest 12、FieldCardAndSpoolTest 9、
  BoardReaderTest 8、ObserveStateTest 11、ObserveClientTest 11、MetadataParserTest 5、BoardAndParseToolTest 5）
- **根包与模块名错位类**（engine/search、engine/http、engine/vision 等）：此前模块名带全包路径漏登，本轮归位
- **APK 域：8 -> 14 条**（ApkInspectorTest 5 + ApkSignatureTest 6 + AxmlManifestTest 3，见下节）

## 三、APK 域薄弱区补测（上版审计遗留清单清账）

上版缺口四项，本轮全补（ApkSignatureTest 4 新用例 + AxmlManifestTest 2 新用例）：

| 缺口 | 新用例 | 验证方式 |
|---|---|---|
| v3 签名路径 | `自造 v3 块走 V3_ID 路径` | 手拼 V3_ID=0xf05368c0 签名块+真证书 DER，断言 v3Present+subject |
| 多 signer | `多证书计数走满 certificates 数组` | 同 DER 双份，断言 certCount=2 |
| 损坏 ZIP 容错 | `中央目录贴零如实报无签名空间` + `魔数错位如实报没有签名块` | 两条早退路径消息精确断言 |
| AXML 属性池边界 | `UTF16 串池路径同样抽出包名与版本` + `截断与越界串池不炸如实返回空` | UTF-16 串池全链抽包名；4 字节截断与 offsets 越界返回空不炸 |

全部离线（无手机依赖），本地 repro 直跑验证通过后才推 CI。

## 四、剩余 17 条零挂钩功能条目（437/454 的差额）

实测全部为 **PR 里程碑事件条目**（功能号 805-821：PR#78 生成参数页修摆设、
PR#79 外观页主色切换、PR#84-100 各页面修摆设/摆设清剿收官、PR#103/105/107 IL2CPP 三刀），
记录的是「哪个 PR 干了什么」，不是可单独执行的功能路径——其代码效果的用例挂在
对应模块的功能条目上（如 IL2CPP 四刀的用例挂在 engine/observe 78 条里）。

判定：17 条均为事件类条目，挂用例无意义——条目级覆盖**实质闭合**：
凡有代码路径的功能条目均有用例挂钩或所属模块用例覆盖。

## 五、防再漂移

- `scripts/ledger-sync-tests.py` 进仓，任何人加测试后跑一遍即完成账本同步
- 纪律：CI 红门不管账本（账本是文档不是门），但审计口径以「重跑同步器=0 缺 0 填」为准
- 上版审计报告的域级结论（634 用例）已被本轮 865 用例取代，数字差异来源=
  PR#110 后新增测试类 + 此前模块名错位漏登
