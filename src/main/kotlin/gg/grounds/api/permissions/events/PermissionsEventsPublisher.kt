package gg.grounds.api.permissions.events

import gg.grounds.grpc.permissions.PermissionsChangeEvent
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.helpers.MultiEmitterProcessor
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class PermissionsEventsPublisher {
    private val processor = MultiEmitterProcessor.create<PermissionsChangeEvent>()
    private val broadcast = processor.toMulti().broadcast().toAllSubscribers()

    fun stream(): Multi<PermissionsChangeEvent> = broadcast

    fun publish(event: PermissionsChangeEvent) {
        processor.onNext(event)
    }
}
