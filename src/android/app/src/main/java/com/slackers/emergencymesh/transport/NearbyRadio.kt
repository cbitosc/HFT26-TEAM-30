package com.slackers.emergencymesh.transport

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionOptions
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionType
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.Task

internal data class RadioResult(val ok: Boolean, val code: Int = 0, val message: String = "")

internal enum class TransferStatus { IN_PROGRESS, SUCCESS, FAILURE }

internal class OutgoingPayload(val id: Long, val native: Any?)

internal interface NearbyRadioListener {
    fun onEndpointFound(endpointId: String, name: String)
    fun onEndpointLost(endpointId: String)
    fun onConnectionInitiated(endpointId: String, name: String, authDigits: String)
    fun onConnectionResult(endpointId: String, connected: Boolean, code: Int)
    fun onDisconnected(endpointId: String)
    fun onBytesReceived(endpointId: String, bytes: ByteArray?)
    fun onTransferUpdate(payloadId: Long, status: TransferStatus)
}

/** The Nearby calls the transport needs, kept small so the logic can run on a fake radio. */
internal interface NearbyRadio {
    var listener: NearbyRadioListener?

    fun startAdvertising(name: String, onResult: (RadioResult) -> Unit)
    fun startDiscovery(onResult: (RadioResult) -> Unit)
    fun stopAdvertising()
    fun stopDiscovery()
    fun stopAllEndpoints()
    fun requestConnection(name: String, endpointId: String, onResult: (RadioResult) -> Unit)
    fun acceptConnection(endpointId: String, onResult: (RadioResult) -> Unit)
    fun rejectConnection(endpointId: String)
    fun disconnect(endpointId: String)
    fun preparePayload(bytes: ByteArray): OutgoingPayload
    fun sendPayload(endpointId: String, payload: OutgoingPayload, onResult: (RadioResult) -> Unit)
}

internal class GmsNearbyRadio(
    context: Context,
    private val serviceId: String = context.packageName,
) : NearbyRadio {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)
    private val strategy = Strategy.P2P_CLUSTER

    override var listener: NearbyRadioListener? = null

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val digits: String? = info.authenticationDigits
            listener?.onConnectionInitiated(
                endpointId,
                info.endpointName,
                digits ?: info.authenticationToken.orEmpty(),
            )
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val status = result.status
            listener?.onConnectionResult(endpointId, status.isSuccess, status.statusCode)
        }

        override fun onDisconnected(endpointId: String) {
            listener?.onDisconnected(endpointId)
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            listener?.onEndpointFound(endpointId, info.endpointName)
        }

        override fun onEndpointLost(endpointId: String) {
            listener?.onEndpointLost(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = if (payload.type == Payload.Type.BYTES) payload.asBytes() else null
            listener?.onBytesReceived(endpointId, bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val status = when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> TransferStatus.SUCCESS
                PayloadTransferUpdate.Status.IN_PROGRESS -> TransferStatus.IN_PROGRESS
                else -> TransferStatus.FAILURE
            }
            listener?.onTransferUpdate(update.payloadId, status)
        }
    }

    override fun startAdvertising(name: String, onResult: (RadioResult) -> Unit) {
        val options = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .setConnectionType(ConnectionType.NON_DISRUPTIVE)
            .build()
        client.startAdvertising(name, serviceId, lifecycleCallback, options).report(onResult)
    }

    override fun startDiscovery(onResult: (RadioResult) -> Unit) {
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(serviceId, discoveryCallback, options).report(onResult)
    }

    override fun stopAdvertising() = client.stopAdvertising()

    override fun stopDiscovery() = client.stopDiscovery()

    override fun stopAllEndpoints() = client.stopAllEndpoints()

    override fun requestConnection(name: String, endpointId: String, onResult: (RadioResult) -> Unit) {
        val options = ConnectionOptions.Builder()
            .setConnectionType(ConnectionType.NON_DISRUPTIVE)
            .build()
        client.requestConnection(name, endpointId, lifecycleCallback, options).report(onResult)
    }

    override fun acceptConnection(endpointId: String, onResult: (RadioResult) -> Unit) {
        client.acceptConnection(endpointId, payloadCallback).report(onResult)
    }

    override fun rejectConnection(endpointId: String) {
        client.rejectConnection(endpointId)
    }

    override fun disconnect(endpointId: String) = client.disconnectFromEndpoint(endpointId)

    override fun preparePayload(bytes: ByteArray): OutgoingPayload {
        val payload = Payload.fromBytes(bytes)
        return OutgoingPayload(payload.id, payload)
    }

    override fun sendPayload(
        endpointId: String,
        payload: OutgoingPayload,
        onResult: (RadioResult) -> Unit,
    ) {
        client.sendPayload(endpointId, payload.native as Payload).report(onResult)
    }

    private fun Task<Void>.report(onResult: (RadioResult) -> Unit) {
        addOnSuccessListener { onResult(RadioResult(true)) }
        addOnFailureListener { error ->
            val code = (error as? ApiException)?.statusCode ?: -1
            onResult(RadioResult(false, code, error.message.orEmpty()))
        }
    }
}
