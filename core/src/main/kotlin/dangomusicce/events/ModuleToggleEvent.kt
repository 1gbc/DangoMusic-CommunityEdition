package dangomusicce.events

import dangomusicce.event.Event
import dangomusicce.module.Module

abstract class ModuleToggleEvent(
    val module: Module,
) : Event {
    class Enable(module: Module) : ModuleToggleEvent(module)
    class Disable(module: Module) : ModuleToggleEvent(module)
}