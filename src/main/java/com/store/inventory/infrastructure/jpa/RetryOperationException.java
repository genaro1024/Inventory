package com.store.inventory.infrastructure.jpa;

final class RetryOperationException extends RuntimeException {

    RetryOperationException(RuntimeException cause) {
        super("Product operation must be retried after a storage conflict", cause);
    }
}
