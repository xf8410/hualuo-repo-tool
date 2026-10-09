#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Artifact 统一账本生成器：把三本账对成一张表。

三本账：
  1) docs/FEATURE-LEDGER.csv   功能账（人工续录，一功能一行）
  2) GitHub Releases           包账（一 run 一存档；功能包 tag=功能N-srcSHA，自检 tag=vX.Y.Z-rN-srcSHA）
  3) GitHub Actions runs+artifacts  证据账（run 名带功能号；artifact=日志/ 逐测试 XML）

对齐后产出 docs/ARTIFACT-LEDGER.md：功能包对照表 + 对账区（有包无账/有账无包/红了的功能包）。
只登记事实，不下结论；判覆盖看审计报告。

用法：python3 tools/gen-artifact-ledger.py   （在仓根跑；token 从 git remote 取，同 dispatch.py）
设计约束：
  - stdlib only（沙箱零依赖可跑）
  - 全部走 GitHub REST API 分页，不 clone
  - 幂等：随时重跑，产物整文件覆盖
"""
import csv
import datetime as dt
import json
import re
import subprocess
import sys
import urllib.request

REPO_API = "https://api.github.com/repos/xf8410/hualuo-repo-tool"
REPO_URL = "https://github.com/xf8410/hualuo-repo-tool"
OUT_PATH = "docs/ARTIFACT-LEDGER.md"

FEAT_TAG = re.compile(r"^功能(\d+)-src([0-9a-f]+)$")
SELF_TAG = re.compile(r"^v(\d+\.\d+\.\d+)-r(\d+)-src([0-9a-f]+)$")
RUN_NAME = re.compile(r"^功能 #(\d+)·")
PR_REF = re.compile(r"PR#?(\d+)")


def token():
    out = subprocess.run(
        ["bash", "-c",
         "cd $(git rev-parse --show-toplevel 2>/dev/null || echo .) "
         "&& git remote get-url origin | grep -oE 'ghp_[A-Za-z0-9]+'"],
        capture_output=True, text=True).stdout.strip()
    if not out:
        sys.exit("拿不到 token：git remote 里没有 ghp_。")
    return out


H = {}  # 每次调用前填 Authorization


def api(path):
    req = urllib.request.Request(REPO_API + path, headers=H)
    return json.load(urllib.request.urlopen(req, timeout=60))


def api_paged(path, key=None):
    """按页收全量（每页 100，短一页即到底）。"""
    items, page = [], 1
    while True:
        sep = "&" if "?" in path else "?"
        body = api(f"{path}{sep}per_page=100&page={page}")
        chunk = body[key] if key else body
        items.extend(chunk)
        if len(chunk) < 100:
            return items
        page += 1


def load_csv():
    feats = {}
    with open("docs/FEATURE-LEDGER.csv", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            feats[int(row["feature_no"])] = row
    return feats


def main():
    global H
    tok = token()
    H = {"Authorization": "token " + tok, "User-Agent": "hrt-artifact-ledger",
         "Accept": "application/vnd.github+json"}

    feats = load_csv()
    rels = api_paged("/releases")
    runs = api_paged("/actions/runs", key="workflow_runs")

    # ---- 包账拆分：功能包 vs 自检包 ----
    feat_rels = {}   # 功能号 -> release
    self_rels = []   # 自检 release
    for r in rels:
        m = FEAT_TAG.match(r["tag_name"])
        if m:
            feat_rels[int(m.group(1))] = r
            continue
        if SELF_TAG.match(r["tag_name"]):
            self_rels.append(r)

    # ---- 证据账：run 名里的功能号；同名多次发的全留（选构建 run 时再挑）----
    runs_by_feat = {}   # 功能号 -> [run, ...]（历史上有重发/取消重试，不能只留一条）
    named_total = 0
    for r in runs:
        m = RUN_NAME.match(r.get("name") or "")
        if m:
            runs_by_feat.setdefault(int(m.group(1)), []).append(r)
            named_total += 1

    def pick_build_run(no, rel):
        """选构建 run：优先 success 且创建时间不晚于 release 的最近一条；都不成就取最新一条（红着记）。
        早期（10-02 功能 1-10 批）存在取消后重发，最新一条未必是造出 Release 的那条。"""
        cands = runs_by_feat.get(no, [])
        if not cands:
            return None
        ok = [r for r in cands if r["conclusion"] == "success"]
        if ok:
            if rel:
                before = [r for r in ok if r["created_at"] <= rel["created_at"]]
                pool = before or ok
            else:
                pool = ok
            return max(pool, key=lambda r: r["created_at"])
        return max(cands, key=lambda r: r["created_at"])

    evid = {}        # 功能号 -> (artifact 名, 是否过期)——取自选中的构建 run
    for no, cands in runs_by_feat.items():
        r = pick_build_run(no, feat_rels.get(no))
        if not r:
            continue
        arts = api_paged(f"/actions/runs/{r['id']}/artifacts", key="artifacts")
        if arts:
            a = arts[0]
            evid[no] = (a["name"], a["expired"])

    # ---- 对账 ----
    orphans_pkg = sorted(set(feat_rels) - set(feats))          # 有包无账：漏记账
    orphans_led = sorted(set(feats) - set(feat_rels))          # 有账无包：早期条目正常态
    red_feat_runs = []   # (功能号, run)——所有非 success 的功能 run，红 run=定位坐标不许删
    for no, cands in runs_by_feat.items():
        for r in cands:
            if r["conclusion"] not in ("success", None):
                red_feat_runs.append((no, r))
    red_feat_runs.sort(key=lambda x: x[1]["run_number"])

    now = dt.datetime.now(dt.timezone(dt.timedelta(hours=8)))
    L = []
    L.append("# Artifact 统一账本（功能 · 包 · 证据 对齐表）")
    L.append("")
    L.append(f"> 生成：{now:%Y-%m-%d %H:%M}（Asia/Shanghai）· 生成器：`tools/gen-artifact-ledger.py`（幂等，随时重跑）")
    L.append("> 数据源：`docs/FEATURE-LEDGER.csv` + GitHub API（releases / actions runs / artifacts）")
    L.append("> 规矩出处：一功能一包一存档（用户规矩：跑一次 CI 留一个包）；本表只登记事实，覆盖判定看审计报告。")
    L.append("")
    L.append("## 一、总览")
    L.append("")
    L.append(f"- 功能账：**{len(feats)}** 条（1-{max(feats)}）")
    L.append(f"- Release：**{len(rels)}** 个（功能包 {len(feat_rels)} · 常规自检 {len(self_rels)}）")
    L.append(f"- Actions run：**{len(runs)}** 个；其中功能名 run {named_total} 条（涉及 {len(runs_by_feat)} 个功能号）")
    L.append(f"- 对齐：功能包 {len(feat_rels) - len(orphans_pkg)}/{len(feat_rels)} 能对上功能账"
             + ("（有包无账 " + str(len(orphans_pkg)) + " 条，见对账区）" if orphans_pkg else "（全对上）"))
    L.append(f"- 无包功能：{len(orphans_led)} 条（包是两波：10-02 的 1-10 语言批与 805 起的实装批；"
             f"其余条目存证=PR+commit，属正常态）")
    L.append("")

    L.append("## 二、功能包对照表（新到旧）")
    L.append("")
    L.append("| 功能号 | 功能名（账本） | 账本关联 | 构建 run | Release（APK） | 源码 | 包日期 | 证据（逐测试 XML） |")
    L.append("|---|---|---|---|---|---|---|---|")
    for no in sorted(feat_rels, reverse=True):
        rel, row = feat_rels[no], feats.get(no)
        # CSV 老行 7 列、新行 6 列（历史错位），PR#N 整行扫才稳；空列是 None 要滤掉
        pr = PR_REF.search(" ".join(v for v in row.values() if v)) if row else None
        pr_s = f"[PR#{pr.group(1)}]({REPO_URL}/pull/{pr.group(1)})" if pr else "—"
        run = pick_build_run(no, rel)
        run_s = f"[#{run['run_number']}]({run['html_url']})" if run else "—"
        apk = next((a for a in rel["assets"] if a["name"].endswith("signed.apk")), None)
        apk_s = f"[{apk['name']}]({apk['browser_download_url']})" if apk else "无资产"
        m = FEAT_TAG.match(rel["tag_name"])
        sha = m.group(2)
        name_s = (row["功能名"] if row else "**账上无此条**")
        ev = evid.get(no)
        if ev:
            ev_s = ev[0] + ("（**已过期**）" if ev[1] else "")
            if run and not ev[1]:
                ev_s = f"[{ev_s}]({run['html_url']})"
        else:
            ev_s = "—"
        L.append(f"| {no} | {name_s} | {pr_s} | {run_s} | [{rel['tag_name']}]({rel['html_url']}) · {apk_s} "
                 f"| [{sha[:7]}]({REPO_URL}/commit/{sha}) "
                 f"| {rel['created_at'][:10]} | {ev_s} |")
    L.append("")

    L.append("## 三、对账区（闭合审计入口）")
    L.append("")
    L.append("### 有包无账（Release 有功能 tag，功能账没这行——漏记账，要补）")
    L.append("")
    if orphans_pkg:
        for no in orphans_pkg:
            L.append(f"- 功能 {no}：tag `{feat_rels[no]['tag_name']}`——补进 FEATURE-LEDGER.csv")
    else:
        L.append("- 无：功能包全部有账。")
    L.append("")
    L.append("### 功能名 run 非 success（红 run=定位坐标，不许删；多数功能后来已绿）")
    L.append("")
    if red_feat_runs:
        L.append(f"共 {len(red_feat_runs)} 条（涉及 {len(set(no for no, _ in red_feat_runs))} 个功能号）：")
        L.append("")
        for no, r in red_feat_runs:
            L.append(f"- 功能 {no}：run [{r['run_number']}]({r['html_url']}) 结论 {r['conclusion']}")
    else:
        L.append("- 无：功能名 run 全绿。")
    L.append("")
    L.append("### 常规自检包（样例，最新 5 个；全量见 Releases 页）")
    L.append("")
    for r in sorted(self_rels, key=lambda x: x["created_at"], reverse=True)[:5]:
        m = SELF_TAG.match(r["tag_name"])
        L.append(f"- run [{m.group(2)}]({r['html_url']}) · `{r['tag_name']}` · {r['created_at'][:10]}")
    L.append("")

    L.append("## 四、怎么用")
    L.append("")
    L.append("1. 找某个功能的安装包：查第二节表，点 Release 列的 APK 链接直接下。")
    L.append("2. 审计某功能的测试证据：点证据列进 run，下载「构建包」artifact（日志/ 里逐测试 XML，run 309 起有）。")
    L.append("3. 本表过期了：仓根跑 `python3 tools/gen-artifact-ledger.py`，整文件覆盖后随任意 PR 提交。")
    L.append("4. CI 不自动改这份文件（红线一：构建时不改源码）——它的保鲜靠「发功能包后顺手重跑生成器」。")
    L.append("")

    with open(OUT_PATH, "w", encoding="utf-8") as f:
        f.write("\n".join(L) + "\n")
    print(f"已生成 {OUT_PATH}：功能包 {len(feat_rels)} 个、对账孤儿 {len(orphans_pkg)} 条、红功能 run {len(red_feat_runs)} 条")
    print(f"自检包 {len(self_rels)} 个（表里列最新 5 个样例）")


if __name__ == "__main__":
    main()
