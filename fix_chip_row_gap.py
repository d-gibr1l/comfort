with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Revert the unintended changes from before
content = content.replace(
    '.padding(bottom = 40.dp),',
    '.padding(bottom = 24.dp),'
)
content = content.replace(
    'Spacer(Modifier.height(16.dp))\r\n        Button(\r\n            onClick = onDownload,',
    'Button(\r\n            onClick = onDownload,'
)
content = content.replace(
    'Spacer(Modifier.height(16.dp))\n        Button(\n            onClick = onDownload,',
    'Button(\n            onClick = onDownload,'
)

# Increase the gap between the two chip rows (was 0.dp)
content = content.replace(
    'Column(\r\n            modifier = Modifier.fillMaxWidth(),\r\n            verticalArrangement = Arrangement.spacedBy(0.dp)\r\n        )',
    'Column(\r\n            modifier = Modifier.fillMaxWidth(),\r\n            verticalArrangement = Arrangement.spacedBy(8.dp)\r\n        )'
)
content = content.replace(
    'Column(\n            modifier = Modifier.fillMaxWidth(),\n            verticalArrangement = Arrangement.spacedBy(0.dp)\n        )',
    'Column(\n            modifier = Modifier.fillMaxWidth(),\n            verticalArrangement = Arrangement.spacedBy(8.dp)\n        )'
)

with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'w', encoding='utf-8') as f:
    f.write(content)
print("Updated spacing between chip rows")
