package com.bingbaihanji.jfgl.renderer;

import com.bingbaihanji.jfgl.math.Vec2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 2D 路径构建器，通过一系列绘图命令构建路径。
 *
 * <p>使用示例：
 * <pre>{@code
 * Path path = Path.builder()
 *     .moveTo(0, 0)
 *     .lineTo(100, 0)
 *     .lineTo(100, 100)
 *     .close()
 *     .build();
 *
 * List<Vec2> vertices = path.toVertices(16);
 * }</pre>
 */
public final class Path {

    /** 路径命令列表 */
    private final List<PathCommand> commands;

    private Path(List<PathCommand> commands) {
        this.commands = Collections.unmodifiableList(commands);
    }

    /**
     * 创建一个新的空路径构建器。
     *
     * @return 新的构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 在参数 {@code t} 处计算二次贝塞尔曲线。
     *
     * @param p0 起点
     * @param p1 控制点
     * @param p2 终点
     * @param t  参数（0 到 1）
     * @return 曲线上的点
     */
    private static Vec2 quadBezier(Vec2 p0, Vec2 p1, Vec2 p2, float t) {
        float u = 1f - t;
        float x = u * u * p0.x() + 2f * u * t * p1.x() + t * t * p2.x();
        float y = u * u * p0.y() + 2f * u * t * p1.y() + t * t * p2.y();
        return new Vec2(x, y);
    }

    /**
     * 在参数 {@code t} 处计算三次贝塞尔曲线。
     *
     * @param p0 起点
     * @param p1 第一个控制点
     * @param p2 第二个控制点
     * @param p3 终点
     * @param t  参数（0 到 1）
     * @return 曲线上的点
     */
    private static Vec2 cubicBezier(Vec2 p0, Vec2 p1, Vec2 p2, Vec2 p3, float t) {
        float u = 1f - t;
        float x = u * u * u * p0.x()
                + 3f * u * u * t * p1.x()
                + 3f * u * t * t * p2.x()
                + t * t * t * p3.x();
        float y = u * u * u * p0.y()
                + 3f * u * u * t * p1.y()
                + 3f * u * t * t * p2.y()
                + t * t * t * p3.y();
        return new Vec2(x, y);
    }

    /**
     * 获取组成此路径的不可修改命令列表。
     *
     * @return 路径命令列表
     */
    public List<PathCommand> commands() {
        return commands;
    }

    /**
     * 将此路径转换为顶点列表，将所有曲线细分为线段。
     *
     * @param segments 用于近似每条曲线的线段数（值越大曲线越平滑，但顶点越多）；
     *                 必须至少为 1
     * @return 表示路径的 {@link Vec2} 顶点的不可修改列表
     * @throws IllegalArgumentException 如果 {@code segments} 小于 1
     */
    public List<Vec2> toVertices(int segments) {
        if (segments < 1) {
            throw new IllegalArgumentException("segments 必须 >= 1，当前值：" + segments);
        }

        List<Vec2> vertices = new ArrayList<>();
        Vec2 current = Vec2.ZERO;
        Vec2 subPathStart = Vec2.ZERO;

        for (PathCommand cmd : commands) {
            switch (cmd.type()) {
                case MOVE_TO -> {
                    current = cmd.points()[0];
                    subPathStart = current;
                    vertices.add(current);
                }
                case LINE_TO -> {
                    current = cmd.points()[0];
                    vertices.add(current);
                }
                case QUAD_TO -> {
                    Vec2 control = cmd.points()[0];
                    Vec2 end = cmd.points()[1];
                    for (int i = 1; i <= segments; i++) {
                        float t = i / (float) segments;
                        Vec2 point = quadBezier(current, control, end, t);
                        vertices.add(point);
                    }
                    current = end;
                }
                case CUBIC_TO -> {
                    Vec2 control1 = cmd.points()[0];
                    Vec2 control2 = cmd.points()[1];
                    Vec2 end = cmd.points()[2];
                    for (int i = 1; i <= segments; i++) {
                        float t = i / (float) segments;
                        Vec2 point = cubicBezier(current, control1, control2, end, t);
                        vertices.add(point);
                    }
                    current = end;
                }
                case CLOSE -> {
                    if (!current.equals(subPathStart)) {
                        current = subPathStart;
                        vertices.add(current);
                    }
                }
            }
        }

        return Collections.unmodifiableList(vertices);
    }

