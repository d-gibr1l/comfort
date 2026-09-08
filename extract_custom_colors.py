import re

with open('app/src/main/java/com/comfort/app/theme/AppTheme.kt', 'r') as f:
    text = f.read()

custom = text.split("CUSTOM(")[1].split("),")[0]
for line in custom.splitlines():
    if "secondaryContainer =" in line or "onSecondaryContainer =" in line or "error =" in line:
        print(line.strip())
