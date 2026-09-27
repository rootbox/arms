package com.arms.androidauto.core.remote

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.future.await
import java.net.URI

// HiveMQ MQTT Client(MQTT 3.1.1, async API)를 suspend 함수로 감싼 전송. 브로커에는 암호문만 보낸다.
// brokerUrl:
//   wss://host[:port][/path]  — WebSocket + TLS (운영 기본, 리버스 프록시 뒤 Mosquitto)
//   ws://host[:port][/path]   — WebSocket 평문 (개발용)
//   ssl://host[:port], mqtts:// — TCP + TLS
//   tcp://host[:port], mqtt://  — TCP 평문 (개발용)
// 콜백(connected/disconnected/subscribe)은 netty 스레드에서 불린다. 예외를 밖으로 내지 않는다.
class MqttRemoteTransport(
    private val brokerUrl: String,
    private val username: String,
    private val password: String,
    private val clientId: String,
) : RemoteTransport {
    private val state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> get() = state

    private class Endpoint(val host: String, val port: Int, val tls: Boolean, val webSocketPath: String?)

    private val endpoint: Endpoint by lazy { parse(brokerUrl) }

    private val client: Mqtt3AsyncClient by lazy {
        val ep = endpoint
        var builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier(clientId)
            .serverHost(ep.host)
            .serverPort(ep.port)
            .automaticReconnectWithDefaultConfig()
            .addConnectedListener {
                state.value = ConnectionState.CONNECTED
            }
            .addDisconnectedListener { context ->
                try {
                    // clean session이므로 재접속 후 브로커에 구독이 없다. 라이브러리가 다시 구독하게 한다.
                    context.reconnector.resubscribeIfSessionExpired(true)
                    state.value = if (context.reconnector.isReconnect) ConnectionState.CONNECTING else ConnectionState.DISCONNECTED
                } catch (_: Throwable) {
                    state.value = ConnectionState.DISCONNECTED
                }
            }
        if (ep.tls) builder = builder.sslWithDefaultConfig()
        if (ep.webSocketPath != null) {
            builder = builder.webSocketConfig().serverPath(ep.webSocketPath).applyWebSocketConfig()
        }
        builder.buildAsync()
    }

    override suspend fun connect() {
        if (state.value == ConnectionState.CONNECTED) return
        state.value = ConnectionState.CONNECTING
        try {
            client.connectWith()
                .keepAlive(KEEP_ALIVE_SECONDS)
                .cleanSession(true)
                .simpleAuth()
                .username(username)
                .password(password.toByteArray(Charsets.UTF_8))
                .applySimpleAuth()
                .send()
                .await()
            state.value = ConnectionState.CONNECTED
        } catch (e: Exception) {
            // 자동 재접속이 켜져 있으면 백그라운드에서 계속 시도하며 리스너가 상태를 갱신한다.
            if (state.value == ConnectionState.CONNECTING) state.value = ConnectionState.DISCONNECTED
            throw e
        }
    }

    override suspend fun publish(topic: String, payload: ByteArray, retain: Boolean) {
        client.publishWith()
            .topic(topic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(retain)
            .payload(payload)
            .send()
            .await()
    }

    override suspend fun subscribe(topic: String, onMessage: (ByteArray) -> Unit) {
        client.subscribeWith()
            .topicFilter(topic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .callback { publish ->
                try {
                    onMessage(publish.payloadAsBytes)
                } catch (_: Throwable) {
                    // 콜백은 netty 스레드: 예외를 전파하면 연결이 끊긴다. 조용히 무시한다.
                }
            }
            .send()
            .await()
    }

    override suspend fun disconnect() {
        try {
            client.disconnect().await()
        } catch (_: Exception) {
            // 이미 끊겨 있으면 무시
        } finally {
            state.value = ConnectionState.DISCONNECTED
        }
    }

    private companion object {
        const val KEEP_ALIVE_SECONDS = 60

        fun parse(url: String): Endpoint {
            val uri = try {
                URI(url.trim())
            } catch (e: Exception) {
                throw IllegalArgumentException("invalid broker url", e)
            }
            val host = uri.host ?: throw IllegalArgumentException("broker url has no host")
            val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("broker url has no scheme")
            val (tls, ws, defaultPort) = when (scheme) {
                "wss" -> Triple(true, true, 443)
                "ws" -> Triple(false, true, 80)
                "ssl", "mqtts" -> Triple(true, false, 8883)
                "tcp", "mqtt" -> Triple(false, false, 1883)
                else -> throw IllegalArgumentException("unsupported broker url scheme: $scheme")
            }
            val port = if (uri.port > 0) uri.port else defaultPort
            val wsPath = if (ws) (uri.rawPath ?: "").trimStart('/') else null
            return Endpoint(host = host, port = port, tls = tls, webSocketPath = wsPath)
        }
    }
}
