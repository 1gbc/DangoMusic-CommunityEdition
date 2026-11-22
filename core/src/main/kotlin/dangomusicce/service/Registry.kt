package dangomusicce.service

interface Registry<T> {
    fun register(item: T)
    fun unregister(item: T)
}