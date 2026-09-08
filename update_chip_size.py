with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'r', encoding='utf-8') as f:
    content = f.read()

if "import androidx.compose.ui.unit.sp" not in content:
    content = content.replace("import androidx.compose.ui.unit.dp\n", "import androidx.compose.ui.unit.dp\nimport androidx.compose.ui.unit.sp\n")

old_chip = """    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, fontWeight = FontWeight.ExtraBold) },
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
                        borderWidth = 1.5.dp,
            selectedBorderWidth = 1.5.dp
        ),
        // 8dp rather than the pill the theme's shape scale would otherwise give a chip - the
        // sheet's own spec calls for squarer chips than the fully-rounded buttons around them.
        shape = shape,
        leadingIcon = leading?.let {
            { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp)) }
        }
    )"""

new_chip = """    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.height(40.dp),
        label = { Text(label, maxLines = 1, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) },
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderWidth = 1.5.dp,
            selectedBorderWidth = 1.5.dp
        ),
        // 8dp rather than the pill the theme's shape scale would otherwise give a chip - the
        // sheet's own spec calls for squarer chips than the fully-rounded buttons around them.
        shape = shape,
        leadingIcon = leading?.let {
            { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp)) }
        }
    )"""

# In case the formatting of old_chip has some weird whitespace:
import re
content = re.sub(r'FilterChip\(\s*selected = selected,\s*onClick = onClick,\s*label = \{ Text\(label, maxLines = 1, fontWeight = FontWeight\.ExtraBold\) \},\s*border = FilterChipDefaults\.filterChipBorder\(\s*enabled = true,\s*selected = selected,\s*borderWidth = 1\.5\.dp,\s*selectedBorderWidth = 1\.5\.dp\s*\),\s*//.*?\s*//.*?\s*shape = shape,\s*leadingIcon = leading\?\.let \{\s*\{ Icon\(it, contentDescription = null, modifier = Modifier\.size\(16\.dp\)\) \}\s*\}\s*\)', new_chip, content, flags=re.DOTALL)

with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'w', encoding='utf-8') as f:
    f.write(content)
print("Updated PreviewChip size")
