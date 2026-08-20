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
    // Every V4-specific field gets a DISTINCT non-zero value so a swapped or substituted argument
    // (e.g. the two ftx numbers, or the parent vs final ftx rolling hash) changes the encoding.
    val distinctlyPopulatedAggregation = createProofToFinalize(
      firstBlockNumber = 1L,
      finalBlockNumber = 2L,
      parentAggregationFtxNumber = 7UL,
      finalFtxNumber = 9UL,
      parentAggregationFtxRollingHash = ByteArray(32) { 0x0a },
      finalFtxRollingHash = ByteArray(32) { 0x0b },
      filteredAddresses = listOf(ByteArray(20) { 0x0c }, ByteArray(20) { 0x0d }),
    )
    val parentL1RollingHash = ByteArray(32) { 0x0e }
    val parentL1RollingHashMessageNumber = 3L

    val v2 = FunctionEncoder.encode(
      Web3JLineaValidiumFunctionBuilders.buildFinalizeBlockFunctionV2(
        distinctlyPopulatedAggregation,
        blob,
        parentL1RollingHash,
        parentL1RollingHashMessageNumber,
      ),
    )
    val v8 = FunctionEncoder.encode(
      FunctionBuildersV8.buildFinalizeBlocksFunctionV8(
        distinctlyPopulatedAggregation,
        blob,
        parentL1RollingHash,
        parentL1RollingHashMessageNumber,
      ),
    )
    // strip the 4-byte selectors ("0x" + 8 hex chars); the parameter encoding must be identical
    assertThat(v2.substring(10)).isEqualTo(v8.substring(10))
  }

  @Test
  fun `Validium V2 acceptShnarfData reuses the V1 encoding byte for byte`() {
    val v1 = FunctionEncoder.encode(
      Web3JLineaValidiumFunctionBuilders.buildAcceptShnarfDataFunction(
        LineaValidiumContractVersion.V1,
        listOf(blob),
      ),
    )
    val v2 = FunctionEncoder.encode(
      Web3JLineaValidiumFunctionBuilders.buildAcceptShnarfDataFunction(
        LineaValidiumContractVersion.V2,
        listOf(blob),
      ),
    )
    assertThat(v2).isEqualTo(v1)
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
