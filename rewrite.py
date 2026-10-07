import re

with open('app/src/main/java/com/example/tellofpv/MainActivity.kt', 'r', encoding='utf-8') as f:
    code = f.read()

# 1. Immersive Sticky Fullscreen & Orientation
on_create_replacement = """    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val windowInsetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
"""
code = re.sub(r'    override fun onCreate\(savedInstanceState: Bundle\?\) \{\s*super\.onCreate\(savedInstanceState\)', on_create_replacement, code)

# 2. Add flightStartTimeMs
code = re.sub(r'var isFlying by remember \{ mutableStateOf\(false\) \}', 'var isFlying by remember { mutableStateOf(false) }\n    var flightStartTimeMs by remember { mutableStateOf<Long?>(null) }', code)

# Update isFlying assignments
code = code.replace('isFlying = false', 'isFlying = false\n                            flightStartTimeMs = null')
code = code.replace('isFlying = true', 'isFlying = true\n                            flightStartTimeMs = System.currentTimeMillis()')


# 3. Update TacticalTopHud signature and body to include flight timer
top_hud_original = """private fun TacticalTopHud(telemetry: TelloTelemetry, rssi: Int?, modifier: Modifier = Modifier) {"""
top_hud_new = """private fun TacticalTopHud(telemetry: TelloTelemetry, rssi: Int?, flightStartTimeMs: Long?, modifier: Modifier = Modifier) {
    var flightTimeStr by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("00:00") }
    androidx.compose.runtime.LaunchedEffect(flightStartTimeMs) {
        while (true) {
            if (flightStartTimeMs != null) {
                val elapsed = (System.currentTimeMillis() - flightStartTimeMs) / 1000
                flightTimeStr = String.format("%02d:%02d", elapsed / 60, elapsed % 60)
            } else {
                flightTimeStr = "00:00"
            }
            kotlinx.coroutines.delay(1000)
        }
    }"""
code = code.replace(top_hud_original, top_hud_new)

# Insert Timer row inside TacticalTopHud
hud_row_original = """    Row(
        modifier = modifier"""
hud_row_new = """    Row(
        modifier = modifier"""
code = code.replace(hud_row_original, hud_row_new)

timer_ui = """        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Timer, "Idő", tint = CyberCyan, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(flightTimeStr, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
"""
code = re.sub(r'(Row\(verticalAlignment = Alignment\.CenterVertically\) \{\s*Icon\(Icons\.Default\.NetworkWifi)', timer_ui + r'\1', code)

# Call TacticalTopHud correctly
code = code.replace('TacticalTopHud(telemetry, rssi)', 'TacticalTopHud(telemetry, rssi, flightStartTimeMs)')

# 4. Remove right vertical column, replace with top-right Row
# Search for Column(Modifier.align(Alignment.CenterEnd).padding(end = 14.dp)
right_column_regex = r'// 4\. Jobb oldali lebeg.*?Column\(\s*Modifier\.align\(Alignment\.CenterEnd\).*?\)\s*\{'
island_replacement = """// 4. Jobb felső Lebegő Sziget (Kameravezérlők)
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 24.dp, top = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GlassDark)
                    .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {"""
code = re.sub(right_column_regex, island_replacement, code, flags=re.DOTALL)

# Also need to add Mission, Logging, Speed somewhere else. Let's put them in the top-left corner as a Row.
# So I will split the right column's contents. Wait, the regex replacement just replaced the `Column(` with `Row(`. The buttons inside will now be in a Row.
# Let's see what happens to the buttons.
# The buttons are: Fotó, Videó, AI Track, Demo Mission, Log Be, Speed.
# I'll let them all be in the TopEnd row for now. It will be a nice wide horizontal bar at the top right.

# 5. Increase Joystick Padding
code = code.replace('padding(start = 24.dp, bottom = 20.dp)', 'padding(start = 48.dp, bottom = 32.dp)')
code = code.replace('padding(end = 24.dp, bottom = 20.dp)', 'padding(end = 48.dp, bottom = 32.dp)')

# 6. Add timer icon import
code = code.replace('import androidx.compose.material.icons.filled.Stop', 'import androidx.compose.material.icons.filled.Stop\nimport androidx.compose.material.icons.filled.Timer')

with open('app/src/main/java/com/example/tellofpv/MainActivity.kt', 'w', encoding='utf-8') as f:
    f.write(code)

