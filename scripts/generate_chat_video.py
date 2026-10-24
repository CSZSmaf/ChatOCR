"""Deterministic chat-like test videos. Contains no personal conversation data."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import subprocess, json, shutil

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'app/src/androidTest/assets/chat-video'
OUT.mkdir(parents=True, exist_ok=True)
FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 28)
SMALL = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 22)
FFMPEG = shutil.which('ffmpeg')
W, H, TOP, BOTTOM = 720, 1280, 180, 1126

def create(platform, group, reverse):
    bg = (237, 237, 237) if platform == 'wechat' else (240, 244, 249)
    world = Image.new('RGB', (W, 4600), bg)
    d = ImageDraw.Draw(world)
    y, truth = 35, []
    for i in range(1, 19):
        right = i % 3 == 0
        x = 200 if right else 90
        if group and not right:
            d.text((x, y), '小林' if i % 2 else '小周', font=SMALL, fill=(100, 100, 105))
            y += 34
        if i in (7, 14):
            patch = Image.new('RGB', (240, 210))
            p = patch.load()
            for py in range(210):
                for px in range(240): p[px, py] = (70 + px // 2, 75 + py // 2, 220 - (px + py) // 4)
            pd = ImageDraw.Draw(patch)
            pd.ellipse((55, 32, 190, 166), fill=(255, 210, 70))
            pd.ellipse((88, 78, 100, 94), fill=(45, 40, 45)); pd.ellipse((143, 78, 155, 94), fill=(45, 40, 45))
            pd.arc((92, 88, 152, 136), 0, 180, fill=(45, 40, 45), width=6)
            world.paste(patch, (x, y)); height = 210
            text = '[image]'
        else:
            text = '好的' if i in (4, 5) else f'消息{i:03d} 今天一起散步'
            width = 128 if text == '好的' else 426
            height = 76
            color = (154, 231, 102) if platform == 'wechat' else (68, 146, 235)
            d.rounded_rectangle((x, y, x + width, y + height), 12, fill=color if right else 'white')
            d.text((x + 18, y + 17), text, font=FONT, fill='white' if right and platform == 'qq' else (25, 28, 27))
        # Avatars should never be imported as separate picture messages.
        ax = 653 if right else 18
        d.rounded_rectangle((ax, y, ax + 46, y + 46), 5, fill=(95, 137, 127))
        truth.append(dict(index=i, text=text, side='RIGHT' if right else 'LEFT', top=y, bottom=y + height))
        y += height + 48
    length = y + 20
    duration = 16
    count = duration * 10
    path = OUT / f'{platform}.mp4'
    proc = subprocess.Popen([FFMPEG, '-y', '-hide_banner', '-loglevel', 'error', '-f', 'rawvideo', '-pixel_format', 'rgb24',
        '-video_size', f'{W}x{H}', '-framerate', '10', '-i', '-', '-an', '-c:v', 'libx264', '-preset', 'fast', '-crf', '18',
        '-pix_fmt', 'yuv420p', str(path)], stdin=subprocess.PIPE)
    for frame in range(count):
        # Includes 2-second stationary intervals at both ends; skip them before OCR.
        f = max(0, min(1, (frame / 10 - 2) / 12))
        if reverse: f = 1 - f
        offset = round(f * max(0, length - (BOTTOM - TOP)))
        image = Image.new('RGB', (W, H), bg)
        image.paste(world.crop((0, offset, W, offset + BOTTOM - TOP)), (0, TOP))
        ui = ImageDraw.Draw(image)
        ui.rectangle((0, 0, W, TOP - 1), fill=(248, 248, 248)); ui.text((280, 90), '测试聊天', font=FONT, fill=(35, 50, 45))
        ui.rectangle((0, BOTTOM, W, H), fill=(248, 248, 248)); ui.text((180, 1170), '输入消息', font=FONT, fill=(140, 145, 140))
        proc.stdin.write(image.tobytes())
        if frame == 35: image.save(ROOT / f'verification/v050-{platform}-fixture.png')
    proc.stdin.close(); assert proc.wait() == 0
    (OUT / f'{platform}.json').write_text(json.dumps(truth, ensure_ascii=False, indent=2), encoding='utf-8')
    print(path, path.stat().st_size)

create('wechat', False, True)
create('qq', True, False)

subprocess.run([FFMPEG, "-y", "-hide_banner", "-loglevel", "error", "-loop", "1", "-i", str(ROOT / "verification/v050-qq-fixture.png"), "-t", "300", "-r", "1", "-c:v", "libx264", "-preset", "fast", "-crf", "18", "-pix_fmt", "yuv420p", str(OUT / "stationary-5min.mp4")], check=True)
