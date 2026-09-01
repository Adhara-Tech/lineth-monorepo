package linea.contract.l1

import linea.contract.FAKE_READ_ONLY_CREDENTIALS
import linea.contract.ValidiumV1
import linea.domain.BlockParameter
import linea.kotlin.toBigInteger
import linea.kotlin.toULong
import linea.web3j.domain.toWeb3j
import net.consensys.linea.async.toSafeFuture
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.web3j.protocol.Web3j
import org.web3j.tx.gas.StaticGasProvider
import tech.pegasys.teku.infrastructure.async.SafeFuture
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

open class Web3JLineaValidiumSmartContractClientReadOnly(
  val web3j: Web3j,
  val contractAddress: String,
  private val versionRefreshInterval: Duration = 6.seconds,
  private val clock: Clock = Clock.System,
  private val log: Logger = LogManager.getLogger(Web3JLineaValidiumSmartContractClientReadOnly::class.java),
) : LineaValidiumSmartContractClientReadOnly, FinalizedStateDataClientReadOnly {
  protected fun contractClientAtBlock(blockParameter: BlockParameter): ValidiumV1 {
    return ValidiumV1.load(
      contractAddress,
      web3j,
      FAKE_READ_ONLY_CREDENTIALS,
      StaticGasProvider(BigInteger.ZERO, BigInteger.ZERO),
    ).apply {
      this.setDefaultBlockParameter(blockParameter.toWeb3j())
    }
  }

  override fun getAddress(): String = contractAddress

  // NOTE: this version cache is intentionally a copy of Web3JLinethRollupSmartContractClientReadOnly's,
  // kept as-is to keep this PR narrow. Both version enums are now Comparable with a `latest`, so the two
  // should be extracted into one shared generic, and the get-then-set below made atomic (updateAndGet),
  // as a follow-up, in both clients together rather than diverging here.
  private data class CachedVersion(
    val version: LineaValidiumContractVersion,
    val fetchedAt: Instant,
  )

  private val smartContractVersionCache = AtomicReference<CachedVersion>(null)

  private fun getSmartContractVersion(): SafeFuture<LineaValidiumContractVersion> {
    val cached = smartContractVersionCache.get()
    return when {
      // once upgraded, it's not downgraded
      cached?.version == LineaValidiumContractVersion.latest ->
        SafeFuture.completedFuture(LineaValidiumContractVersion.latest)

      // below latest: serve from cache within the refresh interval so repeated getVersion calls
      // don't refetch on every call, while still detecting an upgrade within versionRefreshInterval
      cached != null && clock.now() < cached.fetchedAt + versionRefreshInterval ->
        SafeFuture.completedFuture(cached.version)

      else ->
        fetchSmartContractVersion()
          .thenPeek { fetchedVersion ->
            val current = smartContractVersionCache.get()
            // prevent inflight request to override and rollback
            if (current == null || fetchedVersion >= current.version) {
              smartContractVersionCache.set(CachedVersion(fetchedVersion, clock.now()))

              if (current != null && fetchedVersion != current.version) {
                log.info(
                  "Validium smart contract upgraded: prevVersion={} upgradedVersion={}",
                  current.version,
                  fetchedVersion,
                )
              }
            }
          }
    }
  }

  // CONTRACT_VERSION() exists on both V1 and V2, so the V1 wrapper can read it on either contract.
  internal open fun fetchSmartContractVersion(
    blockParameter: BlockParameter = BlockParameter.Tag.LATEST,
  ): SafeFuture<LineaValidiumContractVersion> {
    return contractClientAtBlock(blockParameter)
      .CONTRACT_VERSION().sendAsync()
      .toSafeFuture()
      .thenApply(::parseContractVersion)
  }

  internal fun parseContractVersion(version: String): LineaValidiumContractVersion =
    // Match on the major component so "10.0" is not misread as V1 by a bare "1" prefix.
    when (version.substringBefore('.')) {
      "1" -> LineaValidiumContractVersion.V1
      "2" -> LineaValidiumContractVersion.V2
      else -> throw IllegalStateException("Unsupported Validium contract version: $version")
    }

  override fun getVersion(blockParameter: BlockParameter): SafeFuture<LineaValidiumContractVersion> {
    return if (blockParameter == BlockParameter.Tag.LATEST) {
      getSmartContractVersion()
    } else {
      fetchSmartContractVersion(blockParameter)
    }
  }

  override fun finalizedL2BlockNumber(blockParameter: BlockParameter): SafeFuture<ULong> {
    return contractClientAtBlock(blockParameter)
      .currentL2BlockNumber().sendAsync()
      .thenApply { it.toULong() }
      .toSafeFuture()
  }

  override fun getFinalizedStateData(
    blockParameter: BlockParameter,
  ): SafeFuture<FinalizedStateDataProvider.FinalizedStateData> {
    // Read the version even though both branches return zero today: the exhaustive `when` forces a
    // conscious decision when V3 lands, and this is where the "coordinator doesn't support forced
    // transactions on validium yet" limitation lives. Don't simplify this to just returning zero
    // without the version check.
    // Note: the version is read at LATEST while the finalized state uses blockParameter (mirrors the
    // rollup client); harmless because the finalization monitor only ever queries LATEST.
    return getVersion()
      .thenCombine(finalizedL2BlockNumber(blockParameter)) { version, finalizedBlockNumber ->
        when (version) {
          // V1 contracts have no forced transactions, so the number is always the initial value (0),
          // mirroring the rollup client's V6/V7 behaviour.
          LineaValidiumContractVersion.V1 ->
            FinalizedStateDataProvider.FinalizedStateData(
              blockNumber = finalizedBlockNumber,
              forcedTransactionNumber = 0UL,
            )

          // V2 contracts DO track forced transactions on-chain, but the coordinator does not support
          // forced transactions on validium chains yet (they are disabled at app wiring). Once that
          // support lands, this branch must read the FinalizedStateUpdated event like the rollup
          // client's V8+ path instead of reporting the initial value.
          LineaValidiumContractVersion.V2 ->
            FinalizedStateDataProvider.FinalizedStateData(
              blockNumber = finalizedBlockNumber,
              forcedTransactionNumber = 0UL,
            )
        }
      }
  }

  override fun getMessageRollingHash(blockParameter: BlockParameter, messageNumber: Long): SafeFuture<ByteArray> {
    require(messageNumber >= 0) { "messageNumber must be greater than or equal to 0" }
    return contractClientAtBlock(blockParameter).rollingHashes(messageNumber.toBigInteger()).sendAsync().toSafeFuture()
  }

  override fun isBlobShnarfPresent(blockParameter: BlockParameter, shnarf: ByteArray): SafeFuture<Boolean> {
    return contractClientAtBlock(blockParameter)
      .blobShnarfExists(shnarf).sendAsync()
      .thenApply { it != BigInteger.ZERO }
      .toSafeFuture()
  }

  override fun blockStateRootHash(blockParameter: BlockParameter, lineaL2BlockNumber: ULong): SafeFuture<ByteArray> {
    return contractClientAtBlock(blockParameter)
      .stateRootHashes(lineaL2BlockNumber.toBigInteger()).sendAsync()
      .toSafeFuture()
  }
}
