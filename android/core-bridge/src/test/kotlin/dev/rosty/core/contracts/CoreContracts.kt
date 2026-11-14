package dev.rosty.core.contracts

import dev.rosty.core.RustErrorKinds
import dev.rosty.core.model.core.DataDirInfo
import dev.rosty.core.model.core.EchoProbe
import dev.rosty.core.model.core.ErrEnvelope
import dev.rosty.core.model.core.FailRequest
import dev.rosty.core.model.core.LogsDrainAnswer
import dev.rosty.core.model.core.LogsDrainRequest
import dev.rosty.core.model.core.OkEnvelope
import dev.rosty.core.model.core.VersionInfo
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull

/** Fixture stem to model for the `core.*` ops. */
object CoreContracts : ContractModels {
    override val models: Map<String, KSerializer<*>> =
        buildMap {
            put("core.version.request", JsonNull.serializer())
            put("core.version.answer", OkEnvelope.serializer(VersionInfo.serializer()))
            put("core.data_dir.request", JsonNull.serializer())
            put("core.data_dir.answer", OkEnvelope.serializer(DataDirInfo.serializer()))
            put("core.echo.request", EchoProbe.serializer())
            put("core.echo.answer", OkEnvelope.serializer(EchoProbe.serializer()))
            put("core.logs_drain.request", LogsDrainRequest.serializer())
            put("core.logs_drain.answer", OkEnvelope.serializer(LogsDrainAnswer.serializer()))
            for (kind in RustErrorKinds.wire) {
                put("core.fail.$kind.request", FailRequest.serializer())
                put("core.fail.$kind.answer", ErrEnvelope.serializer())
            }
        }
}
