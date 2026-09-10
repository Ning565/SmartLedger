#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_a11y_config.py —— 无障碍服务配置的编译产物校验（P0-1 长期防护）。

背景：payment_accessibility_service.xml 的 packageNames 若带前导空白，
AOSP 的 AccessibilityServiceInfo 用 split("(\\s)*,(\\s)*") 解析且不 trim，
第一个包名会带空格而永不匹配 → 微信侧静默失效（不崩溃、不报错）。
这类「配置与预期不一致」的问题在源码里看不出来，必须对编译产物校验 ——
与 tools/verify_migration.py 校验 Room 迁移是同一个思路。

校验项：
  1. APK 内 res/xml/payment_accessibility_service.xml 的 packageNames
     值 == 代码常量 WECHAT_PACKAGE/ALIPAY_PACKAGE 的拼接（逐字符）
  2. packageNames 值不含任何空白字符
  3. accessibilityEventTypes 编译为 0x820（typeWindowStateChanged|typeWindowContentChanged）
  4. accessibilityFlags 含 flagRetrieveInteractiveWindows / flagIncludeNotImportantViews
     / flagReportViewIds —— 缺 flagRetrieveInteractiveWindows 时
     AccessibilityService.getWindows() 返回空且不报错，「多窗口遍历」会静默空转
     （debug.4 真机三场景全不记账的根因），属于同一类「配置与预期不一致」的坑

用法：
  python3 tools/verify_a11y_config.py [apk路径]
  （默认 app/build/outputs/apk/debug/app-debug.apk，需先 assembleDebug）
