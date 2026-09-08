import re

with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'r') as f:
    content = f.read()

old_chip = """    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, fontWeight = FontWeight.ExtraBold, color = androidx.compose.ui.graphics.Color.Black) },
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = androidx.compose.ui.graphics.Color.Black,
            borderWidth = 1.5.dp,
            selectedBorderWidth = 1.5.dp
        ),
        // 8dp rather than the pill the theme's shape scale would otherwise give a chip - the
        // sheet's own spec calls for squarer chips than the fully-rounded buttons around them.
        shape = shape,
        leadingIcon = leading?.let {
            { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp), tint = androidx.compose.ui.graphics.Color.Black) }
        }
    )"""

new_chip = """    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, fontWeight = FontWeight.ExtraBold) },
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderWidth = 1.5.dp,
            selectedBorderWidth = 1.5.dp
        ),
        shape = shape,
        leadingIcon = leading?.let {
            { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp)) }
        }
    )"""

content = content.replace(old_chip, new_chip)

with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'w') as f:
    f.write(content)
print("Removed hardcoded black colors from PreviewChip")
