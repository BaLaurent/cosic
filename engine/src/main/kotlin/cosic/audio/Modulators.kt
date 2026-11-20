package cosic.audio

/**
 * The modulatable parameters of one effect chain of a voice, by their Web Audio names: the node (`lpf`, `gain`, `lfo_0`) and the parameter on it
 * (`frequency`, `Q`). Stages put their parameters in ([ChainStage.registerParams]); the modulators look their targets up. Fixed capacity,
 * never allocates.
 *
 * Task 3.10 (modulators) owns this file and adds the lookup it needs.
 */
internal class NodeRegistry(capacity: Int = 64) {
    private val nodes = arrayOfNulls<String>(capacity)
    private val params = arrayOfNulls<String>(capacity)
    private val targets = arrayOfNulls<AParam>(capacity)
    private var count = 0

    /** The number of registered parameters. */
    val size: Int get() = count

    fun clear() {
        for (i in 0 until count) targets[i] = null
        count = 0
    }

    /** Registers [p] as parameter [param] of node [node]; several parameters may share a name (one per filter in series). */
    fun register(node: String, param: String, p: AParam) {
        if (count >= targets.size) return
        nodes[count] = node
        params[count] = param
        targets[count] = p
        count++
    }

    /** The [index]-th parameter registered under [node] and [param], or null. */
    fun find(node: String, param: String, index: Int = 0): AParam? {
        var seen = 0
        for (i in 0 until count) {
            if (nodes[i] == node && params[i] == param) {
                if (seen == index) return targets[i]
                seen++
            }
        }
        return null
    }

}

/**
 * Connects the `.lfo`, `.env` and `.bmod` modulators of a voice to the parameters they target (`superdough.mjs:1004-1049`, modulators.mjs).
 * [groups] are the effect chains of the voice, [registryFor] gives the [NodeRegistry] of a chain by its `fxi` (`main` or an index).
 *
 * Stub of Task 3.1: connects nothing. Task 3.10 implements it.
 */
@Suppress("UNUSED_PARAMETER")
internal fun connectModulators(
    mods: List<ModSpec>, groups: Array<StageGroup>, registryFor: (String) -> NodeRegistry,
    t: Double, endWithRelease: Double, cps: Double, cycle: Double, buses: AudioBuses,
) {
}
