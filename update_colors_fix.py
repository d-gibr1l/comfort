import re

with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'r') as f:
    content = f.read()

# Replace the specific card container color
content = re.sub(
    r'colors = CardDefaults\.cardColors\(containerColor = \nMaterialTheme\.colorScheme\.surfaceContainerHigh\)',
    r'colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)',
    content
)

# And also replace the Text colors inside that card
content = content.replace(
    'title ?: "Unknown video",\n                    style = MaterialTheme.typography.titleMedium,\n                    color = MaterialTheme.colorScheme.onSurface,',
    'title ?: "Unknown video",\n                    style = MaterialTheme.typography.titleMedium,\n                    color = MaterialTheme.colorScheme.onSecondaryContainer,'
)

content = content.replace(
    'uploader ?: "Unknown uploader",\n                    style = MaterialTheme.typography.bodyMedium,\n                    color = MaterialTheme.colorScheme.onSurfaceVariant,',
    'uploader ?: "Unknown uploader",\n                    style = MaterialTheme.typography.bodyMedium,\n                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),'
)

with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'w') as f:
    f.write(content)
print("Updated card colors")
