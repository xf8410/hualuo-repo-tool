# IL2CPP 冷启动流水设计稿（v1）

> 状态：设计稿（待用户过目）。引擎侧四件已落地：OffsetProbe（PR#103）、
> FieldCardStore + MetadataSpool + 三件 uma_* 工具（PR#105）、MetadataParser（PR#107）。
> 本稿定「怎么串成流水」与「最后一公里怎么落地」。

## 一、问题与目标

**问题**：游戏大版本更新 -> IL2CPP 字段偏移漂移 -> hlpatch SO 里硬编码偏移的数据端点
失效 -> 采集断流。现状恢复路径 = Windows 端人工逆向找偏移、改 SO、重发（周期长，
单人瓶颈，期间手机端采集全停）。

**目标**：游戏更新当天，纯手机 + 观测桥自动重建字段偏移账，恢复数据采集，
**全程不改 SO、不重编译**。

**非目标**：
- 不动 SO（xulai1001 域；观测桥现有通用端点已够用）
- 不做画面映射（已终结的线）
- 不复现「首次语义标注」——哪些字段是体力/干线/友谊，是人工逆向沉淀的知识；
  流水只负责在新版本里**对账搬运**这些语义，不凭空发明语义

## 二、流水总图

```
游戏更新（686 -> 692 之类）
   |
   v
L0 探  /debug/global_metadata_probe     拿 addr/version/size
   |
   v
L1 搬  MetadataSpool（32KB 分片循环）    metadata.bin（151MB，断点续传）
   |
   v
L2 解  MetadataParser（v31 流式）        类清单全景（名字，无偏移）
   |
   v
L3 对  /fields/<class>（反射，版本无关） x 旧字段卡 -> OffsetProbe.diff
   |                                      四态差账 + candidates 预言
   v
L4 固  剧本板（board-<scenario>.json）   语义字段的最新偏移表
   |
   v
L5 采  /singletons 拿实例地址 + read_mem 按板切字段 -> 育成数据照常上传
```

关键认知（IL2CPP-OFFSET-STUDY 实证沉淀）：
- metadata 只有**名字全景**，字段偏移在 SO 的 fieldOffsets——所以 L2 与 L3 是
  **互补**不是替代：L2 告诉你「有哪些类、字段叫什么」（防漏），L3 告诉你
  「偏移多少」（真相）。交叉 = 完整字段卡。
- /fields 走 il2cpp 反射 API（enumerate_class_fields），版本无关，游戏更新后
  照常工作——这是流水能自动化的地基。
- /singletons 给 KNOWN_CLASSES 的实例地址（singleton instance 指针），
  read_mem 是通用内存读——两者组合 = 不依赖 SO 硬编码偏移的数据采集。

## 三、六段明细

### L0 探（probe）

- 干什么：扫游戏进程内存找 metadata 魔数（af 1b b1 fa），拿地址/版本/大小
- 工具：观测桥 GET /debug/global_metadata_probe（SO 侧已有，零改动）
- 失败模式：游戏没开/桥没挂 -> 人话报错指路「先到观测页探测」；
  版本号不是 31 -> 流水停在 L2（解析器只认 v31，如实拒并列清单）

### L1 搬（spool）

- 干什么：32KB 一片循环 read_mem，解 hex 立刻落盘 append；progress.json 记断点
- 工具：模型调 uma_metadata_spool（默认 128 片/回合 = 4MB，多回合推进）
- 失败模式：片中途断了 -> 下次调用从 progress 续传，不重头；
  hex 行格式对不上 -> 整体拒（宁失败不拼脏数据）
- 体积账：151MB / 4MB 每回合 = 约 38 回合；每回合模型一次工具调用，
  全程 App 内存 <= 一片

### L2 解（parse）

- 干什么：离线流式解析 metadata.bin，出类清单（namespace/name/字段数/方法数）
- 工具：MetadataParser（引擎件；下一刀补 uma_metadata_parse 工具壳）
- 用途：全景对账——旧剧本板里盯的类还在不在、字段数变没变、
  有没有**新增**类值得人工看一眼（新剧本线索）

### L3 对（cross + diff）

- 干什么：对剧本板盯的每个类，拉 /fields/<class> 当前真值，
  与旧版本字段卡 diff
- 工具：模型调 uma_fieldcard_save（存新卡）+ uma_offset_diff（出四态差账）
- 差账处置表：
  - 保留（offset 不变）：直接沿用
  - 平移（delta 恒定）：整板按 delta 平移候选，read_mem 抽验 2-3 个语义字段
  - 改名候选（编辑距离 0.72）：人工确认（模型给候选清单，人拍板）
  - 新增/消失：消失的走 OffsetProbe.candidates 对齐穷举（窗口 ±64、
    步长按类型），read_mem 试探 + MasterDB 对账终判

