with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace("color = MaterialTheme.colorScheme.surfaceContainerHigh", "color = MaterialTheme.colorScheme.secondaryContainer")

with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'w', encoding='utf-8') as f:
    f.write(content)
print("Updated all surfaceContainerHigh to secondaryContainer")
