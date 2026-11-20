package cosic.io

/**
 * Where the settings a program asks for go: `theme("...")`, `fontFamily("...")` and `fontSize(14)` change the editor, as they change the
 * settings of the web REPL (`patternSetting` of website/src/settings.mjs:149-160). The app implements this and sets it as
 * [OutputBus.settings].
 *
 * [set] runs on the dispatcher thread of the [OutputBus], once for every hap of the pattern the setting was attached to, so a pattern with sixteen
 * haps per cycle calls it sixteen times a cycle with the same value: an implementation changes nothing when the setting already has [value] (the REPL
 * compares with its store first, too). [key] is `theme`, `fontFamily` or `fontSize`; [value] is a [String] or a [Double] (a list is joined with spaces,
 * as the REPL does with `"a:b"`).
 */
fun interface SettingsSink {
    fun set(key: String, value: Any?)
}
