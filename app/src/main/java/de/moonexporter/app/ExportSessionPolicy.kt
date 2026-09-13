package de.moonexporter.app

internal enum class ExplicitSessionAction {
    CREATE_NEW,
    SUPERSEDE_AND_CREATE,
    BLOCK_OTHER_TARGET,
}

internal fun explicitSessionAction(pendingTarget: String?, requestedTarget: String): ExplicitSessionAction = when {
    pendingTarget == null -> ExplicitSessionAction.CREATE_NEW
    pendingTarget == requestedTarget -> ExplicitSessionAction.SUPERSEDE_AND_CREATE
    else -> ExplicitSessionAction.BLOCK_OTHER_TARGET
}
