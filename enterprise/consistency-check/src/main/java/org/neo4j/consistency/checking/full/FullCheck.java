/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.consistency.checking.full;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

import org.neo4j.consistency.ConsistencyCheckSettings;
import org.neo4j.consistency.checking.CheckDecorator;
import org.neo4j.consistency.checking.SchemaRecordCheck;
import org.neo4j.consistency.checking.index.IndexEntryProcessor;
import org.neo4j.consistency.checking.index.IndexIterator;
import org.neo4j.consistency.checking.labelscan.LabelScanCheck;
import org.neo4j.consistency.checking.labelscan.LabelScanDocumentProcessor;
import org.neo4j.consistency.report.ConsistencyReporter;
import org.neo4j.consistency.report.ConsistencySummaryStatistics;
import org.neo4j.consistency.report.InconsistencyMessageLogger;
import org.neo4j.consistency.report.InconsistencyReport;
import org.neo4j.consistency.store.CacheSmallStoresRecordAccess;
import org.neo4j.consistency.store.DiffRecordAccess;
import org.neo4j.consistency.store.DirectRecordAccess;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.helpers.progress.ProgressMonitorFactory;
import org.neo4j.kernel.api.direct.DirectStoreAccess;
import org.neo4j.kernel.api.direct.NodeLabelRange;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.nioneo.store.AbstractBaseRecord;
import org.neo4j.kernel.impl.nioneo.store.IndexRule;
import org.neo4j.kernel.impl.nioneo.store.LabelTokenRecord;
import org.neo4j.kernel.impl.nioneo.store.PropertyKeyTokenRecord;
import org.neo4j.kernel.impl.nioneo.store.RecordStore;
import org.neo4j.kernel.impl.nioneo.store.RelationshipTypeTokenRecord;
import org.neo4j.kernel.impl.nioneo.store.SchemaStore;
import org.neo4j.kernel.impl.nioneo.store.StoreAccess;
import org.neo4j.kernel.impl.util.StringLogger;

import static java.lang.String.format;

import static org.neo4j.consistency.checking.full.MultiPassStore.ARRAYS;
import static org.neo4j.consistency.checking.full.MultiPassStore.NODES;
import static org.neo4j.consistency.checking.full.MultiPassStore.PROPERTIES;
import static org.neo4j.consistency.checking.full.MultiPassStore.RELATIONSHIPS;
import static org.neo4j.consistency.checking.full.MultiPassStore.STRINGS;
import static org.neo4j.consistency.checking.schema.IndexRules.loadAllIndexRules;

public class FullCheck
{
    private final boolean checkPropertyOwners;
    private final TaskExecutionOrder order;
    private final ProgressMonitorFactory progressFactory;
    private final Long totalMappedMemory;

    public FullCheck( Config tuningConfiguration, ProgressMonitorFactory progressFactory )
    {
        this.checkPropertyOwners = tuningConfiguration.get( ConsistencyCheckSettings.consistency_check_property_owners );
        this.order = tuningConfiguration.get( ConsistencyCheckSettings.consistency_check_execution_order );
        this.totalMappedMemory = tuningConfiguration.get( GraphDatabaseSettings.all_stores_total_mapped_memory_size );
        this.progressFactory = progressFactory;
    }

    public ConsistencySummaryStatistics execute( DirectStoreAccess stores, StringLogger logger )
            throws ConsistencyCheckIncompleteException
    {
        ConsistencySummaryStatistics summary = new ConsistencySummaryStatistics();
        InconsistencyReport report = new InconsistencyReport( new InconsistencyMessageLogger( logger ), summary );

        OwnerCheck ownerCheck = new OwnerCheck( checkPropertyOwners );
        execute( stores, ownerCheck, recordAccess( stores.nativeStores() ), report );
        ownerCheck.scanForOrphanChains( progressFactory );

        if ( !summary.isConsistent() )
        {
            logger.logMessage( "Inconsistencies found: " + summary );
        }
        return summary;
    }

