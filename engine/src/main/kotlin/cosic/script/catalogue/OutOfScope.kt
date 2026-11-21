package cosic.script.catalogue

/*
 * The entry points of what the parity plan rules out (decision 4): Hydra, the live-coded visuals of `@strudel/hydra` (`initHydra`, `clearHydra`, `H`), and CSound,
 * the sound language of `@strudel/csound` (`loadCsound` and its spellings `loadCSound` and `loadcsound`, `loadOrc`, and the function and method `csound` and
 * `csoundm`). A program that calls one is an error at that call, `'<name>' is not available in this app`, as every error of a program is reported: the message
 * and the range of the call. They are defined, not left to `x is not defined`, so that the program learns that the feature is known and not offered, and
 * the code of an example that uses one (the tunes `csoundDemo` and `loungeSponge`) stops there, which is the honest outcome.
 *
 * The parity harness does not see these: Strudel's own test runtime replaces `csound`, `loadCsound`, `loadCSound`, `loadcsound` and `loadOrc` with mocks that do
 * nothing, and `ParityHost` repeats them over this catalogue, so that the part of those two tunes that is patterns is still compared. Nothing about Hydra
 * is in the oracle's runtime. `OutOfScopeTest` checks what a program of the app gets.
 */

/** The global functions of Hydra and CSound: each is an error when called. */
internal val OUT_OF_SCOPE_FUNCTIONS: List<String> =
    listOf("initHydra", "clearHydra", "H", "loadCsound", "loadCSound", "loadcsound", "loadOrc", "csound", "csoundm")

/** The methods of patterns that belong to CSound: each is an error when called. */
internal val OUT_OF_SCOPE_METHODS: List<String> = listOf("csound", "csoundm")

internal fun registerOutOfScope() = catalogue("OutOfScope") {
    for (name in OUT_OF_SCOPE_FUNCTIONS) {
        function(listOf(name), 0) { ctx -> ctx.fail("'$name' is not available in this app") }
    }
    for (name in OUT_OF_SCOPE_METHODS) {
        method(listOf(name), 0) { ctx -> ctx.fail("'$name' is not available in this app") }
    }
}
