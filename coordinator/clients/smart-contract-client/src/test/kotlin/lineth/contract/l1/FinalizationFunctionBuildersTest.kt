package lineth.contract.l1

import linea.contract.l1.LineaValidiumContractVersion
import linea.contract.l1.LinethRollupContractVersion
import linea.domain.createBlobRecord
import linea.domain.createProofToFinalize
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.web3j.abi.FunctionEncoder

class FinalizationFunctionBuildersTest {
  private val blob = createBlobRecord(startBlockNumber = 1UL, endBlockNumber = 2UL)
  private val aggregation = createProofToFinalize(firstBlockNumber = 1L, finalBlockNumber = 2L)

  @Test
  fun `builds V6 finalization with a compression proof`() {
    assertThat(
      Web3JLinethRollupFunctionBuilders.buildFinalizeBlocksFunction(
        LinethRollupContractVersion.V6,
        aggregation,
        blob,
        ByteArray(32),
        0L,
      ),
    ).isNotNull()
  }

  @Test
  fun `builds V8 finalization with a compression proof`() {
    assertThat(
      FunctionBuildersV8.buildFinalizeBlocksFunctionV8(
        aggregation,
        blob,
        ByteArray(32),
        0L,
      ),
    ).isNotNull()
  }

  @Test
  fun `builds Validium finalization with a compression proof`() {
    assertThat(
      Web3JLineaValidiumFunctionBuilders.buildFinalizeBlocksFunction(
        LineaValidiumContractVersion.V1,
        aggregation,
        blob,
        ByteArray(32),
        0L,
      ),
    ).isNotNull()
    assertThat(
      Web3JLineaValidiumFunctionBuilders.buildFinalizeBlocksFunction(
        LineaValidiumContractVersion.V2,
        aggregation,
        blob,
        ByteArray(32),
        0L,
      ),
    ).isNotNull()
  }

  @Test
  fun `Validium V2 finalization parameters encode identically to rollup V8`() {
    // Validium V2's FinalizationDataV4 is the same tuple as rollup V8's, so the (positionally
    // encoded) parameter payload must match the battle-tested V8 builder byte for byte — this
    // pins the struct field ORDER, the one thing that can silently corrupt the calldata.
    val v2 = FunctionEncoder.encode(
      Web3JLineaValidiumFunctionBuilders.buildFinalizeBlockFunctionV2(
        aggregation,
        blob,
        ByteArray(32),
        0L,
      ),
    )
    val v8 = FunctionEncoder.encode(
      FunctionBuildersV8.buildFinalizeBlocksFunctionV8(
        aggregation,
        blob,
        ByteArray(32),
        0L,
      ),
    )
    // strip the 4-byte selectors ("0x" + 8 hex chars); the parameter encoding must be identical
    assertThat(v2.substring(10)).isEqualTo(v8.substring(10))
  }

  @Test
  fun `rejects finalization without a compression proof`() {
    val unprovenBlob = blob.copy(blobCompressionProof = null)

    listOf<() -> Unit>(
      {
        Web3JLinethRollupFunctionBuilders.buildFinalizeBlocksFunction(
          LinethRollupContractVersion.V6,
          aggregation,
          unprovenBlob,
          ByteArray(32),
          0L,
        )
      },
      {
        FunctionBuildersV8.buildFinalizeBlocksFunctionV8(
          aggregation,
          unprovenBlob,
          ByteArray(32),
          0L,
        )
      },
      {
        Web3JLineaValidiumFunctionBuilders.buildFinalizeBlocksFunction(
          LineaValidiumContractVersion.V1,
          aggregation,
          unprovenBlob,
          ByteArray(32),
          0L,
        )
      },
      {
        Web3JLineaValidiumFunctionBuilders.buildFinalizeBlocksFunction(
          LineaValidiumContractVersion.V2,
          aggregation,
          unprovenBlob,
          ByteArray(32),
          0L,
        )
      },
    ).forEach { buildFunction ->
      val exception = assertThrows<IllegalArgumentException> { buildFunction() }
      assertThat(exception)
        .hasMessage(
          "aggregationLastBlob.blobCompressionProof must be set when building the finalization function",
        )
    }
  }
}
