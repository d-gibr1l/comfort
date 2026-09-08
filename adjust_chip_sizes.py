with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Update chip height
content = content.replace(
    'modifier = Modifier.height(42.dp),',
    'modifier = Modifier.height(38.dp),'
)

# Update font size
content = content.replace(
    'fontSize = 16.sp',
    'fontSize = 14.sp'
)

# Update icon size
content = content.replace(
    'modifier = Modifier.size(18.dp)',
    'modifier = Modifier.size(15.dp)'
)

with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'w', encoding='utf-8') as f:
    f.write(content)
print("Updated dimensions")
