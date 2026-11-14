package com.gonzalo.controltv.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Vinculación y control completos, con TLS real, contra [FakeTv]. */
class FakeTvTest {
    private lateinit var tv: FakeTv
    private lateinit var phoneCerts: CertManager
    private lateinit var phone: ClientIdentity
    private val logLines = LinkedBlockingQueue<String>()
    private val log = TvLog { logLines.add(it); println("  [log] $it") }

    @Before
    fun setUp() {
        tv = FakeTv(createTempDirectory())
        phoneCerts = CertManager(createTempDirectory())
        phone = phoneCerts.identity()
    }

    @After
    fun tearDown() = tv.close()

    private fun pair(): String {
        val client = PairingClient("127.0.0.1", phone, log, tv.pairingPort)
        client.start("Mi control")
        val code = tv.shownCodes.poll(5, TimeUnit.SECONDS)!!
        assertTrue(client.isCodePlausible(code))
        return client.finish(code)
    }

    @Test
    fun pairsWithTheCodeShownOnTv() {
        val fingerprint = pair()
        assertEquals(tv.fingerprint, fingerprint)
        assertEquals("Mi control", tv.pairingNames.poll())
        assertEquals(listOf(Tls.sha256Hex(phone.certificate.encoded)), tv.pairedClients.toList())
    }

    @Test
    fun mistypedCodeIsCaughtBeforeSending() {
        val client = PairingClient("127.0.0.1", phone, log, tv.pairingPort)
        client.start("Mi control")
        val code = tv.shownCodes.poll(5, TimeUnit.SECONDS)!!
        // Cambiar el primer par de caracteres: el byte de control ya no coincide.
        val wrong = "%02X".format((code.substring(0, 2).toInt(16) + 1) and 0xFF) + code.substring(2)
        assertFalse(client.isCodePlausible(wrong))
        try {
            client.finish(wrong)
            fail("tendría que haber rechazado el código")
        } catch (_: WrongCodeException) {
        }
        assertTrue(tv.pairedClients.isEmpty())
        // El código correcto sigue andando en la misma conexión.
        assertEquals(tv.fingerprint, client.finish(code))
    }

    @Test
    fun controlSessionHandshakeKeysTextAndApps() {
        val fingerprint = pair()
        val ready = CountDownLatch(1)
        val apps = LinkedBlockingQueue<String>()
        val volumes = LinkedBlockingQueue<VolumeInfo>()
        var tvInfo: TvInfo? = null
        val client = RemoteClient("127.0.0.1", phone, fingerprint, log, object : RemoteClient.Listener {
            override fun onReady(info: TvInfo?, poweredOn: Boolean) {
                tvInfo = info
                assertTrue(poweredOn)
                ready.countDown()
            }
            override fun onPowerChanged(poweredOn: Boolean) = Unit
            override fun onAppChanged(appPackage: String) { apps.add(appPackage) }
            override fun onVolumeChanged(volume: VolumeInfo) { volumes.add(volume) }
        }, tv.controlPort)
        val runner = thread { runCatching { client.run() } }

        // Lo que manda el celu al arrancar.
        val configure = tv.nextReceived().message(1)!!
        assertEquals(615, configure.int(1)) // 623 de la tele ∩ 615 pedido
        assertEquals(615, tv.nextReceived().message(2)!!.int(1))
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        assertEquals("Google", tvInfo?.vendor)
        assertEquals("Chromecast", tvInfo?.model)
        assertEquals("com.netflix.ninja", apps.poll(5, TimeUnit.SECONDS))
        assertEquals(VolumeInfo(12, 100, false), volumes.poll(5, TimeUnit.SECONDS))
        // Responde el ping con el mismo valor.
        assertEquals(42, tv.nextReceived().message(9)!!.int(1))

        client.sendKey(Keys.DPAD_UP)
        val key = tv.nextReceived().message(10)!!
        assertEquals(19, key.int(1))
        assertEquals(3, key.int(2))

        assertTrue(client.textFieldOpen) // la tele avisó un campo enfocado (contadores 5 y 7)
        client.sendText("hola")
        val ime = tv.nextReceived().message(21)!!
        assertEquals(5, ime.int(1)) // contadores tomados de ime_key_inject
        assertEquals(7, ime.int(2))
        assertEquals("hola", ime.message(3)!!.message(2)!!.string(3))

        client.launchApp("netflix://")
        assertEquals("netflix://", tv.nextReceived().message(90)!!.string(1))

        client.close()
        runner.join(5000)
    }

