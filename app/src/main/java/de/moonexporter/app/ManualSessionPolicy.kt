package de.moonexporter.app

internal enum class ManualSessionDecision { CREATE_NEW, SUPERSEDE_AND_CREATE, BLOCK_OTHER_TARGET }

internal object ManualSessionPolicy {
    fun decide(pendingTarget: String?, selectedTarget: String): ManualSessionDecision = when {
        pendingTarget == null -> ManualSessionDecision.CREATE_NEW
        pendingTarget == selectedTarget -> ManualSessionDecision.SUPERSEDE_AND_CREATE
        else -> ManualSessionDecision.BLOCK_OTHER_TARGET
    }

    fun canAutoResume(sourceReady: List<Boolean>): Boolean = sourceReady.isNotEmpty() && sourceReady.all { it }
}
