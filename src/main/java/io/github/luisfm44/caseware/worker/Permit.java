package io.github.luisfm44.caseware.worker;

/** One reserved downstream slot, held until dispatch is rejected or execution ends. */
public interface Permit extends AutoCloseable {

    OperationId operationId();

    /** Releases this reservation once. Safe to call from the downstream completion thread. */
    @Override
    void close();
}