    /**
     * 路径命令类型枚举。
     */
    public enum Type {
        /** 移动当前点但不绘制。 */
        MOVE_TO,
        /** 从当前点到目标点绘制直线。 */
        LINE_TO,
        /** 绘制二次贝塞尔曲线。 */
        QUAD_TO,
        /** 绘制三次贝塞尔曲线。 */
        CUBIC_TO,
        /** 通过绘制直线回到起点来关闭当前子路径。 */
        CLOSE
    }

    /**
     * 路径中的单个命令，由类型和零个或多个控制/端点向量组成。
     *
     * @param type   命令类型
     * @param points 与此命令关联的控制和端点向量
     */
    public record PathCommand(Type type, Vec2... points) {

        /**
         * 创建新的路径命令。
         *
         * @param type   命令类型
         * @param points 控制和端点向量
         */
        public PathCommand {
            // 紧凑构造函数 - 对于带可变参数的记录不需要防御性复制
        }
    }

    /**
     * 用于构建 {@link Path} 实例的可变构建器。
     */
    public static final class Builder {

        private final List<PathCommand> commands = new ArrayList<>();

        private Builder() {
        }

        /**
         * 将当前点移动到 ({@code x}, {@code y}) 但不绘制。
         *
         * @param x x 坐标
         * @param y y 坐标
         * @return 此构建器
         */
        public Builder moveTo(float x, float y) {
            commands.add(new PathCommand(Type.MOVE_TO, new Vec2(x, y)));
            return this;
        }

        /**
         * 从当前点到 ({@code x}, {@code y}) 绘制直线。
         *
         * @param x 端点的 x 坐标
         * @param y 端点的 y 坐标
         * @return 此构建器
         */
        public Builder lineTo(float x, float y) {
            commands.add(new PathCommand(Type.LINE_TO, new Vec2(x, y)));
            return this;
        }

        /**
         * 从当前点到 ({@code x}, {@code y}) 添加二次贝塞尔曲线，
         * 使用 ({@code cx}, {@code cy}) 作为控制点。
         *
         * @param cx 控制点的 x 坐标
         * @param cy 控制点的 y 坐标
         * @param x  端点的 x 坐标
         * @param y  端点的 y 坐标
         * @return 此构建器
         */
        public Builder quadTo(float cx, float cy, float x, float y) {
            commands.add(new PathCommand(Type.QUAD_TO, new Vec2(cx, cy), new Vec2(x, y)));
            return this;
        }

        /**
         * 从当前点到 ({@code x}, {@code y}) 添加三次贝塞尔曲线，
         * 使用 ({@code cx1}, {@code cy1}) 和 ({@code cx2}, {@code cy2}) 作为控制点。
         *
         * @param cx1 第一个控制点的 x 坐标
         * @param cy1 第一个控制点的 y 坐标
         * @param cx2 第二个控制点的 x 坐标
         * @param cy2 第二个控制点的 y 坐标
         * @param x   端点的 x 坐标
         * @param y   端点的 y 坐标
         * @return 此构建器
         */
        public Builder cubicTo(float cx1, float cy1, float cx2, float cy2, float x, float y) {
            commands.add(new PathCommand(
                    Type.CUBIC_TO,
                    new Vec2(cx1, cy1),
                    new Vec2(cx2, cy2),
                    new Vec2(x, y)
            ));
            return this;
        }

        /**
         * 通过绘制直线回到最近的 {@link #moveTo(float, float)} 点来关闭当前子路径。
         *
         * @return 此构建器
         */
        public Builder close() {
            commands.add(new PathCommand(Type.CLOSE));
            return this;
        }

        /**
         * 从累积的命令构建并返回不可修改的 {@link Path}。
         *
         * @return 构建的路径
         */
        public Path build() {
            return new Path(new ArrayList<>(commands));
        }
    }
}
