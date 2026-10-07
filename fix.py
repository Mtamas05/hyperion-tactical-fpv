with open('app/src/main/java/com/example/tellofpv/TelloMissionEngine.kt', 'r', encoding='utf-8') as f:
    code = f.read()
code = code.replace('$currentBat', '${telemetry.battery}')
with open('app/src/main/java/com/example/tellofpv/TelloMissionEngine.kt', 'w', encoding='utf-8') as f:
    f.write(code)

