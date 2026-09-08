with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Fix 1: Remove the 80% opacity modifier from onSecondaryContainer
content = content.replace(
    'color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)',
    'color = MaterialTheme.colorScheme.onSecondaryContainer'
)

# Fix 2: Remove the explicit error tint on the Trash icon (it will fall back to LocalContentColor, which is onSecondaryContainer)
old_trash = """                                Icon(
                                    FeatherIcons.Trash2,
                                    contentDescription = "Delete template",
                                    tint = MaterialTheme.colorScheme.error,
                                )"""

new_trash = """                                Icon(
                                    FeatherIcons.Trash2,
                                    contentDescription = "Delete template",
                                )"""

content = content.replace(old_trash, new_trash)

with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'w', encoding='utf-8') as f:
    f.write(content)
print("Applied accessibility fixes")
