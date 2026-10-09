# IL2CPP 立项参考：偏移漂移与版本演进（外部生态实证）

> 2026-10-09 立项时整理。三仓 clone 于 sandbox/il2cpp-study/（Il2CppDumper / Il2CppInspector / Zygisk-Il2CppDumper）。
> 结论只登记源码实证的事实，页码行号可复核。

## 一、IL2CPP 偏移的两种含义（别混）

| 含义 | 在哪 | 怎么拿 | 我们的关系 |
|---|---|---|---|
| **方法指针偏移** | libil2cpp.so 的导出表/符号 | dlsym 按名解析（hlpatch resolve_il2cpp_symbol 同款） | 已解决：按名反射，不硬编码 |
| **字段偏移** | 对象实例内存布局 | 运行时 il2cpp_field_get_offset（FieldInfo.offset）或离线 metadata fieldOffsets 表 | 本立项核心：旧版本记忆 vs 新版本漂移 |

## 二、字段偏移的对齐规则（arm64 Android，实证）

来源：Il2CppDumper Il2Cpp/Il2Cpp.cs GetFieldOffsetFromIndex + IL2CPP 运行时布局惯例：

- 引用类型 / long / ulong / double / IntPtr：**8 字节对齐**
- int / uint / float：**4 字节对齐**
- short / ushort / char：**2 字节对齐**
- byte / sbyte / bool：**1 字节对齐**
- 实例字段从 **16**（arm64 对象头：klass 指针 8 + monitor 8）起排
- 静态字段偏移为负（静态区索引），不打扰实例布局
- 值类型字段偏移修正：Il2CppDumper 对 isValueType 实例做 **-8（32 位）/ -16（64 位）**——这就是「8 或 16」的出处之一：值类型对象内嵌时的头部扣除

版本更新后最常见漂移（经验分布，供候选排序）：
1. 前插一个引用字段 → 后面全部 **+8**
2. 前插 int/float → **+4**
3. 对象头重排/基类变化 → **±16**
4. 字段删除 → 后面回缩
5. 字段改名 → 偏移不动名字变（按相似度配对）

## 三、外部仓怎么处理版本漂移

### Il2CppDumper（Perfare，C#，离线 dump 路线）
- **版本号切结构**：MetadataClass.cs / Il2CppClass.cs 全部字段带 `[Version(Min = X, Max = Y)]` 门（v19-v31+ 每版结构差异都登记）
- **候选试探**：AutoPlusInit 里读出 invokerPointersCount 超 0x50000 就判定版本猜错了，退一版重读（如 31→29、27.1→27.2、24.4→24.5、24.2→24.3）——**读出来不对就换候选，不硬闯**
- **fieldOffsetsArePointers**：v21 做候选试探（前 6 个字段偏移全 0 且第 6 个 >0 = 指针模式）；v22+ 直接定指针模式
- **GetFieldOffsetFromIndex**：fieldOffsets 是指针表时按 `ptr + 4*fieldIndexInType` 读 int32；值类型实例 -8/-16 修正；**异常时返回 -1 不炸**
- config.json 有 ForceIl2CppVersion 手动覆盖口（自动判错时的逃生门）

### Zygisk-Il2CppDumper（Perfare，运行时 dump 路线——与 hlpatch 同路线）
- il2cpp_dump.cpp：`il2cpp_class_get_fields` 迭代 → `il2cpp_field_get_offset(field)` 直接拿运行时真实偏移
- 输出 dump.cs 每字段带 `; // 0x{offset}` 注释
- **运行时拿偏移不受 metadata 加密影响**——这是它对离线路线的根本优势（我们 676 包 metadata 加密、离线 dump 不可行的正解）

### Il2CppInspector（djkaty，C#）
- 1.5G 全家桶含完整 git 历史；版本支持最广（v24-v31 全家）
- 与 Il2CppDumper 同为离线路线，本立项只取其版本对照表参考

## 四、我们的增量（外部仓没有的）

外部仓都在「当次 dump 拿全量」；我们多一块**跨版本记忆对账**：
1. 旧版本字段卡（游戏更新前的 dump 记忆，含 name/offset/type_name）
2. 新版本观测桥 /fields/<class> 实时读
3. **diff 四态对账**：保留/平移（delta）/改名候选（相似度）/新增/消失
4. **消失字段候选穷举**：按对齐规则沿格点出（窗口 ±64、步长 4/8 按类型）
5. 候选验证：read_mem 试探 + MasterDB 对账（最终事实判据=hook 实测，dump.cs 定位只是参照物——用户明示纪律）

## 五、与冷启动流水的衔接

字段卡切分（AI 一次看一张卡）→ 预言（旧卡+对齐规则出候选）→ 对账（观测桥实测 vs 预言）→ 固化为剧本板。
OffsetProbe 就是「预言-对账」的账本件：diff 出差账、candidates 出预言、render 出人话板给 AI 看。

## 六、相关文件

- 引擎件：engine/observe/OffsetProbe.kt（+OffsetProbeTest 13 用例）
- 观测桥字段端点：hlpatch /fields/<class>（enumerate_class_fields，含 name/offset/class/type_enum/type_name）
- 深读底料：sandbox/hlpatch/reverse/apk676_index/（676 版离线索引，metadata 加密实证）
