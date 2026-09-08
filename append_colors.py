import xml.etree.ElementTree as ET
import os

def parse_colors(xml_path):
    colors = {}
    if os.path.exists(xml_path):
        tree = ET.parse(xml_path)
        root = tree.getroot()
        for child in root:
            if child.tag == 'color':
                name = child.attrib['name']
                value = child.text
                if name.startswith('md_theme_'):
                    colors[name] = value
    return colors

light_colors = parse_colors("C:/Users/domin/Desktop/gallerydl/scratch_theme/values/colors.xml")
dark_colors = parse_colors("C:/Users/domin/Desktop/gallerydl/scratch_theme/values-night/colors.xml")

def format_color(name, hex_val):
    if hex_val.startswith('#'):
        hex_val = hex_val[1:]
    if len(hex_val) == 6:
        hex_val = "FF" + hex_val
    return f"val {name} = Color(0x{hex_val})"

kotlin_file = "\n\n// --- CUSTOM IMPORTED THEME ---\n"
kotlin_file += "// Light theme colors\n"
for name, val in light_colors.items():
    kotlin_file += format_color("light_" + name.replace("md_theme_", ""), val) + "\n"

kotlin_file += "\n// Dark theme colors\n"
for name, val in dark_colors.items():
    kotlin_file += format_color("dark_" + name.replace("md_theme_", ""), val) + "\n"

with open("C:/Users/domin/Desktop/gallerydl/app/src/main/java/com/comfort/app/theme/Color.kt", "a") as f:
    f.write(kotlin_file)

print("Appended Custom colors to Color.kt")
