package gg.grounds.api.permissions

import gg.grounds.domain.ApplyOutcome
import gg.grounds.grpc.permissions.ApplyResult

object ApplyResultMapper {
    fun toProto(outcome: ApplyOutcome): ApplyResult {
        return when (outcome) {
            ApplyOutcome.NO_CHANGE -> ApplyResult.NO_CHANGE
            ApplyOutcome.CREATED -> ApplyResult.CREATED
            ApplyOutcome.UPDATED -> ApplyResult.UPDATED
            ApplyOutcome.DELETED -> ApplyResult.DELETED
            ApplyOutcome.ERROR -> ApplyResult.APPLY_RESULT_UNSPECIFIED
        }
    }
}