### L4 固（board）

- 干什么：把对完账的偏移写进剧本板（schema 见下节）
- 纪律：板上一律带**证据三件**——来源（哪个端点实测）、对账结论（四态哪种）、
  验证状态（未验/抽验过/终判过）。没有证据的偏移不许上板。

### L5 采（collect）

- 干什么：拿新板恢复采集——/singletons 拿实例地址，
  read_mem 一次读对象整段（大小=类布局），App 侧按板切字段
- 效果：游戏更新当天采集恢复，SO 零改动

## 四、剧本板 schema（board-<scenario>.json）

```json
{
  "scenario": 14,
  "game_version": "692",
  "updated_at_ms": 0,
  "sources": [
    {"class": "Gallop.WorkDataManager", "via": "singleton",
     "instance_path": "/singletons", "layout_bytes": 4096}
  ],
  "fields": [
    {"name": "Turn", "offset": 0, "type_name": "System.Int32",
     "semantic": "回合数", "evidence": {"origin": "fields_endpoint",
     "diff_state": "kept", "verified": "masterdb_final"}}
  ]
}
```

- `semantic` 是人工语义标注从旧板**搬运**过来的（对账状态=kept/shifted 才许搬；
  renamed 的人工确认后才搬）
- `layout_bytes` 供 L5 一次 read_mem 的读长
- 板存 files/fieldcards/boards/，与字段卡同目录族、同落盘纪律（原子换名）

## 五、最后一公里：路线对比

| 路线 | 做法 | 代价 | 判断 |
|---|---|---|---|
| A 改 SO | 把偏移表做进 SO，游戏更新重编译发版 | 动 xulai1001 域；更新周期=发版周期 | 不走 |
| **B App 偏移驱动** | App 拿剧本板，/singletons + read_mem 自己切字段 | 每对象一次 HTTP；需要 App 侧字段切分件（新刀） | **推荐** |
| C 混合 | 高频字段仍走 SO 端点，低频走 App | 两套真相源，对账成本翻倍 | 备选 |

推荐 B 的理由：
1. SO 零改动（仓边界纪律），观测桥继续当**通用内存桥**——版本特异性逻辑
   全部住在 App 侧的板里，游戏更新只换板不换 SO
2. 性能可控：一个对象一次 read_mem（不是一字段一次），采集频率是回合级
   不是帧级，HTTP 开销无感
3. 证据链闭合：板上的每个偏移都带来源与验证状态，600 审计同款纪律

## 六、运行手册（冷启动会话怎么跑）

游戏更新后，用户对 AI 说一句「跑冷启动」。AI（任何模型，工具化铁律）执行：

1. uma_health -> 桥在不在
2. uma_metadata_spool(game_version=新版本号) 循环调用到 finished
3. uma_metadata_parse（待补工具壳）-> 全景：盯的类还在吗、字段数变化
4. 对板上每个类：uma_fieldcard_save(新版本) -> uma_offset_diff(旧版本)
5. 汇总差账板（render 出的人话账）给用户：平移/保留自动处置，
   改名/消失候选列清单等人拍板
6. 人工确认后写新板（L4），L5 采集自动恢复

全程模型可执行、可中断、可续传；人只在「改名候选拍板」一处介入。

## 七、验证方案（终判标准）

- **逐级**：L3 对账结论只是候选；上板前必须过下面之一：
  - read_mem 抽验：按候选偏移读已知语义字段，值在合理域（回合数 0-90 之类）
  - MasterDB 对账：读到的 ID 在主资料库能查到真实卡/技能
  - hook 实测：SO 端点同字段值一致（双源对拍）
- **终判纪律**（用户明示）：最终事实判据 = hook 实测；dump/对账只是定位参照物
- 板上 verified 字段三态：unverified / spot_checked / final

## 八、开放问题（待用户拍板）

1. L2 的工具壳（uma_metadata_parse）还没注册——设计稿过了就补，小刀
2. L5 的「App 侧字段切分件」（BoardReader：read_mem 缓冲区按板切字段）
   是新刀，量级与 MetadataSpool 相当
3. 剧本板的 semantic 初版从哪来：旧 SO 硬编码偏移表逆向整理（一次性人工活），
   还是从 686 版字段卡 + 人工标注开始？
4. 拉面杯（scenario 14）当第一块试验田还是挑个字段少的剧本先跑通？
