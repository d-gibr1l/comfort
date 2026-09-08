with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'r') as f:
    content = f.read()

old_block = """        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {"""

new_block = """        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {"""

content = content.replace(old_block, new_block)

with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'w') as f:
    f.write(content)
print("Reduced vertical spacing between action rows to 0.dp")
