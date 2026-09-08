with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Increase bottom padding of the sheet to lift it
content = content.replace(
    '.padding(bottom = 24.dp),',
    '.padding(bottom = 40.dp),'
)

# Add space before the download button
content = content.replace(
    """        Button(
            onClick = onDownload,""",
    """        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onDownload,"""
)

# Handle CRLF if needed
content = content.replace(
    """        Button(\r\n            onClick = onDownload,""",
    """        Spacer(Modifier.height(16.dp))\r\n        Button(\r\n            onClick = onDownload,"""
)

with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'w', encoding='utf-8') as f:
    f.write(content)
print("Adjusted spacing")
