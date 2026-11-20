package cosic.pattern

import java.util.concurrent.ConcurrentHashMap

/*
 * The controls of Strudel, `packages/core/controls.mjs` at the pinned commit, and the one list of their names (K5 of the
 * parity plan): 330 `registerControl` calls and 81 matrix entries make 411 registrations. [Controls] builds them from
 * [CONTROL_TABLE] by running the same `registerControl` / `registerMultiControl` logic the file runs, so the namespaces
 * come out as Strudel's: the methods of `Pattern.prototype` (a later registration of a name wins) and the global
 * functions (only what the file exports), which differ for `fmh1`, `fmi1`, `ds` and the aliases that are not exported.
 *
 * What a control does with a value is `createParam` of the file ([Controls.valueOf], [Controls.applyControl]): a control
 * map `{name: value}`, or, for the array form, a list value applied position by position to the keys (`lpf("1000:0.3")`
 * gives `{cutoff: 1000, resonance: 0.3}`). The colon lists come from the mini-notation, not from here.
 */

/** What a control is for, by the tags of its documentation in Strudel (`A` superdough, `SD` SuperDirt only, ...). */
internal enum class ControlDomain(val code: String) {
    AUDIO("A"),
    SUPERDIRT("SD"),
    MIDI("M"),
    OSC("O"),
    VISUAL("V"),
    TONAL("T"),
    ZZFX("Z"),
    UNDOCUMENTED("?"),
    ;

    companion object {
        fun of(code: String): ControlDomain = entries.firstOrNull { it.code == code } ?: error("unknown control domain '$code'")
    }
}

/**
 * One `registerControl` call. [keys] are the names of the control map keys in the order a list value fills them, the
 * first being the canonical name of the control; [multi] says the call used the array form (a list value is then spread
 * over [keys], otherwise it is stored whole); [aliases] are the rest arguments of the call; [globalNames] the names the
 * file exports from it as global functions. [line] is the line of the registration in `controls.mjs`.
 */
internal class ControlSpec(
    val keys: List<String>,
    val multi: Boolean,
    val aliases: List<String>,
    val globalNames: Set<String>,
    val domain: ControlDomain,
    val line: Int,
) {
    val name: String get() = keys[0]

    override fun toString(): String = "ControlSpec(${keys.joinToString("|")}${if (aliases.isEmpty()) "" else " =" + aliases.joinToString(",")})"
}

internal object Controls {
    /** Every registration in the order the file makes them: 411. */
    val specs: List<ControlSpec>

    private val methods: Map<String, ControlSpec>
    private val globals: Map<String, ControlSpec>
    private val aliasOf: Map<String, String>

    /** The aliases of controls programs registered (`registerControl`), which are as global as Strudel's: they last as long as the process. */
    private val customAliasOf = ConcurrentHashMap<String, String>()

    init {
        val statements = parseControlTable(CONTROL_TABLE)
        specs = statements.flatMap { it.specs }
        val methodMap = HashMap<String, ControlSpec>()
        val aliasMap = HashMap<String, String>()
        val globalMap = HashMap<String, ControlSpec>()
        for (spec in specs) {
            // createParam sets Pattern.prototype[names[0]]; registerControl then copies it to every alias and names it in controlAlias.
            methodMap[spec.name] = spec
            aliasMap[spec.name] = spec.name
            for (alias in spec.aliases) {
                methodMap[alias] = spec
                aliasMap[alias] = spec.name
            }
            for (export in spec.globalNames) globalMap[export] = spec
        }
        for (name in METHODS_REPLACED_BY_REGISTER) methodMap.remove(name)
        methods = methodMap
        globals = globalMap
        aliasOf = aliasMap
    }

    /** The control `Pattern.prototype[name]` is, or null: not a control (`ds` is the function of the `adsr` family that replaces the alias of `delaysync`). */
    fun method(name: String): ControlSpec? = methods[name]

    /** The control the global function [name] is, or null: only what the file exports is global (`o` is a method, not a function). */
    fun global(name: String): ControlSpec? = globals[name]

    /** The names `Pattern.prototype` has methods for that are controls. */
    fun methodNames(): Set<String> = methods.keys

    /** The names the file exports from control registrations. */
    fun globalNames(): Set<String> = globals.keys

    /** `getControlName`: the canonical name of a control called [nameOrAlias], or [nameOrAlias] itself when it is none. */
    fun canonicalName(nameOrAlias: String): String = customAliasOf[nameOrAlias] ?: aliasOf[nameOrAlias] ?: nameOrAlias

