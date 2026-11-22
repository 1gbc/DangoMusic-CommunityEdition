package dangomusicce.events

import dangomusicce.event.Event

data class WorldRenderEndEvent(
    val partialTicks: Float,
    val playerEyeY: Double? = null,
) : Event