"""

import os
import re
import subprocess
import sys
import glob

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

WECHAT_PACKAGE = "com.tencent.mm"
ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
EXPECTED_PACKAGE_NAMES = f"{WECHAT_PACKAGE},{ALIPAY_PACKAGE}"

# AOSP AccessibilityServiceInfo 的 flag 位（只校验「必须有」，不做全等 ——
# 以后再加 flag 不该让这个脚本失败）
FLAG_INCLUDE_NOT_IMPORTANT_VIEWS = 0x00000002
FLAG_REPORT_VIEW_IDS = 0x00000010
FLAG_RETRIEVE_INTERACTIVE_WINDOWS = 0x00000040

REQUIRED_ACCESSIBILITY_FLAGS = {
    "flagRetrieveInteractiveWindows": FLAG_RETRIEVE_INTERACTIVE_WINDOWS,
    "flagIncludeNotImportantViews": FLAG_INCLUDE_NOT_IMPORTANT_VIEWS,
    "flagReportViewIds": FLAG_REPORT_VIEW_IDS,
}

# 从源码常量再读一遍做交叉校验（防止脚本与代码各自漂移）
SOURCE = os.path.join(
    REPO, "app/src/main/java/com/smartledger/service/accessibility/AccessibilityFingerprintBuilder.kt"
)

FAILURES = []


def fail(msg: str):
    FAILURES.append(msg)
    print(f"  ❌ {msg}")


def ok(msg: str):
    print(f"  ✅ {msg}")


def read_source_constants():
    with open(SOURCE, encoding="utf-8") as f:
        src = f.read()
    wechat = re.search(r'WECHAT_PACKAGE = "([^"]+)"', src)
    alipay = re.search(r'ALIPAY_PACKAGE = "([^"]+)"', src)
    if not wechat or not alipay:
        fail("无法从 AccessibilityFingerprintBuilder.kt 提取包名常量")
        return None, None
    return wechat.group(1), alipay.group(1)


def find_aapt2():
    candidates = [
        os.environ.get("AAPT2"),
        os.path.join(os.environ.get("ANDROID_HOME", ""), "build-tools"),
    ]
    # macOS brew / 常规 SDK 布局
    roots = [
        os.environ.get("ANDROID_HOME") or "",
        os.environ.get("ANDROID_SDK_ROOT") or "",
        "/opt/homebrew/share/android-commandlinetools",
        os.path.expanduser("~/Library/Android/sdk"),
    ]
    # local.properties 的 sdk.dir 优先级最高（与构建一致）
    try:
        with open(os.path.join(REPO, "local.properties"), encoding="utf-8") as f:
            for line in f:
                if line.startswith("sdk.dir="):
                    roots.insert(0, line.split("=", 1)[1].strip())
                    break
    except FileNotFoundError:
        pass
    for root in roots:
        if root:
            bt = os.path.join(root, "build-tools")
            if os.path.isdir(bt):
                candidates.append(bt)
    for c in candidates:
        if not c:
            continue
        if os.path.isfile(c):
            return c
        if os.path.isdir(c):
            found = sorted(glob.glob(os.path.join(c, "*", "aapt2")))
            if found:
                return found[-1]
    return None


def dump_xmltree(aapt2: str, apk: str, res_path: str) -> str:
    out = subprocess.run(
        [aapt2, "dump", "xmltree", "--file", res_path, apk],
        capture_output=True, text=True,
    )
    if out.returncode != 0:
        raise RuntimeError(f"aapt2 dump failed: {out.stderr[:500]}")
    return out.stdout


def extract_attribute(tree: str, attr_name: str):
    """从 xmltree 输出提取属性原始值（字符串带引号，int/bool 不带，两者都支持）。
    实际格式：A: http://schemas.android.com/apk/res/android:packageNames(0x...)="..."
    （aapt2 用完整命名空间 URI，不是 android: 短前缀）
    """
    m = re.search(
        r'A:\s+(?:http://schemas\.android\.com/apk/res/android:|android:)' +
        re.escape(attr_name) + r'\([^)]*\)=("?)([^"\n]*)\1',
        tree,
    )
    return m.group(2).strip() if m else None


def parse_int(value):
    """把 aapt2 输出的属性值解析成 int。

    flag 类属性编译后是 int，aapt2 可能输出 `0x00000052` 或十进制；
    字符串属性带引号，这里一律返回 None（调用方按「找不到」处理）。
    """
    if value is None:
        return None
    v = value.strip()
    if not v or v.startswith('"'):
        return None
    for base in (0, 10):
        try:
            return int(v, base)
        except ValueError:
            continue
    return None


def main():
    print("═══ 无障碍配置编译产物校验（P0-1 防护）═══")

    # ── 0. 交叉校验：脚本内置常量 vs 源码常量 ──
    wechat, alipay = read_source_constants()
    if wechat and alipay:
        if wechat == WECHAT_PACKAGE and alipay == ALIPAY_PACKAGE:
            ok(f"源码常量与校验基线一致：{wechat} / {alipay}")
        else:
            fail(f"源码常量漂移：{wechat} / {alipay}（请同步更新本脚本基线）")

    # ── 1. 定位 APK ──
    apk = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        REPO, "app/build/outputs/apk/debug/app-debug.apk"
    )
    if not os.path.isfile(apk):
        print(f"  ⚠ 未找到 APK（{apk}），先运行 assembleDebug")
        sys.exit(2)

    aapt2 = find_aapt2()
    if not aapt2:
        print("  ⚠ 未找到 aapt2（检查 ANDROID_HOME / build-tools）")
        sys.exit(2)

    # ── 2. packageNames 逐字符校验 ──
    tree = dump_xmltree(aapt2, apk, "res/xml/payment_accessibility_service.xml")
    raw = extract_attribute(tree, "packageNames")
    if raw is None:
        fail("APK 中找不到 packageNames 属性")
    else:
        if raw == EXPECTED_PACKAGE_NAMES:
            ok(f"packageNames 逐字符一致：{raw}")
        else:
            fail(
                f"packageNames 不符！编译产物=<{raw}>（长度 {len(raw)}），"
                f"期望=<{EXPECTED_PACKAGE_NAMES}>（长度 {len(EXPECTED_PACKAGE_NAMES)}）"
            )
        if re.search(r"\s", raw):
            fail(f"packageNames 含空白字符：{raw!r}")
        else:
            ok("packageNames 无空白字符")

    # ── 3. accessibilityEventTypes flag 校验 ──
    flags = extract_attribute(tree, "accessibilityEventTypes")
    if flags is not None:
        # 0x820 = typeWindowStateChanged(0x800) | typeWindowContentChanged(0x20)
        if "0x00000820" in flags.lower() or "0x820" in flags.lower():
            ok(f"accessibilityEventTypes = {flags.strip()}（两种窗口事件）")
        else:
            fail(f"accessibilityEventTypes 异常：{flags}")

    # ── 4. accessibilityFlags 位校验 ──
    raw_flags = extract_attribute(tree, "accessibilityFlags")
    value = parse_int(raw_flags)
    if value is None:
        fail(f"APK 中找不到 / 无法解析 accessibilityFlags（原值 <{raw_flags}>）")
    else:
        ok(f"accessibilityFlags 编译为 0x{value:08x}")
        for name, bit in REQUIRED_ACCESSIBILITY_FLAGS.items():
            if value & bit:
                ok(f"  含 {name} (0x{bit:x})")
            else:
                fail(
                    f"  缺 {name} (0x{bit:x})！"
                    + (
                        "（缺它时 getWindows() 返回空且不报错，多窗口遍历会静默空转）"
                        if name == "flagRetrieveInteractiveWindows"
                        else ""
                    )
                )

    print()
    if FAILURES:
        print(f"结果：{len(FAILURES)} 项失败 —— 配置会被系统错误解析，禁止发布")
        sys.exit(1)
    print("结果：全部通过")


if __name__ == "__main__":
    main()