    /** `isControlName`: whether [name] is a control or an alias of one (`ds` is, though its method is not a control). */
    fun isControlName(name: String): Boolean = customAliasOf.containsKey(name) || aliasOf.containsKey(name)

    /**
     * The control map [xs] stands for, `withVal` of `createParam`: `{name: xs}`; a list for a control of the array form
     * fills its keys one by one (more entries than keys are dropped, fewer set only the first keys); a map with a `value`
     * key sets the control to that value and keeps its other entries first (`n({value: 3, color: "red"})`).
     */
    fun valueOf(spec: ControlSpec, xs: Any?): ControlMap {
        var value = xs
        var bag: LinkedHashMap<String, Any?>? = null
        if (jsTypeof(value) == "object") {
            if (value == null) throw CosicError("Cannot read properties of null (reading 'value')")
            if (value is Map<*, *> && value.containsKey("value") && value["value"] !== Undefined) {
                @Suppress("UNCHECKED_CAST")
                val copy = LinkedHashMap(value as Map<String, Any?>)
                value = copy.remove("value")
                bag = copy
            }
        }
        if (spec.multi && value is List<*>) {
            val result = bag ?: LinkedHashMap<String, Any?>(8)
            val keys = spec.keys
            for (i in value.indices) {
                if (i < keys.size) result[keys[i]] = value[i]
            }
            return jsOrdered(result)
        }
        if (bag != null) {
            bag[spec.name] = value
            return jsOrdered(bag)
        }
        val single = LinkedHashMap<String, Any?>(4)
        single[spec.name] = value
        return single
    }

    /**
     * What a control function does (`func` of `createParam`): called without a pattern it is a pattern of the control
     * map [value] stands for; called with [receiver] and without a value it sets the control on every value
     * of the receiver; called with both it sets it with the structure of the receiver (`receiver.set(value)`, under the
     * default alignment).
     */
    fun applyControl(spec: ControlSpec, value: Any?, receiver: Any?): Pattern {
        if (!jsTruthy(receiver)) return reify(value).withValue { valueOf(spec, it) }
        val pat = receiver as? Pattern ?: throw CosicError("pat.set is not a function")
        if (value === Undefined) return pat.fmap { valueOf(spec, it) }
        return pat.setWith(reify(value).withValue { valueOf(spec, it) })
    }

    /**
     * A control of the given [names] that is not registered anywhere (`createParams` of the file): a function of a value and
     * an optional pattern for each name.
     */
    fun createParams(vararg names: String): Map<String, (Any?, Pattern?) -> Pattern> {
        val out = LinkedHashMap<String, (Any?, Pattern?) -> Pattern>()
        for (name in names) {
            val spec = ControlSpec(listOf(name), false, emptyList(), emptySet(), ControlDomain.UNDOCUMENTED, 0)
            out[name] = { value, pat -> applyControl(spec, value, pat) }
        }
        return out
    }

    /**
     * `registerControl` for a control a program defines: the canonical name (the first of [keys]) and the [aliases] become
     * control names, as `controlAlias` of the file does for every registration. [multi] is the array form (a program gave
     * the names as an array), in which a list value is spread over [keys].
     */
    fun registerCustom(keys: List<String>, multi: Boolean, aliases: List<String>): ControlSpec {
        val spec = ControlSpec(keys, multi, aliases, emptySet(), ControlDomain.UNDOCUMENTED, 0)
        customAliasOf[spec.name] = spec.name
        for (alias in aliases) customAliasOf[alias] = spec.name
        return spec
    }
}

/** `composeOp(a, b, (a, b) => b)`: what `set` makes of two values: [b] wins, and two control maps are merged key by key. */
internal fun composeSet(a: Any?, b: Any?): Any? = composeOp(a, b) { _, y -> y }

/**
 * `pat.set(other)`: the values of [other] win, the structure is [this]'s under the default alignment (`in`, unless
 * `setDefaultJoin` changed it). It goes through the registered `set` so that the alignments of the operator catalogue apply;
 * until that exists it is the `in` alignment, `fmap(set).appLeft(other)`.
 */
internal fun Pattern.setWith(other: Pattern): Pattern {
    val set = Registry.lookup("set")
    return if (set != null && set.hasMethod) set.method(this, listOf(other)) else zipLeft(other, ::composeSet)
}

/** The only method name that a function made by `register` replaces: `ds` is an alias of `delaysync`, then `register('ds', ...)` takes the method. */
private val METHODS_REPLACED_BY_REGISTER = setOf("ds")

// ---- the table ----------------------------------------------------------------------------------------------------

private class Statement(val specs: List<ControlSpec>)

/** One call of `registerControl(names, ...aliases)`, before its exports are known. */
private class Registration(val keys: List<String>, val multi: Boolean, val aliases: List<String>)

