/*
 * Copyright (c) 2010-2014. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.eventstore.jpa;

import org.axonframework.common.Assert;
import org.axonframework.common.io.IOUtils;
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.eventsourcing.DomainEventMessage;
import org.axonframework.eventsourcing.DomainEventStream;
import org.axonframework.eventsourcing.GenericDomainEventMessage;
import org.axonframework.eventstore.EventStreamNotFoundException;
import org.axonframework.eventstore.EventVisitor;
import org.axonframework.eventstore.SnapshotEventStore;
import org.axonframework.eventstore.jpa.criteria.JpaCriteria;
import org.axonframework.eventstore.jpa.criteria.JpaCriteriaBuilder;
import org.axonframework.eventstore.jpa.criteria.ParameterRegistry;
import org.axonframework.eventstore.management.Criteria;
import org.axonframework.eventstore.management.CriteriaBuilder;
import org.axonframework.eventstore.management.EventStoreManagement;
import org.axonframework.commandhandling.model.ConcurrencyException;
import org.axonframework.serializer.MessageSerializer;
import org.axonframework.serializer.SerializedDomainEventData;
import org.axonframework.serializer.SerializedObject;
import org.axonframework.serializer.Serializer;
import org.axonframework.serializer.xml.XStreamSerializer;
import org.axonframework.upcasting.SimpleUpcasterChain;
import org.axonframework.upcasting.UpcasterAware;
import org.axonframework.upcasting.UpcasterChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.EntityManager;
import javax.sql.DataSource;
import java.io.Closeable;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.axonframework.upcasting.UpcastUtils.upcastAndDeserialize;

/**
 * An EventStore implementation that uses JPA to store DomainEvents in a database. The actual DomainEvent is stored as
 * a
 * serialized blob of bytes. Other columns are used to store meta-data that allow quick finding of DomainEvents for a
 * specific aggregate in the correct order.
 * <p/>
 * This EventStore supports snapshots pruning, which can enabled by configuring a {@link #setMaxSnapshotsArchived(int)
 * maximum number of snapshots to archive}. By default snapshot pruning is configured to archive only {@value
 * #DEFAULT_MAX_SNAPSHOTS_ARCHIVED} snapshot per aggregate.
 * <p/>
 * The serializer used to serialize the events is configurable. By default, the {@link XStreamSerializer} is used.
 *
 * @author Allard Buijze
 * @since 0.5
 */
public class JpaEventStore implements SnapshotEventStore, EventStoreManagement, UpcasterAware {

    private static final Logger logger = LoggerFactory.getLogger(JpaEventStore.class);

    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int DEFAULT_MAX_SNAPSHOTS_ARCHIVED = 1;

    private final MessageSerializer serializer;
    private final EventEntryStore<?> eventEntryStore;
    private final JpaCriteriaBuilder criteriaBuilder = new JpaCriteriaBuilder();
    private final EntityManagerProvider entityManagerProvider;

    private int batchSize = DEFAULT_BATCH_SIZE;
    private UpcasterChain upcasterChain = SimpleUpcasterChain.EMPTY;
    private int maxSnapshotsArchived = DEFAULT_MAX_SNAPSHOTS_ARCHIVED;
    private PersistenceExceptionResolver persistenceExceptionResolver;

    /**
     * Initialize a JpaEventStore using an {@link org.axonframework.serializer.xml.XStreamSerializer}, which
     * serializes events as XML and the default Event Entry store.
     * <p/>
     * The JPA Persistence context is required to contain two entities: {@link DomainEventEntry} and {@link
     * SnapshotEventEntry}.
     *
     * @param entityManagerProvider The EntityManagerProvider providing the EntityManager instance for this EventStore
     */
    public JpaEventStore(EntityManagerProvider entityManagerProvider) {
        this(entityManagerProvider, new XStreamSerializer(), new DefaultEventEntryStore());
    }

