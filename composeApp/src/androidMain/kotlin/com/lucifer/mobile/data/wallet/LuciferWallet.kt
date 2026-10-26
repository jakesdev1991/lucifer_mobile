package com.lucifer.mobile.data.wallet

import android.content.Context
import android.util.Log
import com.lucifer.mobile.security.SecretStore
import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.math.ec.ECPoint
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * LuciferWallet — native wallet ops. No Kai sandbox.
 *
 * Real secp256k1 + Keccak-256 via BouncyCastle: key generation and Ethereum-style
 * address derivation, JSON-RPC balance lookups against public endpoints, and
 * persistence to app-private files/wallets/. Transaction signing is not exposed;
 * legacy raw-hash requests cannot enforce a spending policy.
 *
 * Trust model: private keys live in app-private storage (this is Jake's
 * headless agent phone by design). EVM chains only — same scope as wallet_ops.py.
 */
class LuciferWallet(private val context: Context? = null) {

    companion object {
        private const val TAG = "LuciferWallet"

        private val CURVE = CustomNamedCurves.getByName("secp256k1")
        private val DOMAIN = ECDomainParameters(CURVE.curve, CURVE.g, CURVE.n, CURVE.h)
        private val HALF_N = CURVE.n.shiftRight(1)

        private val CHAIN_RPC = mapOf(
            "ethereum" to "https://ethereum-rpc.publicnode.com",
            "arbitrum" to "https://arb1.arbitrum.io/rpc",
            "optimism" to "https://optimism-rpc.publicnode.com",
            "base" to "https://base-rpc.publicnode.com",
            "polygon" to "https://polygon-bor-rpc.publicnode.com"
        )

        private val CHAIN_SYMBOL = mapOf(
            "polygon" to "POL"
        )
    }

    private fun walletsDir(): File? {
        val ctx = context ?: return null
        return File(ctx.filesDir, "wallets").apply { mkdirs() }
    }

    /** Generate a fresh keypair; returns null only on crypto failure. */
    suspend fun generateWallet(chain: String = "arbitrum"): Wallet? {
        return try {
            val priv = BigInteger(256, SecureRandom()).mod(CURVE.n - BigInteger.ONE).add(BigInteger.ONE)
            val address = deriveAddress(priv)
            val wallet = Wallet(
                address = address,
                chain = chain,
                balance = "0",
                symbol = CHAIN_SYMBOL[chain] ?: "ETH",
                usdValue = "0.00"
            )
            if (!persist(chain, address, priv)) return null
            Log.i(TAG, "wallet generated: $address ($chain)")
            wallet
        } catch (e: Exception) {
            Log.e(TAG, "generateWallet failed: ${e.message}", e)
            null
        }
    }

