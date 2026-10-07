with open('app/src/main/java/com/example/tellofpv/MainActivity.kt', 'r', encoding='utf-8') as f:
    code = f.read()
import re
code = re.sub(
    r'private fun TacticalTopHud\(session: TelloFlightSession, rssi: Int\?, flightStartTimeMs: Long\?, modifier: Modifier =\s*Modifier\) \{',
    'private fun TacticalTopHud(session: TelloFlightSession, rssi: Int?, flightStartTimeMs: Long?, modifier: Modifier = Modifier) {\n    val telemetry by session.telemetry.collectAsState()',
    code
)
with open('app/src/main/java/com/example/tellofpv/MainActivity.kt', 'w', encoding='utf-8') as f:
    f.write(code)

