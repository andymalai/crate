/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.execution.engine.collect.sources;

import com.carrotsearch.hppc.IntIndexedContainer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import io.crate.data.BatchIterator;
import io.crate.data.CollectingBatchIterator;
import io.crate.data.Row;
import io.crate.exceptions.RelationUnknown;
import io.crate.exceptions.SchemaUnknownException;
import io.crate.execution.dsl.phases.CollectPhase;
import io.crate.execution.dsl.phases.RoutedCollectPhase;
import io.crate.execution.engine.collect.CollectTask;
import io.crate.execution.engine.collect.RowsTransformer;
import io.crate.expression.InputFactory;
import io.crate.expression.reference.ReferenceResolver;
import io.crate.expression.reference.StaticTableDefinition;
import io.crate.expression.reference.sys.SysRowUpdater;
import io.crate.expression.reference.sys.check.node.SysNodeChecks;
import io.crate.metadata.Functions;
import io.crate.metadata.RelationName;
import io.crate.metadata.information.InformationSchemaInfo;
import io.crate.metadata.information.InformationSchemaTableDefinitions;
import io.crate.metadata.pgcatalog.PgCatalogSchemaInfo;
import io.crate.metadata.pgcatalog.PgCatalogTableDefinitions;
import io.crate.metadata.sys.SysNodeChecksTableInfo;
import io.crate.metadata.sys.SysSchemaInfo;
import io.crate.metadata.sys.SysTableDefinitions;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;

import java.util.Map;
import java.util.function.Function;

/**
 * this collect service can be used to retrieve a collector for system tables (which don't contain shards)
 * <p>
 * System tables are generally represented as Iterable of some type and are converted on-the-fly to {@link Row}
 */
public class SystemCollectSource implements CollectSource {

    private final ImmutableMap<RelationName, SysRowUpdater<?>> rowUpdaters;
    private final ClusterService clusterService;
    private final InputFactory inputFactory;

    private final InformationSchemaTableDefinitions informationSchemaTables;
    private final SysTableDefinitions sysTables;
    private final PgCatalogTableDefinitions pgCatalogTables;

    @Inject
    public SystemCollectSource(ClusterService clusterService,
                               Functions functions,
                               InformationSchemaTableDefinitions informationSchemaTables,
                               SysTableDefinitions sysTableDefinitions,
                               SysNodeChecks sysNodeChecks,
                               PgCatalogTableDefinitions pgCatalogTables) {
        this.clusterService = clusterService;
        inputFactory = new InputFactory(functions);
        this.informationSchemaTables = informationSchemaTables;
        this.sysTables = sysTableDefinitions;
        this.pgCatalogTables = pgCatalogTables;

        rowUpdaters = ImmutableMap.of(SysNodeChecksTableInfo.IDENT, sysNodeChecks);
    }

    Function<Iterable, Iterable<? extends Row>> toRowsIterableTransformation(RoutedCollectPhase collectPhase,
                                                                             ReferenceResolver<?> referenceResolver,
                                                                             boolean requiresRepeat) {
        return objects -> recordsToRows(collectPhase, referenceResolver, requiresRepeat, objects);
    }

    private Iterable<? extends Row> recordsToRows(RoutedCollectPhase collectPhase,
                                                  ReferenceResolver<?> referenceResolver,
                                                  boolean requiresRepeat,
                                                  Iterable<?> data) {
        if (requiresRepeat) {
            data = ImmutableList.copyOf(data);
        }
        return RowsTransformer.toRowsIterable(
            inputFactory,
            referenceResolver,
            collectPhase,
            data);
    }

    @Override
    public BatchIterator<Row> getIterator(CollectPhase phase, CollectTask collectTask, boolean supportMoveToStart) {
        RoutedCollectPhase collectPhase = (RoutedCollectPhase) phase;

        Map<String, Map<String, IntIndexedContainer>> locations = collectPhase.routing().locations();
        String table = Iterables.getOnlyElement(locations.get(clusterService.localNode().getId()).keySet());
        RelationName relationName = RelationName.fromIndexName(table);
        StaticTableDefinition<?> tableDefinition = tableDefinition(relationName);

        return CollectingBatchIterator.newInstance(
            () -> {},
            // kill no-op: Can't interrupt remote retrieval;
            // If data is already local, then `CollectingBatchIterator` takes care of kill handling.
            t -> {},
            () -> tableDefinition.retrieveRecords(collectPhase.user())
                .thenApply(records ->
                        recordsToRows(
                            collectPhase,
                            tableDefinition.getReferenceResolver(),
                            supportMoveToStart,
                            records
                        )
                    )
        );
    }

    public StaticTableDefinition<?> tableDefinition(RelationName relationName) {
        StaticTableDefinition<?> tableDefinition;
        switch (relationName.schema()) {
            case InformationSchemaInfo.NAME:
                tableDefinition = informationSchemaTables.get(relationName);
                break;
            case SysSchemaInfo.NAME:
                tableDefinition = sysTables.get(relationName);
                break;
            case PgCatalogSchemaInfo.NAME:
                tableDefinition = pgCatalogTables.get(relationName);
                break;
            default:
                throw new SchemaUnknownException(relationName.schema());
        }
        if (tableDefinition == null) {
            throw new RelationUnknown(relationName);
        }
        return tableDefinition;
    }

    /**
     * Returns a new updater for a given table.
     *
     * @param ident the ident of the table
     * @return a row updater instance for the given table
     */
    public SysRowUpdater<?> getRowUpdater(RelationName ident) {
        assert rowUpdaters.containsKey(ident) : "RowUpdater for " + ident.fqn() + " must exist";
        return rowUpdaters.get(ident);
    }
}
