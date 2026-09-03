package com.gcll.docagent.persistence.repository;

import com.gcll.docagent.human.PendingAction;

import java.util.List;
import java.util.Optional;

public interface PendingActionRepository {
    PendingAction save(PendingAction action);

    Optional<PendingAction> findById(String id);

    List<PendingAction> findPending();
}
