with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace(
    'modifier = Modifier.height(38.dp),',
    'modifier = Modifier.height(35.dp),'
)

with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'w', encoding='utf-8') as f:
    f.write(content)
print("Updated height to 35.dp")
