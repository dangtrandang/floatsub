import os
from PIL import Image, ImageDraw

base_res = r"c:\Users\Dang\Desktop\antigravity\floatsub\app\src\main\res"

def create_background(size=432):
    """Full edge-to-edge gradient background for adaptive icon"""
    bg = Image.new("RGBA", (size, size), (0, 0, 0, 255))
    draw = ImageDraw.Draw(bg)
    # Diagonal-ish gradient from vibrant violet (#7C3AED) to deep dark navy (#1E1B4B)
    for y in range(size):
        for x in range(size):
            # diagonal factor
            ratio = (x + y) / (2.0 * size)
            r = int(124 * (1 - ratio) + 26 * ratio)
            g = int(58 * (1 - ratio) + 20 * ratio)
            b = int(237 * (1 - ratio) + 80 * ratio)
            bg.putpixel((x, y), (r, g, b, 255))
    return bg

def create_foreground(size=432):
    """Transparent foreground layer centered in 66dp (safe zone)"""
    fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(fg)
    
    cx, cy = size // 2, size // 2
    # In 432x432 (108dp), safe zone is 264x264 (66dp). 
    # Center emblem should fit nicely within ~240x200
    box_w = int(size * 0.52)
    box_h = int(size * 0.38)
    x0 = cx - box_w // 2
    y0 = cy - box_h // 2 - int(size * 0.02)
    x1 = x0 + box_w
    y1 = y0 + box_h
    
    # 1. Dark glowing CC panel
    # Subtle outer glow
    draw.rounded_rectangle([(x0 - 4, y0 - 4), (x1 + 4, y1 + 4)], radius=int(size * 0.07), fill=(56, 189, 248, 60))
    # Main panel (#0F172A) with cyan border
    draw.rounded_rectangle([(x0, y0), (x1, y1)], radius=int(size * 0.06), fill=(15, 23, 42, 245), outline=(56, 189, 248, 255), width=int(size * 0.016))
    
    # 2. Subtitle text area (bottom half)
    sub_y0 = y0 + int(box_h * 0.44)
    sub_y1 = y1 - int(size * 0.02)
    sub_x0 = x0 + int(size * 0.03)
    sub_x1 = x1 - int(size * 0.03)
    draw.rounded_rectangle([(sub_x0, sub_y0), (sub_x1, sub_y1)], radius=int(size * 0.03), fill=(30, 41, 59, 255))
    
    # Subtitle Line 1: Neon Cyan bar
    line1_y = sub_y0 + int((sub_y1 - sub_y0) * 0.32)
    draw.rounded_rectangle([(sub_x0 + int(size * 0.025), line1_y - int(size * 0.01)), 
                            (sub_x1 - int(size * 0.06), line1_y + int(size * 0.01))], 
                           radius=int(size * 0.01), fill=(56, 189, 248, 255))
                           
    # Subtitle Line 2: Warm Amber/Yellow bar
    line2_y = sub_y0 + int((sub_y1 - sub_y0) * 0.68)
    draw.rounded_rectangle([(sub_x0 + int(size * 0.025), line2_y - int(size * 0.01)), 
                            (sub_x0 + int(box_w * 0.5), line2_y + int(size * 0.01))], 
                           radius=int(size * 0.01), fill=(251, 191, 36, 255))
                           
    # 3. Live Dot (Neon Green #22C55E)
    dot_r = int(size * 0.022)
    dot_x = x1 - int(size * 0.05)
    dot_y = y0 + int(size * 0.055)
    draw.ellipse([(dot_x - dot_r, dot_y - dot_r), (dot_x + dot_r, dot_y + dot_r)], fill=(34, 197, 94, 255))
    
    # 4. "CC" badge in top-left
    cc_x = x0 + int(size * 0.04)
    cc_y = y0 + int(size * 0.038)
    cc_w = int(size * 0.13)
    cc_h = int(size * 0.075)
    draw.rounded_rectangle([(cc_x, cc_y), (cc_x + cc_w, cc_y + cc_h)], radius=int(size * 0.015), outline=(168, 85, 247, 255), width=int(size * 0.008))
    
    c_w = int(size * 0.032)
    c_h = int(size * 0.042)
    c_thick = int(size * 0.008)
    for off in [cc_x + int(size * 0.022), cc_x + int(size * 0.072)]:
        # Top
        draw.rectangle([(off, cc_y + int(size * 0.016)), (off + c_w, cc_y + int(size * 0.016) + c_thick)], fill=(255, 255, 255, 255))
        # Left
        draw.rectangle([(off, cc_y + int(size * 0.016)), (off + c_thick, cc_y + int(size * 0.016) + c_h)], fill=(255, 255, 255, 255))
        # Bottom
        draw.rectangle([(off, cc_y + int(size * 0.016) + c_h - c_thick), (off + c_w, cc_y + int(size * 0.016) + c_h)], fill=(255, 255, 255, 255))
        
    # 5. Soundwave below screen
    wave_y = y1 + int(size * 0.07)
    wave_factors = [0.025, 0.045, 0.07, 0.045, 0.025]
    wave_w = int(size * 0.015)
    wave_gap = int(size * 0.028)
    start_x = cx - int(len(wave_factors) * wave_gap / 2)
    for i, hf in enumerate(wave_factors):
        wx = start_x + i * wave_gap
        h = int(size * hf)
        draw.rounded_rectangle([(wx - wave_w // 2, wave_y - h), (wx + wave_w // 2, wave_y + h)], radius=wave_w // 2, fill=(168, 85, 247, 240))
        
    return fg

def create_legacy_icon(bg, fg, size=192):
    """Combines bg and fg into a standalone squircle icon for legacy/fallback"""
    combined = Image.new("RGBA", bg.size, (0, 0, 0, 0))
    combined.paste(bg, (0, 0))
    combined.alpha_composite(fg)
    
    # Resize to target
    res = combined.resize((size, size), Image.Resampling.LANCZOS)
    return res

print("Generating 432x432 adaptive layers...")
bg_432 = create_background(432)
fg_432 = create_foreground(432)

# Save master preview of composite
preview = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
preview.paste(bg_432, (0, 0))
preview.alpha_composite(fg_432)
preview.save(os.path.join(base_res, "adaptive_preview.png"))

# Standard density sizes for adaptive icon layers (108dp)
# mdpi=108, hdpi=162, xhdpi=216, xxhdpi=324, xxxhdpi=432
adaptive_sizes = {
    "mipmap-mdpi": (108, 48),
    "mipmap-hdpi": (162, 72),
    "mipmap-xhdpi": (216, 96),
    "mipmap-xxhdpi": (324, 144),
    "mipmap-xxxhdpi": (432, 192),
}

for folder, (layer_sz, legacy_sz) in adaptive_sizes.items():
    folder_path = os.path.join(base_res, folder)
    os.makedirs(folder_path, exist_ok=True)
    
    # Save adaptive layers
    bg_resized = bg_432.resize((layer_sz, layer_sz), Image.Resampling.LANCZOS)
    fg_resized = fg_432.resize((layer_sz, layer_sz), Image.Resampling.LANCZOS)
    bg_resized.save(os.path.join(folder_path, "ic_launcher_background.png"))
    fg_resized.save(os.path.join(folder_path, "ic_launcher_foreground.png"))
    
    # Save legacy standalone icons (used when adaptive icon is not used)
    legacy = create_legacy_icon(bg_432, fg_432, legacy_sz)
    legacy.save(os.path.join(folder_path, "ic_launcher.png"))
    legacy.save(os.path.join(folder_path, "ic_launcher_round.png"))
    
    # Also save in corresponding drawable folder
    draw_folder = os.path.join(base_res, folder.replace("mipmap-", "drawable-"))
    os.makedirs(draw_folder, exist_ok=True)
    legacy.save(os.path.join(draw_folder, "ic_launcher.png"))
    legacy.save(os.path.join(draw_folder, "ic_launcher_round.png"))

print("All raster assets generated successfully!")
