package dev.rovsti.core.contracts

import kotlinx.serialization.KSerializer

/** One domain's share of the contract: fixture stem (`format.render.request`) to the serializer of its model. */
interface ContractModels {
    val models: Map<String, KSerializer<*>>
}

/** Every domain's models. A new domain's object is added here, next to its `*Ops` facade. */
object AllContracts {
    val domains: List<ContractModels> =
        listOf(
            CoreContracts,
            HeadlessContracts,
            SharedContracts,
            SearchContracts,
            PhoneContracts,
            ContactsContracts,
            QrContracts,
        )

    val models: Map<String, KSerializer<*>> =
        buildMap {
            for (domain in domains) {
                for ((stem, serializer) in domain.models) {
                    check(put(stem, serializer) == null) { "two domains claim fixture $stem" }
                }
            }
        }
}
