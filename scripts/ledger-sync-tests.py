#!/usr/bin/env python3
"""账本-测试条目同步器（600 审计条目级映射）。

干三件事（幂等，重跑不重复）：
1. 扫 engine/app 测试源码：类名 + @Test fun 用例名（反引号名原样保留）
2. 对账 FEATURE-LEDGER.csv 的 T: 用例条目：缺的追加（feature_no 续最大号）
3. 回填功能条目的关联用例列：
   - 模块 language（lang:xx 注册项）-> LangRegistryTest
   - 模块 observe（uma_* 工具注册项）-> UmaToolTest（全族注册用例盖存在性）

用法：python3 scripts/ledger-sync-tests.py [--dry]
"""
import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LEDGER = ROOT / "docs" / "FEATURE-LEDGER.csv"
TEST_ROOTS = [
    (ROOT / "engine" / "src" / "test" / "kotlin", "engine"),
    (ROOT / "app" / "src" / "test" / "kotlin", "repotool"),
]


def scan_tests():
    """返回 [(类名, 用例名, 模块)]。模块=engine/<包> 或 app 包映射。"""
    out = []
    for root, brand in TEST_ROOTS:
        if not root.exists():
            continue
        for f in root.rglob("*Test.kt"):
            rel = f.relative_to(root).parent.as_posix()
            # 包路径形如 com/hualuo/engine/apk -> 模块 engine/apk；
            # com/hualuo/repotool/ui/state -> repotool/ui/state；根包兜底用品牌名
            tail = rel.split("com/hualuo/")[-1] if "com/hualuo/" in rel else rel
            if brand == "engine":
                # tail 可能已是 engine/search（根包 com/hualuo/engine/search）或 engine（根包直下）或 apk（com/hualuo/engine/apk）
                if tail in ("engine", ".", ""):
                    module = "engine"
                else:
                    module = tail if tail.startswith("engine/") else f"engine/{tail}"
            else:
                module = tail if tail not in (".", "") else "repotool"
            text = f.read_text(encoding="utf-8")
            m = re.search(r"(?:^|\n)(?:\w+\s+)?class\s+(\w+Test)", text)
            if not m:
                continue
            cls = m.group(1)
            for t in re.finditer(
                r"@Test\s+(?:@\w+(?:\([^)]*\))?\s+)*fun\s+`([^`]+)`", text
            ):
                out.append((cls, t.group(1), module))
            for t in re.finditer(
                r"@Test\s+(?:@\w+(?:\([^)]*\))?\s+)*fun\s+(\w+)\(", text
            ):
                out.append((cls, t.group(1), module))
    return out


def main():
    dry = "--dry" in sys.argv
    rows = list(csv.DictReader(open(LEDGER, encoding="utf-8")))
    fields = list(rows[0].keys())
    max_no = max(int(r["feature_no"]) for r in rows if r["feature_no"].isdigit())

    # 已有用例条目：对账基准=类名（实装符号 # 前段）+ 源用例名（关联用例列 camelCase 原名）
    # （实装符号 # 后段是空格名/截断名，不作对账依据）
    have = set()
    for r in rows:
        sym = r["实装符号"] or ""
        if sym.startswith("T:") and (r["关联用例"] or "").strip():
            have.add(f"{sym[2:].split('#')[0]}#{r['关联用例'].strip()}")

    tests = scan_tests()
    missing = []
    for cls, name, module in tests:
        key = f"{cls}#{name}"
        if key not in have:
            missing.append((cls, name, module, key))

    # 回填关联用例列
    backfill = {"language": "LangRegistryTest", "observe": "UmaToolTest"}
    filled = 0
    for r in rows:
        mod = r["模块"]
        if mod in backfill and not (r["实装符号"] or "").startswith("T:") and not (r["关联用例"] or "").strip():
            r["关联用例"] = backfill[mod]
            filled += 1

    print(f"扫描测试用例 {len(tests)} 个；账本缺 {len(missing)} 条；回填关联用例 {filled} 条功能")
    for cls, name, module, key in missing:
        print(f"  + {max_no + 1} T:{key} [{module}]")

    if dry:
        print("dry 模式：不写回")
        return

    # 追加缺的用例条目
    for cls, name, module, key in missing:
        max_no += 1
        rows.append({
            "feature_no": str(max_no),
            "实装符号": f"T:{key}",
            "功能名": f"engine·{cls}·{name[:24]}",
            "用途": f"engine 域「T:{cls}」的独立行为：{name[:40]}（测试源码解析自动登记）",
            "来源": "用例",
            "模块": module,
            "关联用例": name,
        })

    with open(LEDGER, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)
    print(f"已写回 {LEDGER}：总条目 {len(rows)}")


if __name__ == "__main__":
    main()
