with open('app/src/main/java/com/example/tellofpv/MainActivity.kt', 'r', encoding='utf-8') as f:
    code = f.read()

code = code.replace('blackbox.start { telemetry }', 'blackbox.start { session.telemetry.value }')
code = code.replace('telemetry.heightCm', 'session.telemetry.value.heightCm')
code = code.replace('telemetry.pitch', 'session.telemetry.value.pitch')
code = code.replace('telemetry.roll', 'session.telemetry.value.roll')

with open('app/src/main/java/com/example/tellofpv/MainActivity.kt', 'w', encoding='utf-8') as f:
    f.write(code)