    void execute( DirectStoreAccess directStoreAccess, CheckDecorator decorator, DiffRecordAccess recordAccess,
                  InconsistencyReport report )
            throws ConsistencyCheckIncompleteException
    {
        ConsistencyReporter reporter = new ConsistencyReporter( recordAccess, report );
        StoreProcessor processEverything = new StoreProcessor( decorator, reporter );

        ProgressMonitorFactory.MultiPartBuilder progress = progressFactory.multipleParts( "Full consistency check" );
        List<StoppableRunnable> tasks = new ArrayList<>( 16 );


        StoreAccess nativeStores = directStoreAccess.nativeStores();
        MultiPassStore.Factory multiPass = new MultiPassStore.Factory(
                decorator, totalMappedMemory, nativeStores, recordAccess, report );

        tasks.add( new StoreProcessorTask<>(
                nativeStores.getNodeStore(), progress, order,
                processEverything, multiPass.processors( PROPERTIES, RELATIONSHIPS ) ) );

        tasks.add( new StoreProcessorTask<>(
                nativeStores.getRelationshipStore(), progress, order,
                processEverything, multiPass.processors( NODES, PROPERTIES, RELATIONSHIPS ) ) );
        tasks.add( new StoreProcessorTask<>(
                nativeStores.getPropertyStore(), progress, order,
                processEverything, multiPass.processors( PROPERTIES, STRINGS, ARRAYS ) ) );
        tasks.add( new StoreProcessorTask<>(
                nativeStores.getStringStore(), progress, order,
                processEverything, multiPass.processors( STRINGS ) ) );
        tasks.add( new StoreProcessorTask<>(
                nativeStores.getArrayStore(), progress, order,
                processEverything, multiPass.processors( ARRAYS ) ) );

        // The schema store is verified in multiple passes that share state since it fits into memory
        // and we care about the consistency of back references (cf. SemanticCheck)

        // PASS 1: Dynamic record chains
        tasks.add( new StoreProcessorTask<>(
                nativeStores.getSchemaStore(), progress, order,
                processEverything, processEverything ) );

        // PASS 2: Rule integrity and obligation build up
        final SchemaRecordCheck schemaCheck = new SchemaRecordCheck( (SchemaStore) nativeStores.getSchemaStore() );
        tasks.add( new SchemaStoreProcessorTask<>(
                nativeStores.getSchemaStore(), "check_rules", schemaCheck, progress, order,
                processEverything, processEverything ) );

        // PASS 3: Obligation verification and semantic rule uniqueness
        tasks.add( new SchemaStoreProcessorTask<>(
                nativeStores.getSchemaStore(), "check_obligations", schemaCheck.forObligationChecking(), progress, order,
                processEverything, processEverything ) );

        tasks.add( new StoreProcessorTask<>(
                nativeStores.getRelationshipTypeTokenStore(), progress, order,
                processEverything, processEverything ) );
        tasks.add( new StoreProcessorTask<>(
                nativeStores.getPropertyKeyTokenStore(), progress, order,
                processEverything, processEverything ) );
        tasks.add( new StoreProcessorTask<>(
                nativeStores.getLabelTokenStore(), progress, order,
                processEverything, processEverything ) );

        tasks.add( new StoreProcessorTask<>(
                nativeStores.getRelationshipTypeNameStore(), progress, order,
                processEverything, processEverything ) );
        tasks.add( new StoreProcessorTask<>(
                nativeStores.getPropertyKeyNameStore(), progress, order,
                processEverything, processEverything ) );
        tasks.add( new StoreProcessorTask<>(
                nativeStores.getLabelNameStore(), progress, order,
                processEverything, processEverything ) );

        tasks.add( new StoreProcessorTask<>( nativeStores.getNodeDynamicLabelStore(), progress, order,
                processEverything, processEverything ) );

        int iPass = 0;
        for ( ConsistencyReporter filteredReporter : multiPass.reporters( order, NODES ) )
        {
            tasks.add( new RecordScanner<NodeLabelRange>( directStoreAccess.labelScanStore().newAllEntriesReader(),
                    format( "LabelScanStore_%d", iPass ), progress, new LabelScanDocumentProcessor( filteredReporter,
                    new LabelScanCheck() ) ) );

            for ( IndexRule indexRule : loadAllIndexRules( directStoreAccess.nativeStores().getSchemaStore() ) )
            {
                tasks.add( new RecordScanner<Long>( new IndexIterator( indexRule, directStoreAccess.indexes() ),
                        format( "Index_%d_%d", indexRule.getId(), iPass ), progress, new IndexEntryProcessor( filteredReporter,
                        new IndexCheck( indexRule ) ) ) );
            }
            iPass++;
        }

        order.execute( tasks, progress.build() );
    }

    static DiffRecordAccess recordAccess( StoreAccess store )
    {
        return new CacheSmallStoresRecordAccess(
                new DirectRecordAccess( store ),
                readAllRecords( PropertyKeyTokenRecord.class, store.getPropertyKeyTokenStore() ),
                readAllRecords( RelationshipTypeTokenRecord.class, store.getRelationshipTypeTokenStore() ),
                readAllRecords( LabelTokenRecord.class, store.getLabelTokenStore() ) );
    }

    private static <T extends AbstractBaseRecord> T[] readAllRecords( Class<T> type, RecordStore<T> store )
    {
        @SuppressWarnings("unchecked")
        T[] records = (T[]) Array.newInstance( type, (int) store.getHighId() );
        for ( int i = 0; i < records.length; i++ )
        {
            records[i] = store.forceGetRecord( i );
        }
        return records;
    }

}
