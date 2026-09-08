with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace("containerColor = \nMaterialTheme.colorScheme.surfaceContainerHigh", "containerColor = MaterialTheme.colorScheme.secondaryContainer")
content = content.replace("containerColor = \r\nMaterialTheme.colorScheme.surfaceContainerHigh", "containerColor = MaterialTheme.colorScheme.secondaryContainer")
content = content.replace("containerColor = MaterialTheme.colorScheme.surfaceContainerHigh", "containerColor = MaterialTheme.colorScheme.secondaryContainer")

content = content.replace("color = MaterialTheme.colorScheme.onSurface,", "color = MaterialTheme.colorScheme.onSecondaryContainer,")
content = content.replace("color = MaterialTheme.colorScheme.onSurfaceVariant,", "color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),")

with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'w', encoding='utf-8') as f:
    f.write(content)
print("Updated successfully")
