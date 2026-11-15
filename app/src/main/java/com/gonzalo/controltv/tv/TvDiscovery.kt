package com.gonzalo.controltv.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Una tele que anuncia el control remoto en la red: su nombre (el de la tele) y su IP. */
data class DiscoveredTv(val name: String, val host: String)

/**
 * Busca las teles de la casa: las Google TV y Android TV anuncian el control remoto por mDNS como
 * `_androidtvremote2._tcp`, con el nombre que tienen puesto (por ejemplo, "Comedor").
 */
class TvDiscovery(context: Context, private val log: TvLog) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    /** La lista de teles encontradas, que crece mientras se recolecta. */
    fun discover(): Flow<List<DiscoveredTv>> = callbackFlow {
        val found = LinkedHashMap<String, DiscoveredTv>()
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                toResolve.trySend(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                if (found.remove(info.serviceName) != null) trySend(found.values.toList())
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                log.log("✗ No se pudo buscar teles en la red (error $errorCode)")
                close()
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        trySend(emptyList())
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        // De a una: en Android viejo, resolver dos a la vez falla con FAILURE_ALREADY_ACTIVE.
        val resolver = launch {
            for (info in toResolve) {
                val tv = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolve(info) } ?: continue
                found[tv.name] = tv
                trySend(found.values.toList())
            }
        }
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            resolver.cancel()
        }
    }

    private suspend fun resolve(info: NsdServiceInfo): DiscoveredTv? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= 34) {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(si: NsdServiceInfo) {
                    val host = si.hostAddresses.firstOrNull { it is Inet4Address } ?: si.hostAddresses.firstOrNull() ?: return
                    runCatching { nsd.unregisterServiceInfoCallback(this) }
                    if (cont.isActive) cont.resume(DiscoveredTv(si.serviceName, host.hostAddress ?: return))
                }

                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceLost() = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            nsd.registerServiceInfoCallback(info, executor, callback)
            cont.invokeOnCancellation { runCatching { nsd.unregisterServiceInfoCallback(callback) } }
        } else {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(si: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = si.host?.hostAddress
                    if (cont.isActive) cont.resume(host?.let { DiscoveredTv(si.serviceName, it) })
                }

                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        }
    }

    companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp"
        private const val RESOLVE_TIMEOUT_MS = 4_000L
    }
}

/**
 * Encuentra en qué IP está ahora una tele ya vinculada (por ejemplo, si el router le dio otra): busca en la red
 * y compara la huella del certificado de cada tele con la guardada. Así nunca se confunde con la del vecino.
 */
class TvLocator(
    private val discover: () -> Flow<List<DiscoveredTv>>,
    private val certs: CertManager,
    private val log: TvLog,
    private val port: Int = PAIRING_PORT,
) {

    /** La IP donde está la tele con [fingerprint], o null si no aparece en [timeoutMs]. */
    suspend fun locate(fingerprint: String, timeoutMs: Long = 10_000): String? {
        val probed = HashSet<String>()
        return withTimeoutOrNull(timeoutMs) {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            discover()
                .flatMapConcat { list -> list.filter { probed.add(it.host) }.asFlow() }
                .filter { tv -> fingerprintOf(tv.host) == fingerprint }
                .map { it.host }
                .firstOrNull() // la búsqueda terminó sin encontrarla
        }
    }

    /** La huella del certificado de la tele en [host] (por el puerto de vinculación, que acepta a cualquiera). */
    suspend fun fingerprintOf(host: String): String? = withContext(Dispatchers.IO) {
        val trust = Tls.CapturingTrustManager()
        // Aunque el TLS se corte después, el certificado de la tele ya llegó: alcanza para compararlo.
        runCatching { Tls.connect(host, port, certs.identity(), trust).use { } }
            .onFailure { if (trust.serverCertificate == null) log.log("· No se pudo mirar el certificado de $host: ${it.message}") }
        trust.serverCertificate?.let { Tls.sha256Hex(it.encoded) }
    }
}
