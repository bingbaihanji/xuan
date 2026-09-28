#!/usr/bin/env bash
#
# MSAA 校验：跑 `MsaaVerifier` **三次**（msaa=0 / 4 / **-1**）并比对三份读数。
#
# 为什么要有 `-1` 那次：按 openglfx 的约定负数 = "用最大采样数"（**不是"关"**），
# 所以它又是一条多采样路径，而它**最容易把守卫判错**（写成 `msaa <= 0` 就漏了它）。
# 只跑 0 与 4 时那一档**永远看不到**。
#
# 为什么要一个脚本：**一个进程只能有一个 msaa 值**——采样数是帧缓冲的属性，
# 而帧缓冲在 `GLCanvas` 构造时就建好了（`GLCanvas` 没有 setter，实测）。
# 于是"`msaa=4` 的过渡像素比 `msaa=0` 多"这条判据**只能跨进程比**：
# 每个进程把自己的读数打成一行 `MSAA_READING`，本脚本解析三行、比对。
#
# 用法（仓库根或任意目录都行）：
#     bash xuan-javafx/scripts/msaa-verify.sh
#
# 退出码：0 = 两次都通过且两条跨进程判据成立；非 0 = 有失败（清单打印在末尾）。
#
# ⚠️ 前置：`xuan-render-gl` 必须已经 `install` 过（校验器从**本地仓库**解析它）：
#     mvn -o install -DskipTests
#     改了 xuan-render-gl 却只 compile 的话，跑出来的是**旧版本**，而输出会
#     "完全一致地失败"，看起来像校验器飘——见 CLAUDE.md 的元规则。
#
# ⚠️ 两个编码开关都要带（`-Dstdout.encoding` 与 `-Dstderr.encoding` 是**两个**独立属性，
#     默认都是 GBK）：不加的话中文断言与失败清单全是乱码，而"读不出原因"正是这些
#     校验器存在的一半理由。

set -u

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)

# 临时目录 + 退出清理。用 trap 而不是"跑完手动删"：脚本中途失败（或被 Ctrl-C）时
# 也会走到这里——手动删只覆盖成功那条路。
OUT_DIR=$(mktemp -d)
trap 'rm -rf "$OUT_DIR"' EXIT INT TERM

# 跑一次并抓取读数那一行。$1 = msaa 值，$2 = 日志文件。
run_one() {
    local msaa="$1" log="$2"
    echo "--- 运行 MsaaVerifier（-Dxuan.probe.msaa=$msaa） ---"
    (cd "$REPO_ROOT" && mvn -o -f xuan-javafx/pom.xml compile exec:exec \
        "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
        "-Dexec.args=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dxuan.probe.msaa=$msaa -cp %classpath com.bingbaihanji.xuan.example.MsaaVerifier") \
        >"$log" 2>&1
    local code=$?
    # 只印校验器自己那一段（Maven 的编译日志没有判据价值）。找不到分节标题就全印，
    # 否则"校验器根本没跑到"这件事会在日志里**看不见**。
    if grep -q '=== MSAA 校验 ===' "$log"; then
        sed -n '/=== MSAA 校验 ===/,$p' "$log"
    else
        echo "★ 没有找到「=== MSAA 校验 ===」分节——校验器没有跑到读数阶段，完整日志："
        cat "$log"
    fi
    return $code
}

# 从一行 `MSAA_READING` 里取一个字段。
field() {  # $1 = 行，$2 = 键
    printf '%s\n' "$1" | tr ' ' '\n' | sed -n "s/^$2=//p" | head -1
}

FAILURES=()

run_one 0 "$OUT_DIR/msaa-0.log"
CODE0=$?
run_one 4 "$OUT_DIR/msaa-4.log"
CODE4=$?
# ★ **第三次跑 `-1`**：按 openglfx 的约定它是"用最大采样数"（实现
#   `msaa < 0 -> Framebuffer.MultiSampled(..., GL_MAX_SAMPLES)`），**不是"关"**
#   ⇒ 它同样要多采样、同样让 `glReadPixels` 非法、读数应当与 `msaa=4` 那次同构。
#   加这一跑是因为：**负数是守卫最容易判错的那一档**（写成 `msaa <= 0` 就会把它
#   当成"可以回读"），而只跑 0 与 4 时**永远看不到它**。
run_one -1 "$OUT_DIR/msaa-neg1.log"
CODENEG=$?

LINE0=$(grep -m1 '^MSAA_READING ' "$OUT_DIR/msaa-0.log" || true)
LINE4=$(grep -m1 '^MSAA_READING ' "$OUT_DIR/msaa-4.log" || true)
LINENEG=$(grep -m1 '^MSAA_READING ' "$OUT_DIR/msaa-neg1.log" || true)

