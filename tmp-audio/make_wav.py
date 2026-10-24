import math, struct, sys, wave

out = sys.argv[1]
rate = 44100
seconds = 30.0
n = int(rate * seconds)
frames = bytearray()
for i in range(n):
    t = i / rate
    # 每 0.5 秒换一个音高，耳朵与频谱都容易认；整体加一个淡入淡出免得首尾爆音
    step = int(t / 0.5) % 3
    freq = [440.0, 660.0, 880.0][step]
    env = min(1.0, t / 0.05, (seconds - t) / 0.05)
    v = int(0.6 * env * 32767 * math.sin(2 * math.pi * freq * t))
    frames += struct.pack('<h', max(-32768, min(32767, v)))

# 手写 44 字节 RIFF 头（不依赖任何库的行为）
data_size = len(frames)
hdr = b'RIFF' + struct.pack('<I', 36 + data_size) + b'WAVE'
hdr += b'fmt ' + struct.pack('<IHHIIHH', 16, 1, 1, rate, rate * 2, 2, 16)
hdr += b'data' + struct.pack('<I', data_size)
open(out, 'wb').write(hdr + bytes(frames))
print(out, data_size, 'bytes of PCM;', n, 'frames @', rate)
