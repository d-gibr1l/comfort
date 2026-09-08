def luminance(r, g, b):
    a = [c / 255.0 for c in (r, g, b)]
    for i, c in enumerate(a):
        if c <= 0.03928:
            a[i] = c / 12.92
        else:
            a[i] = ((c + 0.055) / 1.055) ** 2.4
    return 0.2126 * a[0] + 0.7152 * a[1] + 0.0722 * a[2]

def contrast(rgb1, rgb2):
    lum1 = luminance(*rgb1)
    lum2 = luminance(*rgb2)
    brightest = max(lum1, lum2)
    darkest = min(lum1, lum2)
    return (brightest + 0.05) / (darkest + 0.05)

def hex_to_rgb(hex_str):
    hex_str = hex_str.lstrip('#').lstrip('0x').lstrip('FF')
    if len(hex_str) == 6:
        return tuple(int(hex_str[i:i+2], 16) for i in (0, 2, 4))
    return (0, 0, 0)

def alpha_blend(fg_rgb, bg_rgb, alpha):
    return tuple(round(fg_rgb[i] * alpha + bg_rgb[i] * (1 - alpha)) for i in range(3))

print("=== LIGHT MODE ===")
bg_light = hex_to_rgb("CCEEA1")
text_light = hex_to_rgb("516D2F")
text_light_80 = alpha_blend(text_light, bg_light, 0.8)
error_light = hex_to_rgb("BA1A1A")

print(f"Text (100%): {contrast(bg_light, text_light):.2f}:1")
print(f"Text (80%): {contrast(bg_light, text_light_80):.2f}:1")
print(f"Error Icon: {contrast(bg_light, error_light):.2f}:1")

print("\n=== DARK MODE ===")
bg_dark = hex_to_rgb("365016")
text_dark = hex_to_rgb("A3C37B")
text_dark_80 = alpha_blend(text_dark, bg_dark, 0.8)
error_dark = hex_to_rgb("FFB4AB")

print(f"Text (100%): {contrast(bg_dark, text_dark):.2f}:1")
print(f"Text (80%): {contrast(bg_dark, text_dark_80):.2f}:1")
print(f"Error Icon: {contrast(bg_dark, error_dark):.2f}:1")