    /** Load the persisted private key for an address (null if unknown). */
    private fun privateKeyFor(address: String): String? {
        val dir = walletsDir() ?: return null
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return null
        for (f in files) {
            try {
                val o = JSONObject(f.readText())
                if (o.optString("address", "").equals(address, ignoreCase = true)) {
                    val secretPath = "wallets/${f.name}.key"
                    SecretStore.read(context ?: return null, secretPath)?.let { return it }

                    // Migrate keys written by older releases out of plaintext wallet JSON.
                    val legacyKey = o.optString("privateKey", "").takeIf { it.isNotBlank() } ?: continue
                    val ctx = context ?: return null
                    if (!SecretStore.write(ctx, secretPath, legacyKey)) return null
                    o.remove("privateKey")
                    f.writeText(o.toString())
                    return legacyKey
                }
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Sign a 32-byte message hash. RFC-6979 deterministic ECDSA, low-s
     * normalized, Ethereum-style recovery byte (v = 27/28). Returns 65-byte
     * signature as 0x-hex, or null on failure.
     */
    private suspend fun signMessageHash(messageHash: ByteArray, privateKeyHex: String): String? {
        if (messageHash.size != 32) return null
        return try {
            val priv = BigInteger(privateKeyHex.removePrefix("0x"), 16)
            val pub = CURVE.g.multiply(priv).normalize()

            val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
            signer.init(true, ECPrivateKeyParameters(priv, DOMAIN))
            val raw = signer.generateSignature(messageHash)
            val r = BigInteger(1, raw[0].toByteArray())
            val s = BigInteger(1, raw[1].toByteArray())

            // EIP-2: low-s normalization
            val sNorm = if (s > HALF_N) CURVE.n - s else s

            // Recovery byte: find recId such that recovered pubkey == ours
            var recId = -1
            for (candidate in 0 until 4) {
                val recovered = recoverPublicKey(candidate, r, sNorm, messageHash)
                if (recovered != null && recovered == pub) {
                    recId = candidate
                    break
                }
            }
            if (recId < 0) {
                Log.e(TAG, "signature recovery failed — cannot compute v")
                return null
            }

            val sig = ByteArray(65)
            System.arraycopy(to32(r), 0, sig, 0, 32)
            System.arraycopy(to32(sNorm), 0, sig, 32, 32)
            sig[64] = (recId + 27).toByte()
            "0x" + sig.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "signMessageHash failed: ${e.message}", e)
            null
        }
    }

    /** Balance via public JSON-RPC eth_getBalance (wei → decimal ether string). */
    suspend fun getBalance(address: String, chain: String = "ethereum"): String? {
        val rpc = CHAIN_RPC[chain.lowercase()] ?: return null
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(rpc).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val body = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "eth_getBalance")
                put("params", org.json.JSONArray().put(address).put("latest"))
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val hexWei = JSONObject(text).optString("result", "")
                if (hexWei.startsWith("0x") && hexWei.length > 2) {
                    BigDecimal(BigInteger(hexWei.substring(2), 16))
                        .divide(BigDecimal("1000000000000000000")).toPlainString()
                } else null
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "getBalance failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** List persisted wallets (address/chain; keys never leave storage). */
    fun listWallets(): List<Wallet> {
        val dir = walletsDir() ?: return emptyList()
        return dir.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                try {
                    val o = JSONObject(f.readText())
                    val chain = o.getString("chain")
                    Wallet(
                        address = o.getString("address"),
                        chain = chain,
                        balance = "0",
                        symbol = CHAIN_SYMBOL[chain] ?: "ETH",
                        usdValue = "0.00"
                    )
                } catch (_: Exception) { null }
            } ?: emptyList()
    }

    /** OmniBridge compatibility: load a persisted wallet by address (no key return). */
    suspend fun loadWallet(address: String): Wallet? {
        val dir = walletsDir() ?: return null
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return null
        for (f in files) {
            try {
                val o = JSONObject(f.readText())
                if (o.optString("address", "").equals(address, ignoreCase = true)) {
                    val chain = o.getString("chain")
                    return Wallet(
                        address = o.getString("address"),
                        chain = chain,
                        balance = getBalance(address, chain) ?: "0",
                        symbol = CHAIN_SYMBOL[chain] ?: "ETH",
                        usdValue = "0.00"
                    )
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun initialize() { /* pure crypto — nothing to warm; kept for OmniBridge/LuciferCore */ }

    fun shutdown() { /* pure crypto — nothing to tear down */ }

    // ───────────── internals ─────────────

    /** Uncompressed pubkey → keccak256(X||Y) → last 20 bytes → 0x address. */
    private fun deriveAddress(priv: BigInteger): String {
        val pub = CURVE.g.multiply(priv).normalize()
        val encoded = pub.getEncoded(false) // 0x04 || X(32) || Y(32)
        val keccak = KeccakDigest(256)
        keccak.update(encoded, 1, 64)
        val out = ByteArray(32)
        keccak.doFinal(out, 0)
        return "0x" + out.sliceArray(12..31).joinToString("") { "%02x".format(it) }
    }

    /** SEC1 point recovery: Q = r⁻¹·(s·R − e·G), R decompressed from x=r+i·n. */
    private fun recoverPublicKey(recId: Int, r: BigInteger, s: BigInteger, hash: ByteArray): ECPoint? {
        return try {
            val n = CURVE.n
            val i = BigInteger.valueOf((recId / 2).toLong())
            val x = r.add(i.multiply(n))
            if (x >= CURVE.curve.fieldSize.toBigInteger().let { BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16) }) {
                return null
            }
            val header = (0x02 + (recId and 1)).toByte()
            val rPoint = CURVE.curve.decodePoint(byteArrayOf(header) + to32(x)) ?: return null

            val e = BigInteger(1, hash).mod(n)
            val eInv = e.negate().mod(n)
            val rInv = r.modInverse(n)
            rPoint.multiply(s)
                .add(CURVE.g.multiply(eInv))
                .multiply(rInv)
                .normalize()
        } catch (_: Exception) {
            null
        }
    }

    private fun to32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        val out = ByteArray(32)
        if (b.size >= 32) {
            System.arraycopy(b, b.size - 32, out, 0, 32)
        } else {
            System.arraycopy(b, 0, out, 32 - b.size, b.size)
        }
        return out
    }

    /** Store wallet metadata separately from its Keystore-encrypted signing key. */
    private fun persist(chain: String, address: String, priv: BigInteger): Boolean {
        val dir = walletsDir() ?: return false
        val ctx = context ?: return false
        val fileName = "${chain}-${address.removePrefix("0x")}.json"
        return try {
            if (!SecretStore.write(ctx, "wallets/$fileName.key", priv.toString(16))) return false
            val rec = JSONObject().apply {
                put("address", address)
                put("chain", chain)
                put("createdAt", System.currentTimeMillis())
            }
            File(dir, fileName).writeText(rec.toString())
            true
        } catch (e: Exception) {
            SecretStore.delete(ctx, "wallets/$fileName.key")
            Log.e(TAG, "persist failed: ${e.message}")
            false
        }
    }
}

data class Wallet(
    val address: String,
    val chain: String,
    val balance: String,
    val symbol: String,
    val usdValue: String
)
