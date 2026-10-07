import sys
import re

path = r'app/src/main/java/com/example/tellofpv/MainActivity.kt'
with open(path, 'r', encoding='utf-8') as f:
    code = f.read()

new_code = re.sub(
    r'runCatching \{\s*val values = ContentValues.*?\.onFailure \{[^\}]+\}',
    '''CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "TELLO_${System.currentTimeMillis()}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/TelloFPV")
                    }
                    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("Nem hozható létre média bejegyzés")
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                    }
                }.onSuccess { launch(Dispatchers.Main) { Toast.makeText(context, "Pillanatkép mentve a Galériába!", Toast.LENGTH_SHORT).show() } }
                 .onFailure { launch(Dispatchers.Main) { Toast.makeText(context, "Képmentési hiba történt", Toast.LENGTH_SHORT).show() } }
            }''',
    code, flags=re.DOTALL
)

with open(path, 'w', encoding='utf-8') as f:
    f.write(new_code)

