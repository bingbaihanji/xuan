package com.bingbaihanji.xuan.example.audio

/**
 * PCM 字节 → `[-1, 1]` 浮点样本的**唯一一份**实现。
 *
 * 为什么值得单独一个文件：WAV 解码（[WavFileSource]，8/16/24/32 位、大小端、有无符号）
 * 与麦克风采集（[MicrophoneSource]，恒为 16 位小端）是同一条尺规。两边各写一遍的话，
 * 改了其中一处（比如把除数 32768 换成 32767）另一处不会动，而"两路音频的音量刻度不一样"
 * 在画面上只表现为"频谱高度差一点"——不报错，也没有别的东西能指出它。
 *
 * @param offset [buffer] 里这个样本第一个字节的位置
 * @param bits 位宽，只支持 8 / 16 / 24 / 32；容器宽度必须恰好是 `bits / 8`
 * @param bigEndian 字节序（WAV 绝大多数是小端，但格式头里能声明大端）
 * @param unsigned `PCM_UNSIGNED` 为 true。它的零点在中位（8 位是 128），不在最小值上
 */
internal fun pcmToFloat(
    buffer: ByteArray,
    offset: Int,
    bits: Int,
    bigEndian: Boolean,
    unsigned: Boolean,
): Float {
    val bytes = bits ushr 3
    // 先把字节拼成无符号整数（有符号的情况下一步再符号扩展）。
    var raw = 0
    if (bigEndian) {
        for (i in 0 until bytes) raw = (raw shl 8) or (buffer[offset + i].toInt() and 0xFF)
    } else {
        for (i in bytes - 1 downTo 0) raw = (raw shl 8) or (buffer[offset + i].toInt() and 0xFF)
    }
    // 满量程的一半是 32768 而不是 32767：PCM 的负半轴比正半轴多一格（-32768 没有正对应）。
    // 用 Long 是因为 32 位时 `1 shl 31` 会溢出成负数。
    val half = 1L shl (bits - 1)
    // 有符号：按位宽做算术右移，把 8/16/24 位时挤在 raw 高位上的垃圾抹掉（相当于符号扩展）。
    val value = if (unsigned) raw.toLong() - half else ((raw shl (32 - bits)) shr (32 - bits)).toLong()
    return value.toFloat() / half.toFloat()
}
