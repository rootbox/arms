package com.arms.androidauto.core.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// 테스트용 인메모리 브로커: 토픽별 구독자 목록 + retained 메시지(빈 페이로드 publish = 삭제).
// 구독 시 retained가 있으면 즉시 전달한다(MQTT와 동일).
class InMemoryBroker {
    private val lock = Any()
    private val retained = HashMap<String, ByteArray>()
    private val subscribers = HashMap<String, MutableList<(ByteArray) -> Unit>>()
    val published = ArrayList<Triple<String, ByteArray, Boolean>>()

    fun publish(topic: String, payload: ByteArray, retain: Boolean) {
        val targets: List<(ByteArray) -> Unit>
        synchronized(lock) {
            published.add(Triple(topic, payload, retain))
            if (retain) {
                if (payload.isEmpty()) retained.remove(topic) else retained[topic] = payload
            }
            targets = subscribers[topic]?.toList() ?: emptyList()
        }
        targets.forEach { it(payload) }
    }

    fun subscribe(topic: String, onMessage: (ByteArray) -> Unit) {
        val initial: ByteArray?
        synchronized(lock) {
            subscribers.getOrPut(topic) { ArrayList() }.add(onMessage)
            initial = retained[topic]
        }
        initial?.let(onMessage)
    }

    fun retained(topic: String): ByteArray? = synchronized(lock) { retained[topic] }
}

class InMemoryRemoteTransport(private val broker: InMemoryBroker) : RemoteTransport {
    private val state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> get() = state
    var connectCalls = 0
    var disconnectCalls = 0

    override suspend fun connect() {
        connectCalls++
        state.value = ConnectionState.CONNECTED
    }

    override suspend fun publish(topic: String, payload: ByteArray, retain: Boolean) {
        check(state.value == ConnectionState.CONNECTED) { "not connected" }
        broker.publish(topic, payload, retain)
    }

    override suspend fun subscribe(topic: String, onMessage: (ByteArray) -> Unit) {
        check(state.value == ConnectionState.CONNECTED) { "not connected" }
        broker.subscribe(topic, onMessage)
    }

    override suspend fun disconnect() {
        disconnectCalls++
        state.value = ConnectionState.DISCONNECTED
    }
}
