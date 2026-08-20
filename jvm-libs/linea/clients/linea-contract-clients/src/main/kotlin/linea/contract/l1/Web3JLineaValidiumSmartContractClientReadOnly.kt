package linea.contract.l1

import linea.contract.FAKE_READ_ONLY_CREDENTIALS
import linea.contract.ValidiumV1
import linea.domain.BlockParameter
import linea.kotlin.toBigInteger
import linea.kotlin.toULong
import linea.web3j.domain.toWeb3j
import net.consensys.linea.async.toSafeFuture
import org.web3j.protocol.Web3j
import org.web3j.tx.gas.StaticGasProvider
import tech.pegasys.teku.infrastructure.async.SafeFuture
import java.math.BigInteger

open class Web3JLineaValidiumSmartContractClientReadOnly(
  val web3j: Web3j,
  val contractAddress: String,
) : LineaValidiumSmartContractClientReadOnly, FinalizedStateDataProvider {
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

  // CONTRACT_VERSION() exists on both V1 and V2, so the V1 wrapper can read it on either contract.
  override fun getVersion(blockParameter: BlockParameter): SafeFuture<LineaValidiumContractVersion> =
    contractClientAtBlock(blockParameter)
      .CONTRACT_VERSION().sendAsync()
      .toSafeFuture()
      .thenApply(::parseContractVersion)

  private fun parseContractVersion(version: String): LineaValidiumContractVersion = when {
    version.startsWith("1") -> LineaValidiumContractVersion.V1
    version.startsWith("2") -> LineaValidiumContractVersion.V2
    else -> throw IllegalStateException("Unsupported Validium contract version: $version")
  }

  override fun finalizedL2BlockNumber(blockParameter: BlockParameter): SafeFuture<ULong> {
    return contractClientAtBlock(blockParameter)
      .currentL2BlockNumber().sendAsync()
      .thenApply { it.toULong() }
      .toSafeFuture()
  }

  // Validium mirrors the rollup V6/V7 behaviour: forced transactions are a rollup-only feature, so the
  // forced-transaction number is always the initial value (0). The finalization monitor only needs the
  // finalized block number to advance.
  override fun getFinalizedStateData(
    blockParameter: BlockParameter,
  ): SafeFuture<FinalizedStateDataProvider.FinalizedStateData> {
    return finalizedL2BlockNumber(blockParameter)
      .thenApply { finalizedBlockNumber ->
        FinalizedStateDataProvider.FinalizedStateData(
          blockNumber = finalizedBlockNumber,
          forcedTransactionNumber = 0UL,
        )
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