    @Test
    fun unknownPhoneNeedsPairing() {
        // El celu nunca se vinculó: la tele corta el TLS.
        val client = RemoteClient("127.0.0.1", phone, tv.fingerprint, log, NoListener, tv.controlPort)
        try {
            client.run()
            fail("tendría que pedir vincular")
        } catch (e: NeedsPairingException) {
            assertFalse(e.definitive)
        }
    }

    @Test
    fun differentTvCertificateNeedsPairing() {
        pair()
        val client = RemoteClient("127.0.0.1", phone, "00".repeat(32), log, NoListener, tv.controlPort)
        try {
            client.run()
            fail("tendría que pedir vincular")
        } catch (e: NeedsPairingException) {
            assertTrue(e.definitive)
        }
    }

    @Test
    fun connectionReconnectsAndReportsState() = runBlocking<Unit> {
        val fingerprint = pair()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connection = TvConnection(phoneCerts, log, scope, tv.controlPort)
        try {
            connection.setTarget(TvConnection.Target("127.0.0.1", fingerprint))
            connection.start()
            val connected = withTimeout(10_000) {
                connection.state.first { it is TvState.Conectado && it.app != null && it.volumen != null }
            } as TvState.Conectado
            assertEquals("com.netflix.ninja", connected.app)
            assertEquals(12, connected.volumen?.level)

            assertTrue(connection.sendKey(Keys.VOLUME_UP))
            var got: ProtoMessage
            do { got = tv.nextReceived() } while (!got.has(10))
            assertEquals(24, got.message(10)!!.int(1))

            // La tele corta: se tiene que reconectar sola.
            tv.dropSessions()
            withTimeout(10_000) { connection.state.first { it !is TvState.Conectado } }
            withTimeout(10_000) { connection.state.first { it is TvState.Conectado } }
        } finally {
            connection.stop()
            scope.cancel()
        }
    }

    @Test
    fun manyRestartsLeaveASingleSession() = runBlocking<Unit> {
        val fingerprint = pair()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connection = TvConnection(phoneCerts, log, scope, tv.controlPort)
        try {
            connection.start()
            // Muchos reinicios seguidos (como al guardar la vinculación): no puede quedar ninguna sesión huérfana.
            repeat(40) { i ->
                connection.setTarget(TvConnection.Target(if (i % 2 == 0) "127.0.0.1" else "localhost", fingerprint))
                Thread.sleep((i % 4).toLong())
            }
            withTimeout(10_000) { connection.state.first { it is TvState.Conectado } }
            Thread.sleep(1500)
            assertEquals(1, tv.openSessions())
            connection.stop()
            Thread.sleep(500)
            assertEquals(0, tv.openSessions())
        } finally {
            connection.stop()
            scope.cancel()
        }
    }

    @Test
    fun connectionStopsRetryingWhenPhoneIsUnknown() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connection = TvConnection(phoneCerts, log, scope, tv.controlPort)
        try {
            connection.setTarget(TvConnection.Target("127.0.0.1", tv.fingerprint))
            connection.start()
            val state = withTimeout(10_000) { connection.state.first { it is TvState.HayQueVincular } }
            assertTrue(state is TvState.HayQueVincular)
        } finally {
            connection.stop()
            scope.cancel()
        }
    }

    private object NoListener : RemoteClient.Listener {
        override fun onReady(info: TvInfo?, poweredOn: Boolean) = Unit
        override fun onPowerChanged(poweredOn: Boolean) = Unit
        override fun onAppChanged(appPackage: String) = Unit
        override fun onVolumeChanged(volume: VolumeInfo) = Unit
    }
}