echo
echo "================ 跨进程比对 ================"
echo "msaa=0  读数：${LINE0:-（缺）}"
echo "msaa=4  读数：${LINE4:-（缺）}"
echo "msaa=-1 读数：${LINENEG:-（缺）}"
echo

# 每次运行自己的退出码：它反映的是那一次里全部断言的成败。
[ "$CODE0" -eq 0 ] || FAILURES+=("msaa=0 那次运行的退出码是 $CODE0（非 0）")
[ "$CODE4" -eq 0 ] || FAILURES+=("msaa=4 那次运行的退出码是 $CODE4（非 0）")
[ "$CODENEG" -eq 0 ] || FAILURES+=("msaa=-1 那次运行的退出码是 $CODENEG（非 0）")

# ★ 读数行缺失必须**响亮失败**，不能当成 0 继续比：缺失意味着校验器没跑到读数阶段
#   （比如窗口没开出来），而"把缺读数当 0"会让下面的比较给出看不懂的结论。
if [ -z "$LINE0" ] || [ -z "$LINE4" ] || [ -z "$LINENEG" ]; then
    FAILURES+=("没有解析到 MSAA_READING 行（0: '${LINE0:-缺}'，4: '${LINE4:-缺}'，-1: '${LINENEG:-缺}'）——校验器没有跑到读数阶段")
else
    # 采样数真的生效了吗？只有它对了，下面两条比的才是"同一个几何在几种采样数下"。
    [ "$(field "$LINE0" msaa)" = "0" ] || FAILURES+=("msaa=0 那次的读数行里 msaa=$(field "$LINE0" msaa)（配置没生效）")
    [ "$(field "$LINE4" msaa)" = "4" ] || FAILURES+=("msaa=4 那次的读数行里 msaa=$(field "$LINE4" msaa)（配置没生效）")
    [ "$(field "$LINENEG" msaa)" = "-1" ] || FAILURES+=("msaa=-1 那次的读数行里 msaa=$(field "$LINENEG" msaa)（配置没生效）")

    F0=$(field "$LINE0" fringe)
    C0=$(field "$LINE0" core)

    # ★ 两条判据对**每一个非 0 的采样数**都成立（`4` 与 `-1` 各比一次）——
    #   `-1` 不是"第三种行为"，它就是多采样（openglfx 的 `-1 = 最大采样数`）。
    for pair in "$LINE4:4" "$LINENEG:-1"; do
        line="${pair%:*}"; tag="${pair##*:}"
        F=$(field "$line" fringe)
        C=$(field "$line" core)
        if [ -z "$F0" ] || [ -z "$C0" ] || [ -z "$F" ] || [ -z "$C" ]; then
            FAILURES+=("读数行里缺 fringe/core 字段（msaa=$tag 那次：'$line'）")
            continue
        fi
        # ★ 判据一：MSAA 真的画出了过渡像素，而且**比硬边多**。
        #   （两个方向都写进同一条：`>` 同时排除了"两者都是 0"与"MSAA 反而更少"。）
        if [ "$F" -gt "$F0" ]; then
            echo "[PASS] ★ 跨进程① msaa=$tag 的过渡像素($F) > msaa=0 的($F0)"
        else
            FAILURES+=("★ 跨进程① msaa=$tag 的过渡像素($F) 必须 > msaa=0 的($F0)")
            echo "[FAIL] ★ 跨进程① msaa=$tag 的过渡像素($F) 未超过 msaa=0 的($F0)"
        fi
        # ★ 判据二：线心（3 行）的纯色像素数**各模式精确相等**。
        #   它是"MSAA 没有让线心移位/变淡"——缺了它，"过渡像素变多"可以靠"整条线糊掉"来满足。
        if [ "$C" -eq "$C0" ]; then
            echo "[PASS] ★ 跨进程② 线心纯色像素数相等（msaa=0: $C0，msaa=$tag: $C）"
        else
            FAILURES+=("★ 跨进程② 线心纯色像素数必须相等（msaa=0: $C0，msaa=$tag: $C）")
            echo "[FAIL] ★ 跨进程② 线心纯色像素数不等（msaa=0: $C0，msaa=$tag: $C）"
        fi
    done
fi

echo
if [ "${#FAILURES[@]}" -eq 0 ]; then
    echo "=== MSAA 跨进程校验全部通过 ==="
    exit 0
fi
echo "=== MSAA 跨进程校验失败 ${#FAILURES[@]} 项 ==="
for f in "${FAILURES[@]}"; do
    echo "  [FAIL] $f"
done
exit 1
