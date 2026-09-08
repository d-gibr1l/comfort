with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Restore ArrowDown to 18.dp
content = content.replace(
    'Icon(FeatherIcons.ArrowDown, contentDescription = null, modifier = Modifier.size(15.dp))',
    'Icon(FeatherIcons.ArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))'
)

with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'w', encoding='utf-8') as f:
    f.write(content)
print("Restored ArrowDown")
