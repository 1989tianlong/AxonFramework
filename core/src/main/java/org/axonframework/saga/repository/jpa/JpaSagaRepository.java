/*
 * Copyright (c) 2010. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.saga.repository.jpa;

import org.axonframework.saga.AssociationValue;
import org.axonframework.saga.ResourceInjector;
import org.axonframework.saga.Saga;
import org.axonframework.saga.repository.AbstractSagaRepository;

import java.util.List;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

/**
 * JPA implementation of the Saga Repository. It uses an {@link EntityManager} to persist the actual saga in a backing
 * store.
 * <p/>
 * After each operations that modified the backing store, {@link javax.persistence.EntityManager#flush()} is invoked to
 * ensure the store contains the last modifications. To override this behavior, see {@link }
 *
 * @author Allard Buijze
 * @since 0.7
 */
public class JpaSagaRepository extends AbstractSagaRepository {

    private EntityManager entityManager;
    private ResourceInjector injector;
    private volatile boolean useExplicitFlush = true;

    @SuppressWarnings({"unchecked"})
    @Override
    protected void removeAssociationValue(AssociationValue associationValue, String sagaIdentifier) {
        List<AssociationValueEntry> potentialCandidates = entityManager.createQuery(
                "SELECT ae FROM AssociationValueEntry ae WHERE ae.associationKey = :associationKey AND ae.sagaId = :sagaId")
                .setParameter("associationKey", associationValue.getKey())
                .setParameter("sagaId", sagaIdentifier)
                .getResultList();
        for (AssociationValueEntry entry : potentialCandidates) {
            if (associationValue.getValue().equals(entry.getAssociationValue().getValue())) {
                entityManager.remove(entry);
            }
        }
        if (useExplicitFlush) {
            entityManager.flush();
        }
    }

    @Override
    protected void storeAssociationValue(AssociationValue associationValue, String sagaIdentifier) {
        entityManager.persist(new AssociationValueEntry(sagaIdentifier, associationValue));
        if (useExplicitFlush) {
            entityManager.flush();
        }
    }

    @Override
    protected Saga loadSaga(String sagaId) {
        SagaEntry entry = entityManager.find(SagaEntry.class, sagaId);
        if (entry == null) {
            return null;
        }
        Saga storedSaga = entry.getSaga();
        injector.injectResources(storedSaga);
        return storedSaga;
    }

    @Override
    protected void updateSaga(Saga saga) {
        entityManager.merge(SagaEntry.forSaga(saga));
        if (useExplicitFlush) {
            entityManager.flush();
        }
    }

    @Override
    protected void storeSaga(Saga saga) {
        entityManager.persist(SagaEntry.forSaga(saga));
        if (useExplicitFlush) {
            entityManager.flush();
        }
    }

    @SuppressWarnings({"unchecked"})
    @PostConstruct
    public void initialize() {
        List<AssociationValueEntry> entries =
                entityManager.createQuery("SELECT ae FROM AssociationValueEntry ae").getResultList();
        for (AssociationValueEntry entry : entries) {
            AssociationValue associationValue = entry.getAssociationValue();
            getAssociationValueMap().add(associationValue, entry.getSagaIdentifier());
        }
    }

    @Resource
    public void setSagaResourceInjector(ResourceInjector injector) {
        this.injector = injector;
    }

    @PersistenceContext
    public void setEntityManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Sets whether or not to do an explicit {@link javax.persistence.EntityManager#flush()} after each data modifying
     * operation on the backing storage. Default to <code>true</code>
     *
     * @param useExplicitFlush <code>true</code> to force flush, <code>false</code> otherwise.
     */
    public void setUseExplicitFlush(boolean useExplicitFlush) {
        this.useExplicitFlush = useExplicitFlush;
    }
}