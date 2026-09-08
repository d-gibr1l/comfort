with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace(
    'FilterChip(\n        selected = selected,\n        onClick = onClick,\n        label = { Text(label, maxLines = 1, fontWeight = FontWeight.ExtraBold) },',
    'FilterChip(\n        selected = selected,\n        onClick = onClick,\n        modifier = Modifier.height(42.dp),\n        label = { Text(label, maxLines = 1, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) },'
)

# Handle potential CRLF
content = content.replace(
    'FilterChip(\r\n        selected = selected,\r\n        onClick = onClick,\r\n        label = { Text(label, maxLines = 1, fontWeight = FontWeight.ExtraBold) },',
    'FilterChip(\r\n        selected = selected,\r\n        onClick = onClick,\r\n        modifier = Modifier.height(42.dp),\r\n        label = { Text(label, maxLines = 1, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) },'
)

# And the icon size
content = content.replace(
    'Icon(it, contentDescription = null, modifier = Modifier.size(16.dp))',
    'Icon(it, contentDescription = null, modifier = Modifier.size(18.dp))'
)

if "import androidx.compose.ui.unit.sp" not in content:
    content = content.replace("import androidx.compose.ui.unit.dp\n", "import androidx.compose.ui.unit.dp\nimport androidx.compose.ui.unit.sp\n")
    content = content.replace("import androidx.compose.ui.unit.dp\r\n", "import androidx.compose.ui.unit.dp\r\nimport androidx.compose.ui.unit.sp\r\n")

with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'w', encoding='utf-8') as f:
    f.write(content)
print("Updated successfully")
