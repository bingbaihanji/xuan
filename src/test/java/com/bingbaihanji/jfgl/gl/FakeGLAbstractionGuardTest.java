package com.bingbaihanji.jfgl.gl;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 钉住假实现的<strong>护栏面</strong>。
 *
 * <p>{@code FakeGLAbstraction} 的价值全在"测试里真调到了不该调的东西就炸"。
 * 而<strong>卸掉护栏没有任何症状</strong>：把某个方法的 {@code throw} 换成空实现，
 * 测试照样全绿，直到某天一条走偏的代码路径静默通过。
 *
 * <p>所以这里把"哪些抛、哪些实现"写成两向断言。将来某个任务想放开一个方法，
 * 必须同时改这张清单——于是那次放开是一次<strong>可见的</strong>代码改动，
 * 而且会在计划里留下理由。这是本项目"护栏要能被观测"这条纪律的机器化。
 *
 * <p>两条断言各管一件不同的事：
 * <ul>
 *   <li>{@link #假实现的方法分类不得静默变化} 管<strong>清单的完整性</strong>——
 *       任何新出现的、没被归类的 GL 方法都会让它失败。</li>
 *   <li>{@link #MUST_THROW里的方法必须真的抛异常} 管<strong>清单的诚实性</strong>——
 *       光有清单挡不住"清单里列着、方法体却被悄悄掏空"。这一条才真正拦住
 *       "把 throw 换成空实现"的那种改动，而那种改动在单测里是零症状的。</li>
 * </ul>
 */
class FakeGLAbstractionGuardTest {

    /** 必须抛 {@link UnsupportedOperationException} 的方法签名。 */
    private static final Set<String> MUST_THROW = Set.of(
            "initialize()", "clear(Color)", "setViewport(int,int,int,int)",
            "createVao()", "bindVao(int)", "deleteVao(int)",
            "uploadVboData(int[])",          // 注意：float[] 重载是"记录"，不是抛
            "uploadVboBytes(ByteBuffer)",
            "drawArrays(int,int,int)", "drawElements(int,int)",
            "enableBlend()", "disableBlend()", "setBlendFunc(int,int)",
            "createShader(String,String)", "createTexture(int,int,int[])",
            "setVertexAttribDivisor(int,int)", "drawArraysInstancedBaseInstance(int,int,int,int,int)");

    /**
     * 真正实现或记录的方法签名（**不**抛 {@link UnsupportedOperationException} 的那些）。
     *
     * <p>措辞要准确：这一组<strong>并非"都不抛异常"</strong>——
     * {@code clearIntegerColor}（裁剪开着时）与 {@code uploadR8SubImage}（纹理不存在时）
     * 都会抛 {@link AssertionError}，{@code uploadVboSubData} 与
     * {@code uploadVboData(float[])} 也会（VBO 名不合法或越界时）。
     * 本清单管的是"护栏怎么分类"，不是"这个方法绝不失败"。
     */
    private static final Set<String> IMPLEMENTED = Set.of(
            // 拾取路径
            "createFramebuffer()", "bindFramebuffer(int)", "deleteFramebuffer(int)",
            "currentFramebufferBinding()", "createIntegerTexture(int,int)",
            "deleteTexture(int)", "createR8Texture(int,int)",
            "uploadR8SubImage(int,int,int,int,int,byte[])",
            "attachTextureToColor0(int)", "framebufferStatus()", "clearIntegerColor(int)",
            "isScissorEnabled()", "setScissorEnabled(boolean)",
            "readUnsignedIntPixel(int,int)", "readUnsignedIntPixels(int,int,int,int,int[])",
            // 生命周期：空实现，无资源可释放（不属上面任何一类，也不抛）
            "dispose()",
            // 顶点缓冲路径（图表后端）
            "createVbo()", "bindVbo(int)", "deleteVbo(int)",
            "uploadVboData(float[])", "uploadVboSubData(int,ByteBuffer)");

    private static String signature(Method m) {
        return m.getName() + Arrays.stream(m.getParameterTypes())
                .map(Class::getSimpleName)
                .collect(Collectors.joining(",", "(", ")"));
    }

    /**
     * 假实现声明的、<strong>属于 {@link GLAbstraction} 接口</strong>的那些方法。
     *
     * <p>刻意只取接口方法：{@code setUserPixel} / {@code setScreenBottomUp} /
     * {@code maybeThrow} 这类测试便捷入口不是护栏面的一部分——它们本来就该实现，
     * 把它们塞进 {@code IMPLEMENTED} 只会让"这个清单在说什么"变模糊。
     * 而接口方法一个都跑不掉：类必须实现全部接口方法，
     * 所以任何新增的接口方法都必然出现在这里、必然要求被归类。
     */
    private static Map<String, Method> declaredGlMethods() {
        Map<String, Method> bySignature = new LinkedHashMap<>();
        for (Method m : FakeGLAbstraction.class.getDeclaredMethods()) {
            if (m.isSynthetic()) {
                continue;
            }
            try {
                GLAbstraction.class.getMethod(m.getName(), m.getParameterTypes());
            } catch (NoSuchMethodException notAnInterfaceMethod) {
                continue;
            }
            bySignature.put(signature(m), m);
        }
        return bySignature;
    }

    @Test
    void 假实现的方法分类不得静默变化() {
        Set<String> actual = declaredGlMethods().keySet();

        Set<String> missing = new TreeSet<>(MUST_THROW);
        missing.removeAll(actual);
        assertTrue(missing.isEmpty(), "清单里的这些方法在假实现里不存在（改名了？）：" + missing);

        Set<String> undocumented = new TreeSet<>(actual);
        undocumented.removeAll(MUST_THROW);
        undocumented.removeAll(IMPLEMENTED);
        assertTrue(undocumented.isEmpty(),
                "假实现里有方法既不在 MUST_THROW 也不在 IMPLEMENTED 里：" + undocumented
                        + "。新加一个不抛异常的方法意味着卸掉了一处护栏——"
                        + "若是有意的，把它加进 IMPLEMENTED 并说明理由。");

        Set<String> overImplemented = new TreeSet<>(IMPLEMENTED);
        overImplemented.removeAll(actual);
        assertTrue(overImplemented.isEmpty(),
                "IMPLEMENTED 里列了假实现其实没有的方法：" + overImplemented);
    }

    @Test
    void MUST_THROW里的方法必须真的抛异常() {
        Map<String, Method> declared = declaredGlMethods();
        FakeGLAbstraction fake = new FakeGLAbstraction();

        for (String sig : new TreeSet<>(MUST_THROW)) {
            Method m = declared.get(sig);
            assertNotNull(m, "清单里的 " + sig + " 在假实现里找不到");
            // 用默认参数调用即可：这些方法体就是一句 throw，不会去碰参数。
            assertThrows(UnsupportedOperationException.class,
                    () -> invokeWithDefaults(m, fake),
                    sig + " 不再抛 UnsupportedOperationException 了——护栏被悄悄卸掉了。"
                            + "若是有意放开，请把它移到 IMPLEMENTED 并说明理由。");
        }
    }

    /**
     * 定容与子上传都要求当前绑定的确实是一个<b>活着</b>的 VBO。
     *
     * <p>这是本项目那条纪律的直接应用：上一轮堵住了"越界"，却漏了"名字压根不合法"——
     * 而后者会把容量记在 0 名下，随后越界断言<strong>全部放行</strong>。
     * 不被执行的守卫等于没有守卫，所以这里把三条分支（从未绑定 / 从未创建 / 建了又删）
     * 逐一钉住。
     *
     * <p>末尾的反向断言同样必要：没有它，前面几条"抛 AssertionError"可能只是恒抛。
     */
    @Test
    void 未绑定活着的VBO时定容与子上传必须显式失败() {
        FakeGLAbstraction fake = new FakeGLAbstraction();
        int live = fake.createVbo();
        int spare = fake.createVbo();
        ByteBuffer payload = ByteBuffer.allocate(16);

        // 1) 从未 bindVbo：boundVbo 保持 0，而 0 永远不是合法名字
        assertThrows(AssertionError.class, () -> fake.uploadVboSubData(0, payload));
        // 定容这条最关键：不拦的话容量会记到 0 名下，越界断言从此全部放行
        assertThrows(AssertionError.class, () -> fake.uploadVboData(new float[4]));

        // 2) 绑到一个从未创建的名字
        fake.bindVbo(9999);
        assertThrows(AssertionError.class, () -> fake.uploadVboSubData(0, payload));

        // 3) 建了又删——只查 createdVbos 拦不住这一条
        fake.bindVbo(live);
        fake.deleteVbo(live);
        assertThrows(AssertionError.class, () -> fake.uploadVboSubData(0, payload));

        // 反向：合法流程必须放行，且边界（offset + bytes == 容量）恰好通过
        fake.bindVbo(spare);
        fake.uploadVboData(new float[8]);
        fake.uploadVboSubData(0, ByteBuffer.allocate(32));
        assertEquals(List.of(spare + "@0:32"), fake.vboSubDataCalls);
        // 再多一个字节才越界
        assertThrows(AssertionError.class, () -> fake.uploadVboSubData(0, ByteBuffer.allocate(33)));
    }

    private static void invokeWithDefaults(Method m, FakeGLAbstraction target) {
        try {
            m.invoke(target, defaultArgs(m));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("MUST_THROW 的方法抛了受检异常：" + m, cause);
        } catch (IllegalAccessException e) {
            throw new AssertionError("无法调用 " + m + "（可见性变了？）", e);
        }
    }

    private static Object[] defaultArgs(Method m) {
        Class<?>[] types = m.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            args[i] = defaultValue(types[i]);
        }
        return args;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == int.class) return 0;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        throw new AssertionError("未知的原始类型：" + type);
    }
}