private const val MATRIX_SIZE = 9

/**
 * Parses [table] (see [CONTROL_TABLE]) by running what the statements it describes do: `registerControl` for a plain line,
 * `registerMultiControl` for a `*count` line, and the loop over the FM matrix.
 */
private fun parseControlTable(table: String): List<Statement> = table.lineSequence().filter { it.isNotBlank() }.map { row ->
    val tokens = row.trim().split(' ')
    val line = tokens[0].removePrefix("L").toInt()
    val domain = ControlDomain.of(tokens.last())
    val registrations: List<Registration>
    var exports: List<String>? = null
    if (tokens[1] == "matrix") {
        registrations = buildList {
            for (i in 0 until MATRIX_SIZE) for (j in 0 until MATRIX_SIZE) add(Registration(listOf("fmi$i$j"), false, listOf("fm$i$j")))
        }
        exports = emptyList()
    } else {
        val multi = '|' in tokens[1]
        val keys = tokens[1].split('|').filter { it.isNotEmpty() }
        var count = 0
        var aliases = emptyList<String>()
        for (token in tokens.subList(2, tokens.size - 1)) {
            when (token[0]) {
                '*' -> count = token.substring(1).toInt()
                '=' -> aliases = token.substring(1).split(',')
                '!' -> exports = token.substring(1).split(',')
                else -> error("bad token '$token' in the controls table, line $line")
            }
        }
        registrations = if (count > 0) registerMultiControl(keys, count, aliases) else listOf(Registration(keys, multi, aliases))
    }
    // The bag the statement returns: every name of every registration, a later registration replacing an earlier one.
    val bag = HashMap<String, Int>()
    for ((index, r) in registrations.withIndex()) {
        bag[r.keys[0]] = index
        for (alias in r.aliases) bag[alias] = index
    }
    val exported = exports ?: (listOf(registrations[0].keys[0]) + registrations[0].aliases).distinct()
    for (name in exported) check(name in bag) { "'$name' is exported but not registered, line $line" }
    Statement(
        registrations.mapIndexed { index, r ->
            ControlSpec(r.keys, r.multi, r.aliases, exported.filterTo(LinkedHashSet()) { bag[it] == index }, domain, line)
        },
    )
}.toList()

/** `registerMultiControl(names, maxControls, ...aliases)`: `fmh`, `fmh2`, ... with `fmh1`, `fmi1` as aliases of the first. */
private fun registerMultiControl(names: List<String>, maxControls: Int, aliases: List<String>): List<Registration> =
    (1..maxControls).map { i ->
        if (i == 1) {
            Registration(names, true, aliases + aliases.map { "${it}1" } + names.map { "${it}1" })
        } else {
            Registration(names.map { "$it$i" }, true, aliases.map { "$it$i" })
        }
    }

/**
 * The registrations of `controls.mjs`, one statement per line in the order of the file, as `tools/strudel-ref/controls-table.mjs`
 * prints them from the oracle's own record:
 *
 *     L<line> <keys> [*<count>] [=<aliases>] [!<exports>] <domain>        L<line> matrix <domain>
 *
 * `keys` is `a` for a control with one key and `a|b|c` for the array form (a trailing `|` is the array form with one key);
 * `*count` makes the statement a `registerMultiControl` call; `aliases` are the rest arguments; `exports` the global
 * names the file exports from the statement, when they are not just the key and the aliases; `domain` is a [ControlDomain] code.
 */
