package com.rlc.fixer;

/**
 * Thrown when a fix cannot be materialized without changing program behavior, for
 * example because the leaked allocation cannot be located or moved safely. RLPatcher
 * then reports the fix as not materialized instead of emitting a patch.
 */
class UnsafeEditException extends RuntimeException {
    UnsafeEditException(String message) {
        super(message);
    }
}
