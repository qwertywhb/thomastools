#!/usr/bin/env python3
"""一键发版：自增版本号 -> 构建 release -> 生成 update.json -> 建 GitHub Release 并上传。

Repository（owner/repo）**不在这里硬编码**，而是运行时从
`app/src/main/java/com/example/jingcai/data/update/UpdateConfig.kt` 读，
保证「App 检查更新的仓库」和「脚本上传 APK 的仓库」永远是同一个 —— 这两者
一旦分叉，用户装的新包就永远收不到更新提示。

## 用法

    # 正常发版：versionCode +1，构建并上传（最常用）
    python scripts/release.py --changelog "1. 修复篮球主客反了 2. 账单长按复制"

    # 不改版本号，只重打包（客户端靠 sha256 识别「同版本新包」）
    python scripts/release.py --changelog "只调了下间距" --no-bump

    # 只构建 + 生成产物，不上传（本地验证用）
    python scripts/release.py --changelog "预览" --dry-run

    # 跳过构建，直接用现有 app-release.apk（构建已经手动做过时）
    python scripts/release.py --changelog "重传" --skip-build

## 认证

优先读环境变量 `GITHUB_TOKEN`；没设就用 `git credential fill` 取本机已保存的
GitHub 凭据（Windows 上 Git Credential Manager 存的那份）。**不会把 token 写进任何文件。**

## 为什么必须一起上传 update.json

`UpdateConfig` 的注释里记着一个真实事故：v9 那个 Release 挂的是 v8 的 APK 和 v8 的
update.json，于是客户端判定「我还是最新」，新包永远不提示。所以本脚本的
`update.json` 与 APK **由同一次运行生成**，versionCode / sha256 不可能对不上。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[1]
BUILD_GRADLE = PROJECT / "app" / "build.gradle"
UPDATE_CONFIG = (
    PROJECT / "app" / "src" / "main" / "java" / "com" / "example" / "jingcai"
    / "data" / "update" / "UpdateConfig.kt"
)
RELEASE_APK = PROJECT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
DIST = PROJECT / "dist"

API = "https://api.github.com"
UPLOAD_API = "https://uploads.github.com"


# ---------------------------------------------------------------- 基础工具

def die(msg: str) -> None:
    print(f"\n[失败] {msg}", file=sys.stderr)
    sys.exit(1)


def step(msg: str) -> None:
    print(f"\n==> {msg}")


def read_text(p: Path) -> str:
    return p.read_text(encoding="utf-8")


def write_text(p: Path, s: str) -> None:
    p.write_text(s, encoding="utf-8", newline="")


# ---------------------------------------------------------------- 读配置

def read_update_config() -> tuple[str, str]:
    """从 UpdateConfig.kt 里取 OWNER / REPO —— 与 App 同源，避免发到别的仓库。"""
    if not UPDATE_CONFIG.is_file():
        die(f"找不到 {UPDATE_CONFIG}")
    src = read_text(UPDATE_CONFIG)
    owner = re.search(r'const\s+val\s+OWNER\s*=\s*"([^"]*)"', src)
    repo = re.search(r'const\s+val\s+REPO\s*=\s*"([^"]*)"', src)
    if not owner or not repo:
        die("UpdateConfig.kt 里没解析到 OWNER / REPO")
    o, r = owner.group(1).strip(), repo.group(1).strip()
    if not o or not r:
        die("UpdateConfig 的 OWNER / REPO 为空，App 根本不会检查更新")
    return o, r


def read_version() -> tuple[int, str]:
    src = read_text(BUILD_GRADLE)
    vc = re.search(r"^\s*versionCode\s+(\d+)\s*$", src, re.M)
    vn = re.search(r'^\s*versionName\s+"([^"]*)"\s*$', src, re.M)
    if not vc or not vn:
        die("app/build.gradle 里没找到 versionCode / versionName")
    return int(vc.group(1)), vn.group(1)


def bump_version() -> tuple[int, str]:
    """versionCode +1，versionName 同步成同号的 `1.<code>`（与历史命名一致）。"""
    src = read_text(BUILD_GRADLE)
    old_code, _ = read_version()
    new_code = old_code + 1
    new_name = f"1.{new_code}"

    # assert count == 1：多出同名行就说明文件被改过结构，宁可不改也不能改错
    pat_code = re.compile(r"(^\s*versionCode\s+)(\d+)(\s*$)", re.M)
    pat_name = re.compile(r'(^\s*versionName\s+")([^"]*)("\s*$)', re.M)
    if len(pat_code.findall(src)) != 1 or len(pat_name.findall(src)) != 1:
        die("build.gradle 里 versionCode/versionName 不是各一行，拒绝自动改")
    src = pat_code.sub(lambda m: f"{m.group(1)}{new_code}{m.group(3)}", src)
    src = pat_name.sub(lambda m: f"{m.group(1)}{new_name}{m.group(3)}", src)
    write_text(BUILD_GRADLE, src)
    print(f"    版本：{old_code} -> {new_code}（versionName {new_name}）")
    return new_code, new_name


def find_gradle() -> str:
    """gradlew -> 环境变量 -> PATH 里的 gradle，找不到就报错并给出提示。"""
    for name in ("gradlew.bat", "gradlew"):
        cand = PROJECT / name
        if cand.is_file():
            return str(cand)
    env = os.environ.get("GRADLE_BIN", "").strip()
    if env and Path(env).is_file():
        return env
    for name in ("gradle.bat", "gradle"):
        found = shutil.which(name)
        if found:
            return found
    die("找不到 gradle。请装 Android Studio 后设置环境变量 GRADLE_BIN=<gradle.bat 路径>，"
        "或在项目根生成 wrapper（gradle wrapper）")


# ---------------------------------------------------------------- 构建

def build() -> Path:
    gradle = find_gradle()
    step(f"构建 release 包（{Path(gradle).name}）")
    started = time.time()
    proc = subprocess.run([gradle, "assembleRelease", "-q"], cwd=str(PROJECT))
    if proc.returncode != 0:
        die("构建失败，看上面的 Gradle 输出")
    if not RELEASE_APK.is_file():
        die(f"构建成功但没找到产物：{RELEASE_APK}")
    # 产物必须是这次构建出来的，否则会把上一版 APK 当新版发出去（v9 事故的同类）
    if RELEASE_APK.stat().st_mtime < started - 5:
        die("app-release.apk 比本次构建开始时间还早，说明用的还是旧包，已中止")
    return RELEASE_APK


def sha256_of(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def prepare_assets(version_code: int, version_name: str, changelog: str,
                   force: bool, apk: Path) -> tuple[Path, Path, str]:
    DIST.mkdir(exist_ok=True)
    apk_name = f"jingcai-v{version_code}.apk"
    out_apk = DIST / apk_name
    shutil.copy2(apk, out_apk)

    digest = sha256_of(out_apk)
    payload = {
        "versionCode": version_code,
        "versionName": version_name,
        "apk": apk_name,
        "changelog": changelog,
        "sha256": digest,
        "force": force,
    }
    out_json = DIST / "update.json"
    write_text(out_json, json.dumps(payload, ensure_ascii=False, indent=2) + "\n")

    mb = out_apk.stat().st_size / (1024 * 1024)
    print(f"    APK : {apk_name}（{mb:.2f} MB）")
    print(f"    SHA256: {digest}")
    print(f"    JSON: {out_json}")
    return out_apk, out_json, digest


# ---------------------------------------------------------------- GitHub

def token() -> str:
    env = os.environ.get("GITHUB_TOKEN", "").strip()
    if env:
        return env
    try:
        out = subprocess.run(
            ["git", "credential", "fill"],
            cwd=str(PROJECT),
            input="protocol=https\nhost=github.com\n\n",
            capture_output=True,
            text=True,
            timeout=30,
        ).stdout
    except Exception:
        out = ""
    m = re.search(r"^password=(.+)$", out, re.M)
    if not m:
        die("没有 GitHub 凭据。设置环境变量 GITHUB_TOKEN=<token>，或先 git push 一次让凭据管理器记住")
    return m.group(1).strip()


def api(path: str, tok: str, method: str = "GET", body: dict | None = None,
        raw: bytes | None = None, ctype: str | None = None) -> tuple[int, dict]:
    url = path if path.startswith("http") else API + path
    data = None
    headers = {
        "Authorization": f"Bearer {tok}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "jingcai-release-script",
    }
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    elif raw is not None:
        data = raw
        headers["Content-Type"] = ctype or "application/octet-stream"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            payload = resp.read()
            return resp.status, (json.loads(payload) if payload else {})
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, {"message": text[:400]}


def ensure_release(owner: str, repo: str, tok: str, tag: str,
                   changelog: str) -> int:
    """取或建该 tag 的 Release，返回 release id。已存在就复用（重打包场景）。"""
    status, data = api(f"/repos/{owner}/{repo}/releases/tags/{tag}", tok)
    if status == 200:
        print(f"    Release {tag} 已存在，复用（id={data['id']}）")
        return int(data["id"])
    if status != 404:
        die(f"查询 Release 失败：HTTP {status} {data.get('message')}")

    body = {
        "tag_name": tag,
        "name": tag,
        # tag 数字 = versionCode，客户端就是拿它和本地比较的
        "body": changelog,
        "draft": False,
        "prerelease": False,
    }
    status, data = api(f"/repos/{owner}/{repo}/releases", tok, "POST", body=body)
    if status not in (200, 201):
        die(f"创建 Release 失败：HTTP {status} {data.get('message')}")
    print(f"    Release {tag} 已创建（id={data['id']}）")
    return int(data["id"])


def upload_asset(owner: str, repo: str, tok: str, release_id: int, f: Path) -> None:
    """先删同名旧资产再传 —— GitHub 不允许同名覆盖，重传时静默失败会很难查。"""
    status, assets = api(f"/repos/{owner}/{repo}/releases/{release_id}/assets?per_page=100", tok)
    if status == 200:
        for a in assets:
            if a.get("name") == f.name:
                api(f"/repos/{owner}/{repo}/releases/assets/{a['id']}", tok, "DELETE")
                print(f"    删除旧资产 {f.name}")

    ctype = "application/vnd.android.package-archive" if f.suffix == ".apk" else "application/json"
    url = (f"{UPLOAD_API}/repos/{owner}/{repo}/releases/{release_id}/assets"
           f"?name={urllib.parse.quote(f.name)}")
    status, data = api(url, tok, "POST", raw=f.read_bytes(), ctype=ctype)
    if status not in (200, 201):
        die(f"上传 {f.name} 失败：HTTP {status} {data.get('message')}")
    print(f"    上传完成 {f.name}（{data.get('size', 0)} 字节）")


# ---------------------------------------------------------------- 入口

def main() -> None:
    ap = argparse.ArgumentParser(description="构建并发布到 GitHub Releases（自动更新同源）")
    ap.add_argument("--changelog", default="", help="更新说明，会显示在 App 的更新弹窗里")
    ap.add_argument("--no-bump", action="store_true", help="不改 versionCode（只重打包）")
    ap.add_argument("--force", action="store_true", help="标记为强制更新")
    ap.add_argument("--skip-build", action="store_true", help="不重新构建，直接用现有 APK")
    ap.add_argument("--dry-run", action="store_true", help="只构建 + 生成产物，不上传")
    args = ap.parse_args()

    owner, repo = read_update_config()
    step(f"目标仓库 {owner}/{repo}（取自 UpdateConfig.kt）")

    if args.no_bump:
        code, name = read_version()
        print(f"    版本：保持 {code}（{name}）")
    else:
        code, name = bump_version()

    apk = RELEASE_APK if args.skip_build else build()
    if args.skip_build and not apk.is_file():
        die(f"--skip-build 但产物不存在：{apk}")

    step("生成 update.json 与待上传产物")
    changelog = args.changelog.strip() or "持续优化"
    out_apk, out_json, _ = prepare_assets(code, name, changelog, args.force, apk)

    if args.dry_run:
        step("dry-run：不上传")
        print(f"    会创建 Release tag=v{code}，资产 {out_apk.name} + update.json")
        print("    确认无误后去掉 --dry-run 重跑即可")
        return

    tok = token()
    tag = f"v{code}"
    step(f"发布 Release {tag}")
    rid = ensure_release(owner, repo, tok, tag, changelog)
    upload_asset(owner, repo, tok, rid, out_apk)
    upload_asset(owner, repo, tok, rid, out_json)

    print(f"\n完成：https://github.com/{owner}/{repo}/releases/tag/{tag}")
    print("手机上的 App 下次冷启动就会提示更新（同版本重打包靠 sha256 识别）。")
    print("\n别忘了把源码改动一起提交：git add -A && git commit -m '发版 v%d'" % code)


if __name__ == "__main__":
    main()
