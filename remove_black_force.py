with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace(", color = androidx.compose.ui.graphics.Color.Black", "")
content = content.replace("borderColor = androidx.compose.ui.graphics.Color.Black,\n", "")
content = content.replace("borderColor = androidx.compose.ui.graphics.Color.Black,\r\n", "")
content = content.replace(", tint = androidx.compose.ui.graphics.Color.Black", "")

with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'w', encoding='utf-8') as f:
    f.write(content)
print("Removed Color.Black")
