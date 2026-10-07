import re
with open('app/src/main/java/com/example/tellofpv/TelloMissionEngine.kt', 'r', encoding='utf-8') as f:
    code = f.read()

new_code = re.sub(
    r'val currentBat = session\.telemetry\.value\.battery\s*if \(currentBat in 1\.\.15\) \{',
    '''val telemetry = session.telemetry.value
                    if (!telemetry.connected) {
                        _status.value = "Kapcsolat megszakadt! Küldetés abortálva."
                        isAborted = true
                        break
                    }
                    if (telemetry.battery in 1..15) {''',
    code, flags=re.DOTALL
)

new_code = new_code.replace(
    'if (!isActive) break\n                                session.sendControl(step.roll, step.pitch, step.throttle, step.yaw, fast = false)',
    'if (!isActive || !session.telemetry.value.connected) break\n                                session.sendControl(step.roll, step.pitch, step.throttle, step.yaw, fast = false)'
)

new_code = new_code.replace(
    'if (!isActive) break\n                                session.sendControl(0, 0, 0, step.speed, fast = false)',
    'if (!isActive || !session.telemetry.value.connected) break\n                                session.sendControl(0, 0, 0, step.speed, fast = false)'
)

with open('app/src/main/java/com/example/tellofpv/TelloMissionEngine.kt', 'w', encoding='utf-8') as f:
    f.write(new_code)

