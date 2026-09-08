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

# Dark Mode Colors
colors = {
    "primary": "97D945", "onPrimary": "1F3700",
    "primaryContainer": "64A104", "onPrimaryContainer": "192F00",
    "secondary": "B1D188", "onSecondary": "1F3700",
    "secondaryContainer": "365016", "onSecondaryContainer": "A3C37B",
    "surface": "11150B", "onSurface": "E0E4D4",
    "surfaceVariant": "424937", "onSurfaceVariant": "C2CAB2",
    "inverseSurface": "E0E4D4", "inverseOnSurface": "2E3227",
    "surfaceContainerLow": "191D13"
}
c = {k: hex_to_rgb(v) for k, v in colors.items()}

print("=== DARK MODE PREVIEW SHEET CONTRAST ===")
print(f"Main Sheet Text (onSurface / surfaceContainerLow): {contrast(c['onSurface'], c['surfaceContainerLow']):.2f}:1")
print(f"Selected Chip (onSecondaryContainer / secondaryContainer): {contrast(c['onSecondaryContainer'], c['secondaryContainer']):.2f}:1")
print(f"Unselected Chip (onSurfaceVariant / surfaceContainerLow): {contrast(c['onSurfaceVariant'], c['surfaceContainerLow']):.2f}:1")
print(f"Thumbnail Box Icon (onPrimaryContainer / primaryContainer): {contrast(c['onPrimaryContainer'], c['primaryContainer']):.2f}:1")
print(f"Button Text (onPrimary / primary): {contrast(c['onPrimary'], c['primary']):.2f}:1")
print(f"Main Card Text (onSecondaryContainer / secondaryContainer): {contrast(c['onSecondaryContainer'], c['secondaryContainer']):.2f}:1")

# Trim Video Timestamp (inverseSurface 70% over primaryContainer)
trim_bg = alpha_blend(c['inverseSurface'], c['primaryContainer'], 0.7)
print(f"Trim Timestamp Text (inverseOnSurface / blended inverseSurface): {contrast(c['inverseOnSurface'], trim_bg):.2f}:1")
