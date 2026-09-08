import re

with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'r') as f:
    content = f.read()

old_block = """        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            PreviewChip(
                label = "Save thumbnail",
                selected = saveThumbnail,
                icon = FeatherIcons.Image,
                shape = firstShape,
                onClick = onToggleSaveThumbnail,
            )
            PreviewChip(
                label = if (commandCount > 0) "Commands ($commandCount)" else "Add extra Commands",
                selected = commandCount > 0,
                icon = FeatherIcons.Terminal,
                shape = lastShape,
                onClick = onOpenCommands,
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            PreviewChip(
                label = "Trim Video",
                selected = trimmed,
                icon = FeatherIcons.Scissors,
                shape = firstShape,
                onClick = onOpenTrim,
            )
            PreviewChip(
                label = outputFormat.name.lowercase().replaceFirstChar { it.uppercase() },
                icon = FeatherIcons.Film,
                shape = middleShape,
                onClick = onToggleFormat,
            )
            PreviewChip(
                label = "Filename Templates.",
                selected = filenameTemplate != null,
                icon = FeatherIcons.Tag,
                shape = lastShape,
                onClick = onOpenTemplates,
            )
        }"""

new_block = """        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PreviewChip(
                    label = "Save thumbnail",
                    selected = saveThumbnail,
                    icon = FeatherIcons.Image,
                    shape = firstShape,
                    onClick = onToggleSaveThumbnail,
                )
                PreviewChip(
                    label = if (commandCount > 0) "Commands ($commandCount)" else "Add extra Commands",
                    selected = commandCount > 0,
                    icon = FeatherIcons.Terminal,
                    shape = lastShape,
                    onClick = onOpenCommands,
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PreviewChip(
                    label = "Trim Video",
                    selected = trimmed,
                    icon = FeatherIcons.Scissors,
                    shape = firstShape,
                    onClick = onOpenTrim,
                )
                PreviewChip(
                    label = outputFormat.name.lowercase().replaceFirstChar { it.uppercase() },
                    icon = FeatherIcons.Film,
                    shape = middleShape,
                    onClick = onToggleFormat,
                )
                PreviewChip(
                    label = "Filename Templates.",
                    selected = filenameTemplate != null,
                    icon = FeatherIcons.Tag,
                    shape = lastShape,
                    onClick = onOpenTemplates,
                )
            }
        }"""

content = content.replace(old_block, new_block)

with open('app/src/main/java/com/comfort/app/ui/main/DownloadPreviewSheet.kt', 'w') as f:
    f.write(content)
print("Wrapped action rows in a Column with 4dp spacing")