    /**
     * Initialize a JpaEventStore using the given <code>eventEntryStore</code> and an {@link
     * org.axonframework.serializer.xml.XStreamSerializer}, which serializes events as XML.
     *
     * @param entityManagerProvider The EntityManagerProvider providing the EntityManager instance for this EventStore
     * @param eventEntryStore       The instance providing persistence logic for Domain Event entries
     */
    public JpaEventStore(EntityManagerProvider entityManagerProvider, EventEntryStore eventEntryStore) {
        this(entityManagerProvider, new XStreamSerializer(), eventEntryStore);
    }

    /**
     * Initialize a JpaEventStore which serializes events using the given <code>eventSerializer</code> and stores the
     * events in the database using the default EventEntryStore.
     * <p/>
     * <p/>
     * <em>Note: the SerializedType of Message Meta Data is not stored in the DefaultEventEntryStore. Upon retrieval,
     * it is set to the default value (name = "org.axonframework.messaging.metadata.MetaData", revision = null). See {@link
     * org.axonframework.serializer.SerializedMetaData#isSerializedMetaData(org.axonframework.serializer.SerializedObject)}</em>
     *
     * @param entityManagerProvider The EntityManagerProvider providing the EntityManager instance for this EventStore
     * @param serializer            The serializer to (de)serialize domain events with.
     */
    public JpaEventStore(EntityManagerProvider entityManagerProvider, Serializer serializer) {
        this(entityManagerProvider, serializer, new DefaultEventEntryStore());
    }