private const val CONTROL_TABLE = """
L111 s|n|gain =sound A
L123 wt =wavetablePosition A
L132 wtenv A
L141 wtattack =wtatt A
L151 wtdecay =wtdec A
L161 wtsustain =wtsus A
L171 wtrelease =wtrel A
L180 wtrate A
L188 wtsync A
L197 wtdepth A
L206 wtshape A
L215 wtdc A
L224 wtskew A
L237 warp =wavetableWarp A
L247 warpattack =warpatt A
L257 warpdecay =warpdec A
L267 warpsustain =warpsus A
L277 warprelease =warprel A
L286 warprate A
L295 warpdepth A
L304 warpshape A
L313 warpdc A
L322 warpskew A
L339 warpmode =wavetableWarpMode A
L352 wtphaserand =wavetablePhaseRand A
L361 warpenv A
L370 warpsync A
L382 source =src A
L396 n A
L407 i T
L431 note|n T
L444 accelerate SD
L456 velocity =vel A
L467 gain A
L478 postgain A
L490 amp SD
L511 fmh|fmi *8 =fmh !fmh,fmh1,fmh2,fmh3,fmh4,fmh5,fmh6,fmh7,fmh8 A
L536 fmi|fmh *8 =fm !fm,fm1,fm2,fm3,fm4,fm5,fm6,fm7,fm8,fmi,fmi1,fmi2,fmi3,fmi4,fmi5,fmi6,fmi7,fmi8 A
L559 fmenv| *8 =fme !fme,fmenv,fmenv1,fmenv2,fmenv3,fmenv4,fmenv5,fmenv6,fmenv7,fmenv8 A
L583 fmattack| *8 =fmatt !fmatt,fmatt1,fmatt2,fmatt3,fmatt4,fmatt5,fmatt6,fmatt7,fmatt8,fmattack,fmattack1,fmattack2,fmattack3,fmattack4,fmattack5,fmattack6,fmattack7,fmattack8 A
L619 fmwave| *8 !fmwave,fmwave1,fmwave2,fmwave3,fmwave4,fmwave5,fmwave6,fmwave7,fmwave8 A
L643 fmdecay| *8 =fmdec !fmdec,fmdec1,fmdec2,fmdec3,fmdec4,fmdec5,fmdec6,fmdec7,fmdec8,fmdecay,fmdecay1,fmdecay2,fmdecay3,fmdecay4,fmdecay5,fmdecay6,fmdecay7,fmdecay8 A
L683 fmsustain| *8 =fmsus !fmsus,fmsus1,fmsus2,fmsus3,fmsus4,fmsus5,fmsus6,fmsus7,fmsus8,fmsustain,fmsustain1,fmsustain2,fmsustain3,fmsustain4,fmsustain5,fmsustain6,fmsustain7,fmsustain8 A
L716 fmrelease| *8 =fmrel !fmrel,fmrel1,fmrel2,fmrel3,fmrel4,fmrel5,fmrel6,fmrel7,fmrel8,fmrelease,fmrelease1,fmrelease2,fmrelease3,fmrelease4,fmrelease5,fmrelease6,fmrelease7,fmrelease8 A
L742 matrix A
L758 bank A
L770 chorus A
L773 analyze V
L775 fft V
L788 attack =att A
L802 decay =dec A
L814 sustain =sus A
L826 release =rel A
L827 hold ?
L841 bandf|bandq|bpenv =bpf,bp A
L856 bandq =bpq A
L868 begin A
L880 end A
L893 loop A
L907 loopBegin =loopb A
L921 loopEnd =loope A
L933 crush A
L944 coarse A
L957 tremolo|tremolodepth|tremoloskew|tremolophase =trem A
L970 tremolosync|tremolodepth|tremoloskew|tremolophase =tremsync !tremolosync A
L986 tremolodepth =tremdepth !tremolodepth A
L998 tremoloskew =tremskew !tremoloskew A
L1011 tremolophase =tremphase !tremolophase A
L1024 tremoloshape =tremshape !tremoloshape A
L1035 drive A
L1055 duckorbit =duck !duck A
L1074 duckdepth A
L1103 duckonset =duckons !duckonset A
L1125 duckattack =duckatt,datt !duckattack A
L1139 byteBeatExpression =bbexpr,bb !bbexpr,byteBeatExpression A
L1153 byteBeatStartTime =bbst A
L1167 channels =ch A
L1180 pw|pwrate|pwsweep A
L1194 pwrate =pwr !pwrate A
L1207 pwsweep =pws !pwsweep A
L1221 phaserrate|phaserdepth|phasercenter|phasersweep =ph,phaser A
L1239 phasersweep =phs A
L1254 phasercenter =phc A
L1269 phaserdepth =phd,phasdp A
L1279 channel A
L1290 cut A
L1306 cutoff|resonance|lpenv =ctf,lpf,lp A
L1321 lpenv =lpe A
L1335 hpenv =hpe A
L1349 bpenv =bpe A
L1363 lpattack =lpa A
L1377 hpattack =hpa A
L1391 bpattack =bpa A
L1405 lpdecay =lpd A
L1420 hpdecay =hpd A
L1435 bpdecay =bpd A
L1450 lpsustain =lps A
L1465 hpsustain =hps A
L1480 bpsustain =bps A
L1496 lprelease =lpr A
L1512 hprelease =hpr A
L1528 bprelease =bpr A
L1543 ftype A
L1554 fanchor A
L1582 lprate A
L1593 lpsync A
L1605 lpdepth A
L1617 lpdepthfrequency =lpdepthfreq A
L1626 lpshape A
L1635 lpdc A
L1644 lpskew A
L1653 bprate A
L1662 bpsync A
L1671 bpdepth A
L1684 bpdepthfrequency =bpdepthfreq A
L1693 bpshape A
L1702 bpdc A
L1711 bpskew A
L1720 hprate A
L1729 hpsync A
L1738 hpdepth A
L1751 hpdepthfrequency =hpdepthfreq A
L1760 hpshape A
L1769 hpdc A
L1778 hpskew A
L1797 vib|vibmod =vibrato,v A
L1807 noise A
L1825 vibmod|vib =vmod A
L1826 hcutoff|hresonance|hpenv =hpf,hp A
L1838 hresonance =hpq A
L1851 resonance =lpq A
L1862 djf A
L1881 delay|delaytime|delayfeedback A
L1894 delayfeedback =delayfb,dfb A
L1909 delaytime =delayt,dt A
L1922 delaysync =delays,ds !delaysync A
L1937 lock SD
L1949 detune =det A
L1960 unison A
L1972 spread A
L1983 dry A
L1995 fadeTime =fadeOutTime SD
L1996 fadeInTime ?
L2009 freq A
L2022 pattack =patt A
L2034 pdecay =pdec A
L2036 psustain =psus ?
L2050 prelease =prel A
L2063 penv A
L2077 pcurve A
L2091 panchor A
L2093 gate =gat ?
L2114 leslie SD
L2127 lrate SD
L2139 lsize SD
L2147 activeLabel V
L2148 label|activeLabel V
L2162 degree ?
L2164 mtranspose ?
L2166 ctranspose ?
L2168 harmonic ?
L2170 stepsPerOctave ?
L2172 octaveR ?
L2175 nudge ?
L2187 octave =oct A
L2204 orbit =o !orbit A
L2217 bus A
L2227 busgain =bgain A
L2230 overgain ?
L2232 overshape ?
L2245 pan A
L2256 panspan SD
L2267 pansplay SD
L2268 panwidth ?
L2269 panorient ?
L2276 slide ?
L2278 semitone ?
L2281 voice ?
L2291 chord T
L2307 dictionary =dict T
L2318 anchor T
L2328 offset T
L2338 octaves T
L2356 mode|anchor T
L2372 room|size A
L2386 roomlp =rlp A
L2401 roomdim =rdim A
L2416 roomfade =rfade A
L2427 ir|i =iresponse A
L2439 irspeed A
L2452 irbegin A
L2470 roomsize =size,sz,rsize A
L2488 shape|shapevol A
L2508 distort|distortvol|distorttype =dist A
L2520 distortvol =distvol !distortvol A
L2538 distorttype =disttype !distorttype A
L2551 compressor|compressorRatio|compressorKnee|compressorAttack|compressorRelease A
L2558 compressorKnee A
L2559 compressorRatio A
L2560 compressorAttack A
L2561 compressorRelease A
L2574 speed A
L2590 stretch A
L2603 unit SD
L2617 squiz SD
L2644 vowel A
L2655 waveloss ?
L2666 density A
L2668 expression ?
L2669 sustainpedal ?
L2671 fshift ?
L2672 fshiftnote ?
L2673 fshiftphase ?
L2675 triode ?
L2676 krush ?
L2677 kcutoff ?
L2678 octer ?
L2679 octersub ?
L2680 octersubsub ?
L2681 ring ?
L2682 ringf ?
L2683 ringdf ?
L2684 freeze ?
L2685 xsdelay ?
L2686 tsdelay ?
L2687 real ?
L2688 imag ?
L2689 enhance ?
L2690 comb ?
L2691 smear ?
L2692 scram ?
L2693 binshift ?
L2694 hbrick ?
L2695 lbrick ?
L2697 frameRate ?
L2698 frames ?
L2699 hours ?
L2700 minutes ?
L2701 seconds ?
L2702 songPtr ?
L2703 uid ?
L2704 val ?
L2705 cps ?
L2717 clip =legato A
L2730 duration =dur A
L2733 zrand Z
L2734 curve Z
L2737 deltaSlide Z
L2738 pitchJump Z
L2739 pitchJumpTime Z
L2741 znoise Z
L2742 zmod Z
L2744 zcrush Z
L2745 zdelay Z
L2746 zzfx Z
L2755 color|colour V
L2806 midichan M
L2808 midimap M
L2819 midiport M
L2830 midicmd M
L2855 ccn M
L2863 ccv M
L2864 ctlNum M
L2875 nrpnn M
L2884 nrpv M
L2895 progNum M
L2921 sysexid M
L2930 sysexdata M
L2940 midibend M
L2949 miditouch M
L2952 polyTouch M
L2962 oschost O
L2972 oscport O
L3302 transient|transsustain A
L3304 FXrelease =FXrel,FXr,fxr A
"""
