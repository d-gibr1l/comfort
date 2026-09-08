with open("app/src/main/java/com/comfort/app/theme/Color.kt", "r") as f:
    colors_kt = f.read()

import re

# Parse the generated Color.kt to extract color definitions
light_colors = {}
dark_colors = {}
for line in colors_kt.splitlines():
    match = re.search(r'val (light|dark)_([a-zA-Z0-9_]+)\s*=\s*(Color\(0x[0-9A-F]+\))', line)
    if match:
        theme_type = match.group(1)
        name = match.group(2)
        val = match.group(3)
        if theme_type == 'light':
            light_colors[name] = val
        else:
            dark_colors[name] = val

def build_color_scheme(colors, is_dark):
    # Material 3 color scheme constructor arguments
    scheme_type = "darkColorScheme" if is_dark else "lightColorScheme"
    
    # Map the parsed names to MaterialTheme property names (e.g., surfaceContainerHigh)
    props = []
    
    # Standard mapping, ignoring _mediumContrast and _highContrast variants
    for k, v in colors.items():
        if "_mediumContrast" in k or "_highContrast" in k:
            continue
        
        # k is like "primary", "onSurfaceVariant", "surfaceContainerHighest"
        props.append(f"{k} = {v}")
    
    return f"{scheme_type}(\n            " + ",\n            ".join(props) + "\n        )"

light_scheme = build_color_scheme(light_colors, False)
dark_scheme = build_color_scheme(dark_colors, True)

custom_enum = f"""    CUSTOM(
        lightLabel = "Custom",
        darkLabel = "Custom",
        light = {light_scheme},
        dark = {dark_scheme},
    ),
"""

with open("app/src/main/java/com/comfort/app/theme/AppTheme.kt", "r") as f:
    apptheme_kt = f.read()

# Insert CUSTOM before EXPRESSIVE
apptheme_kt = apptheme_kt.replace("    EXPRESSIVE(", custom_enum + "    EXPRESSIVE(")

with open("app/src/main/java/com/comfort/app/theme/AppTheme.kt", "w") as f:
    f.write(apptheme_kt)

with open("app/src/main/java/com/comfort/app/theme/Theme.kt", "r") as f:
    theme_kt = f.read()

theme_kt = theme_kt.replace("lightTheme: AppTheme = AppTheme.MONOCHROME", "lightTheme: AppTheme = AppTheme.CUSTOM")
theme_kt = theme_kt.replace("darkTheme: AppTheme = AppTheme.MONOCHROME", "darkTheme: AppTheme = AppTheme.CUSTOM")
# Also change the default preference in GalleryDlPreferences if possible, but the defaults in GalleryDLTheme should be enough if prefs are empty.

with open("app/src/main/java/com/comfort/app/theme/Theme.kt", "w") as f:
    f.write(theme_kt)

print("Injected CUSTOM theme into AppTheme.kt and Theme.kt")
