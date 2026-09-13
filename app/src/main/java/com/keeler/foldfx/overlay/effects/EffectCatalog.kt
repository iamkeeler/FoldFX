package com.keeler.foldfx.overlay.effects

/**
 * Single home for the effect catalog: the settings picker and the overlay
 * manager both read from here, so they can never disagree about which
 * effects exist or what their IDs are.
 */
object EffectCatalog {
    val all: List<FoldEffect> = listOf(BookFoldEffect(), FadeEffect(), PageTurnEffect())
    val defaultId: String = all.first().id
}
