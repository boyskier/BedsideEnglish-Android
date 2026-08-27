# Project-specific R8 rules.
#
# AndroidX Room, WorkManager, kotlinx.serialization and OkHttp publish the consumer rules they
# require. Keep this file intentionally minimal: broad keep rules would retain dead code and mask
# release-only reflection problems instead of making them visible during verification.
