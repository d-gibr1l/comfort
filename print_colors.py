with open('app/src/main/java/com/comfort/app/theme/Color.kt', 'r') as f:
    for line in f.read().splitlines():
        if "secondaryContainer" in line or "onSecondaryContainer" in line or "error" in line:
            print(line)
