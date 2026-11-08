package dev.rovsti.core.contracts

import dev.rovsti.core.RustErrorKinds
import dev.rovsti.core.model.core.DataDirInfo
import dev.rovsti.core.model.core.EchoProbe
import dev.rovsti.core.model.core.ErrEnvelope
import dev.rovsti.core.model.core.FailRequest
import dev.rovsti.core.model.core.OkEnvelope
import dev.rovsti.core.model.core.VersionInfo
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
            for (kind in RustErrorKinds.wire) {
                put("core.fail.$kind.request", FailRequest.serializer())
                put("core.fail.$kind.answer", ErrEnvelope.serializer())
            }
        }
}
