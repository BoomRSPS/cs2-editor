package dawn.cs2

const val GENERATE_SCRIPTS_DB = false
const val DEBUG = false

const val SCRIPTS_INDEX = 12

// OSRS revision 237+ (incl. 238/239) grew the script trailer by two shorts: a long-type local
// count and a long-type parameter count (16-byte footer); caches <=236 use the old 12-byte footer.
// The format isn't self-describing, so this is only a default — MainController re-detects the correct
// value per cache on open (see ScriptConfiguration.detectDisableLongs) and reassigns this. Kept a var
// so older caches (longs off) and 237+ caches (longs on) can both be opened in the same session.
var scriptConfiguration = ScriptConfiguration(disableSwitches = false, disableLongs = false)