    /**
     * Initialize a JpaEventStore which serializes events using the given <code>eventSerializer</code> and stores the
     * events in the database using the given <code>eventEntryStore</code>.
     *
     * @param entityManagerProvider The EntityManagerProvider providing the EntityManager instance for this EventStore
     * @param serializer            The serializer to (de)serialize domain events with.
     * @param eventEntryStore       The instance providing persistence logic for Domain Event entries
     */
    public JpaEventStore(EntityManagerProvider entityManagerProvider, Serializer serializer,
                         EventEntryStore eventEntryStore) {
        Assert.notNull(entityManagerProvider, "entityManagerProvider may not be null");
        Assert.notNull(serializer, "serializer may not be null");
        Assert.notNull(eventEntryStore, "eventEntryStore may not be null");
        this.entityManagerProvider = entityManagerProvider;
        this.serializer = new MessageSerializer(serializer);
        this.eventEntryStore = eventEntryStore;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void appendEvents(List<DomainEventMessage<?>> events) {
        try {
            EntityManager entityManager = entityManagerProvider.getEntityManager();
            for (DomainEventMessage<?> event : events) {
                SerializedObject serializedPayload = serializer.serializePayload(event, eventEntryStore.getDataType());
                SerializedObject serializedMetaData = serializer.serializeMetaData(event, eventEntryStore.getDataType());
                eventEntryStore.persistEvent(event, serializedPayload, serializedMetaData, entityManager);
            }
            entityManager.flush();
        } catch (RuntimeException exception) {
            if (!events.isEmpty()
                    && persistenceExceptionResolver != null
                    && persistenceExceptionResolver.isDuplicateKeyViolation(exception)) {
                throw new ConcurrencyException(
                        String.format("Concurrent modification detected for Aggregate identifier [%s], sequence: [%s]",
                                      events.get(0).getAggregateIdentifier(),
                                      events.get(0).getSequenceNumber()),
                        exception);
            }
            throw exception;
        }
    }

    @SuppressWarnings({"unchecked"})
    @Override
    public DomainEventStream readEvents(String identifier) {
        long snapshotSequenceNumber = -1;
        EntityManager entityManager = entityManagerProvider.getEntityManager();
        SerializedDomainEventData lastSnapshotEvent = eventEntryStore.loadLastSnapshotEvent(identifier,
                                                                                            entityManager);
        DomainEventMessage snapshotEvent = null;
        if (lastSnapshotEvent != null) {
            try {
                snapshotEvent = new GenericDomainEventMessage<>(
                        identifier,
                        lastSnapshotEvent.getSequenceNumber(),
                        serializer.deserialize(lastSnapshotEvent.getPayload()),
                        (Map<String, Object>) serializer.deserialize(lastSnapshotEvent.getMetaData()));
                snapshotSequenceNumber = snapshotEvent.getSequenceNumber();
            } catch (RuntimeException | LinkageError ex) {
                logger.warn("Error while reading snapshot event entry. "
                                    + "Reconstructing aggregate on entire event stream. Caused by: {} {}",
                            ex.getClass().getName(),
                            ex.getMessage());
            }
        }

        Iterator<? extends SerializedDomainEventData> entries =
                eventEntryStore.fetchAggregateStream(identifier, snapshotSequenceNumber + 1,
                                                     batchSize, entityManager);
        if (snapshotEvent == null && !entries.hasNext()) {
            throw new EventStreamNotFoundException(identifier);
        }
        return new CursorBackedDomainEventStream(snapshotEvent, entries, false);
    }

    @Override
    public DomainEventStream readEvents(String identifier, long firstSequenceNumber,
                                        long lastSequenceNumber) {
        EntityManager entityManager = entityManagerProvider.getEntityManager();
        int minimalBatchSize = (int) Math.min(batchSize, (lastSequenceNumber - firstSequenceNumber) + 2);
        Iterator<? extends SerializedDomainEventData> entries = eventEntryStore.fetchAggregateStream(identifier,
                                                                                                     firstSequenceNumber,
                                                                                                     minimalBatchSize,
                                                                                                     entityManager);
        if (!entries.hasNext()) {
            throw new EventStreamNotFoundException(identifier);
        }
        return new CursorBackedDomainEventStream(null, entries, lastSequenceNumber, false);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void appendSnapshotEvent(DomainEventMessage snapshotEvent) {
        EntityManager entityManager = entityManagerProvider.getEntityManager();
        // Persist snapshot before pruning redundant archived ones, in order to prevent snapshot misses when reloading
        // an aggregate, which may occur when a READ_UNCOMMITTED transaction isolation level is used.
        final Class<?> dataType = eventEntryStore.getDataType();
        SerializedObject serializedPayload = serializer.serializePayload(snapshotEvent, dataType);
        SerializedObject serializedMetaData = serializer.serializeMetaData(snapshotEvent, dataType);
        try {
            eventEntryStore.persistSnapshot(snapshotEvent, serializedPayload, serializedMetaData, entityManager);
            if (maxSnapshotsArchived > 0) {
                eventEntryStore.pruneSnapshots(snapshotEvent, maxSnapshotsArchived,
                                               entityManagerProvider.getEntityManager());
            }

            entityManager.flush();
        } catch (RuntimeException exception) {
            if (snapshotEvent != null
                    && persistenceExceptionResolver != null
                    && persistenceExceptionResolver.isDuplicateKeyViolation(exception)) {
                throw new ConcurrencyException(
                        String.format("A snapshot for aggregate [%s] at sequence: [%s] was already inserted",
                                      snapshotEvent.getAggregateIdentifier(),
                                      snapshotEvent.getSequenceNumber()),
                        exception);
            }
            throw exception;
        }
    }

    @Override
    public void visitEvents(EventVisitor visitor) {
        doVisitEvents(visitor, null, Collections.<String, Object>emptyMap());
    }

    @Override
    public void visitEvents(Criteria criteria, EventVisitor visitor) {
        StringBuilder sb = new StringBuilder();
        ParameterRegistry parameters = new ParameterRegistry();
        ((JpaCriteria) criteria).parse("e", sb, parameters);
        doVisitEvents(visitor, sb.toString(), parameters.getParameters());
    }

    @Override
    public CriteriaBuilder newCriteriaBuilder() {
        return criteriaBuilder;
    }

    private void doVisitEvents(EventVisitor visitor, String whereClause, Map<String, Object> parameters) {
        EntityManager entityManager = entityManagerProvider.getEntityManager();
        Iterator<? extends SerializedDomainEventData> batch = eventEntryStore.fetchFiltered(whereClause,
                                                                                            parameters,
                                                                                            batchSize,
                                                                                            entityManager);
        DomainEventStream eventStream = new CursorBackedDomainEventStream(null, batch, true);
        while (eventStream.hasNext()) {
            visitor.doWithEvent(eventStream.next());
        }
    }

    /**
     * Registers the data source that allows the EventStore to detect the database type and define the error codes that
     * represent concurrent access failures.
     * <p/>
     * Should not be used in combination with {@link #setPersistenceExceptionResolver(PersistenceExceptionResolver)},
     * but rather as a shorthand alternative for most common database types.
     *
     * @param dataSource A data source providing access to the backing database
     * @throws SQLException If an error occurs while accessing the dataSource
     */
    public void setDataSource(DataSource dataSource) throws SQLException {
        if (persistenceExceptionResolver == null) {
            persistenceExceptionResolver = new SQLErrorCodesResolver(dataSource);
        }
    }

    /**
     * Sets the persistenceExceptionResolver that will help detect concurrency exceptions from the backing database.
     *
     * @param persistenceExceptionResolver the persistenceExceptionResolver that will help detect concurrency
     *                                     exceptions
     */
    public void setPersistenceExceptionResolver(PersistenceExceptionResolver persistenceExceptionResolver) {
        this.persistenceExceptionResolver = persistenceExceptionResolver;
    }

    /**
     * Sets the number of events that should be read at each database access. When more than this number of events must
     * be read to rebuild an aggregate's state, the events are read in batches of this size. Defaults to 100.
     * <p/>
     * Tip: if you use a snapshotter, make sure to choose snapshot trigger and batch size such that a single batch will
     * generally retrieve all events required to rebuild an aggregate's state.
     *
     * @param batchSize the number of events to read on each database access. Default to 100.
     */
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    @Override
    public void setUpcasterChain(UpcasterChain upcasterChain) {
        this.upcasterChain = upcasterChain;
    }

    /**
     * Sets the maximum number of snapshots to archive for an aggregate. The EventStore will keep at most this number
     * of
     * snapshots per aggregate.
     * <p/>
     * Defaults to {@value #DEFAULT_MAX_SNAPSHOTS_ARCHIVED}.
     *
     * @param maxSnapshotsArchived The maximum number of snapshots to archive for an aggregate. A value less than 1
     *                             disables pruning of snapshots.
     */
    public void setMaxSnapshotsArchived(int maxSnapshotsArchived) {
        this.maxSnapshotsArchived = maxSnapshotsArchived;
    }

    private final class CursorBackedDomainEventStream implements DomainEventStream, Closeable {

        private final Iterator<? extends SerializedDomainEventData> cursor;
        private final long lastSequenceNumber;
        private final boolean skipUnknownTypes;
        private Iterator<DomainEventMessage> currentBatch;
        private DomainEventMessage next;

        public CursorBackedDomainEventStream(DomainEventMessage snapshotEvent,
                                             Iterator<? extends SerializedDomainEventData> cursor,
                                             boolean skipUnknownTypes) {
            this(snapshotEvent, cursor, Long.MAX_VALUE, skipUnknownTypes);
        }

        public CursorBackedDomainEventStream(DomainEventMessage snapshotEvent,
                                             Iterator<? extends SerializedDomainEventData> cursor,
                                             long lastSequenceNumber,
                                             boolean skipUnknownTypes) {
            this.lastSequenceNumber = lastSequenceNumber;
            this.skipUnknownTypes = skipUnknownTypes;
            if (snapshotEvent != null) {
                currentBatch = Collections.singletonList(snapshotEvent).iterator();
            } else {
                currentBatch = Collections.<DomainEventMessage>emptyList().iterator();
            }
            this.cursor = cursor;
            initializeNextItem();
        }

        @Override
        public boolean hasNext() {
            return next != null && next.getSequenceNumber() <= lastSequenceNumber;
        }

        @Override
        public DomainEventMessage next() {
            DomainEventMessage current = next;
            initializeNextItem();
            return current;
        }

        private void initializeNextItem() {
            while (!currentBatch.hasNext() && cursor.hasNext()) {
                final SerializedDomainEventData entry = cursor.next();
                currentBatch = upcastAndDeserialize(entry, serializer, upcasterChain,
                                                    skipUnknownTypes)
                        .iterator();
            }
            next = currentBatch.hasNext() ? currentBatch.next() : null;
        }


        @Override
        public DomainEventMessage peek() {
            return next;
        }

        @Override
        public void close() throws IOException {
            IOUtils.closeIfCloseable(currentBatch);
        }
    }
}
