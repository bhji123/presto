/*
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
package io.prestosql.plugin.phoenix;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.slice.Slice;
import io.prestosql.plugin.jdbc.JdbcColumnHandle;
import io.prestosql.plugin.jdbc.JdbcIdentity;
import io.prestosql.plugin.jdbc.JdbcMetadata;
import io.prestosql.plugin.jdbc.JdbcMetadataConfig;
import io.prestosql.plugin.jdbc.JdbcOutputTableHandle;
import io.prestosql.plugin.jdbc.JdbcTableHandle;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.AggregateFunction;
import io.prestosql.spi.connector.AggregationApplicationResult;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.ConnectorInsertTableHandle;
import io.prestosql.spi.connector.ConnectorNewTableLayout;
import io.prestosql.spi.connector.ConnectorOutputMetadata;
import io.prestosql.spi.connector.ConnectorOutputTableHandle;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.ConnectorTableMetadata;
import io.prestosql.spi.connector.SchemaNotFoundException;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.security.PrestoPrincipal;
import io.prestosql.spi.statistics.ComputedStatistics;
import io.prestosql.spi.type.Type;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.TableProperty;
import org.apache.phoenix.util.SchemaUtil;

import javax.inject.Inject;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.plugin.phoenix.MetadataUtil.getEscapedTableName;
import static io.prestosql.plugin.phoenix.MetadataUtil.toPhoenixSchemaName;
import static io.prestosql.plugin.phoenix.MetadataUtil.toPrestoSchemaName;
import static io.prestosql.plugin.phoenix.PhoenixColumnProperties.isPrimaryKey;
import static io.prestosql.plugin.phoenix.PhoenixErrorCode.PHOENIX_METADATA_ERROR;
import static io.prestosql.spi.StandardErrorCode.ALREADY_EXISTS;
import static io.prestosql.spi.StandardErrorCode.NOT_SUPPORTED;
import static java.lang.String.format;
import static java.lang.String.join;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;
import static org.apache.hadoop.hbase.HConstants.FOREVER;
import static org.apache.phoenix.util.PhoenixRuntime.getTable;
import static org.apache.phoenix.util.SchemaUtil.getEscapedArgument;

public class PhoenixMetadata
        extends JdbcMetadata
{
    // Maps to Phoenix's default empty schema
    public static final String DEFAULT_SCHEMA = "default";
    // col name used for PK if none provided in DDL
    private static final String ROWKEY = "ROWKEY";
    private final PhoenixClient phoenixClient;

    @Inject
    public PhoenixMetadata(PhoenixClient phoenixClient, JdbcMetadataConfig metadataConfig)
    {
        super(phoenixClient, metadataConfig.isAllowDropTable());
        this.phoenixClient = requireNonNull(phoenixClient, "client is null");
    }

    @Override
    public JdbcTableHandle getTableHandle(ConnectorSession session, SchemaTableName schemaTableName)
    {
        return phoenixClient.getTableHandle(JdbcIdentity.from(session), schemaTableName)
                .map(tableHandle -> new JdbcTableHandle(
                        schemaTableName,
                        tableHandle.getCatalogName(),
                        toPrestoSchemaName(Optional.ofNullable(tableHandle.getSchemaName())).orElse(null),
                        tableHandle.getTableName()))
                .orElse(null);
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle table)
    {
        return getTableMetadata(session, table, false);
    }

    private ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle table, boolean rowkeyRequired)
    {
        JdbcTableHandle handle = (JdbcTableHandle) table;
        List<ColumnMetadata> columnMetadata = phoenixClient.getColumns(session, handle).stream()
                .filter(column -> rowkeyRequired || !ROWKEY.equalsIgnoreCase(column.getColumnName()))
                .map(JdbcColumnHandle::getColumnMetadata)
                .collect(toImmutableList());
        return new ConnectorTableMetadata(handle.getSchemaTableName(), columnMetadata, getTableProperties(session, handle));
    }

    private Map<String, Object> getTableProperties(ConnectorSession session, JdbcTableHandle handle)
    {
        ImmutableMap.Builder<String, Object> properties = ImmutableMap.builder();

        try (PhoenixConnection connection = phoenixClient.getConnection(JdbcIdentity.from(session));
                HBaseAdmin admin = connection.getQueryServices().getAdmin()) {
            String schemaName = toPhoenixSchemaName(Optional.ofNullable(handle.getSchemaName())).orElse(null);
            PTable table = getTable(connection, SchemaUtil.getTableName(schemaName, handle.getTableName()));

            boolean salted = table.getBucketNum() != null;
            StringJoiner joiner = new StringJoiner(",");
            List<PColumn> pkColumns = table.getPKColumns();
            for (PColumn pkColumn : pkColumns.subList(salted ? 1 : 0, pkColumns.size())) {
                joiner.add(pkColumn.getName().getString());
            }
            properties.put(PhoenixTableProperties.ROWKEYS, joiner.toString());

            if (table.getBucketNum() != null) {
                properties.put(PhoenixTableProperties.SALT_BUCKETS, table.getBucketNum());
            }
            if (table.isWALDisabled()) {
                properties.put(PhoenixTableProperties.DISABLE_WAL, table.isWALDisabled());
            }
            if (table.isImmutableRows()) {
                properties.put(PhoenixTableProperties.IMMUTABLE_ROWS, table.isImmutableRows());
            }

            String defaultFamilyName = QueryConstants.DEFAULT_COLUMN_FAMILY;
            if (table.getDefaultFamilyName() != null) {
                defaultFamilyName = table.getDefaultFamilyName().getString();
                properties.put(PhoenixTableProperties.DEFAULT_COLUMN_FAMILY, defaultFamilyName);
            }

            HTableDescriptor tableDesc = admin.getTableDescriptor(table.getPhysicalName().getBytes());

            HColumnDescriptor[] columnFamilies = tableDesc.getColumnFamilies();
            for (HColumnDescriptor columnFamily : columnFamilies) {
                if (columnFamily.getNameAsString().equals(defaultFamilyName)) {
                    if (!"NONE".equals(columnFamily.getBloomFilterType().toString())) {
                        properties.put(PhoenixTableProperties.BLOOMFILTER, columnFamily.getBloomFilterType().toString());
                    }
                    if (columnFamily.getMaxVersions() != 1) {
                        properties.put(PhoenixTableProperties.VERSIONS, columnFamily.getMaxVersions());
                    }
                    if (columnFamily.getMinVersions() > 0) {
                        properties.put(PhoenixTableProperties.MIN_VERSIONS, columnFamily.getMinVersions());
                    }
                    if (!columnFamily.getCompression().toString().equals("NONE")) {
                        properties.put(PhoenixTableProperties.COMPRESSION, columnFamily.getCompression().toString());
                    }
                    if (columnFamily.getTimeToLive() < FOREVER) {
                        properties.put(PhoenixTableProperties.TTL, columnFamily.getTimeToLive());
                    }
                    if (!columnFamily.getDataBlockEncoding().toString().equals("NONE")) {
                        properties.put(PhoenixTableProperties.DATA_BLOCK_ENCODING, columnFamily.getDataBlockEncoding().toString());
                    }
                    break;
                }
            }
        }
        catch (IOException | SQLException e) {
            throw new PrestoException(PHOENIX_METADATA_ERROR, "Couldn't get Phoenix table properties", e);
        }
        return properties.build();
    }

    @Override
    public void createSchema(ConnectorSession session, String schemaName, Map<String, Object> properties, PrestoPrincipal owner)
    {
        checkArgument(properties.isEmpty(), "Can't have properties for schema creation");
        if (DEFAULT_SCHEMA.equalsIgnoreCase(schemaName)) {
            throw new PrestoException(NOT_SUPPORTED, "Can't create 'default' schema which maps to Phoenix empty schema");
        }
        phoenixClient.execute(session, format("CREATE SCHEMA %s", getEscapedArgument(toMetadataCasing(session, schemaName))));
    }

    @Override
    public void dropSchema(ConnectorSession session, String schemaName)
    {
        if (DEFAULT_SCHEMA.equalsIgnoreCase(schemaName)) {
            throw new PrestoException(NOT_SUPPORTED, "Can't drop 'default' schema which maps to Phoenix empty schema");
        }
        phoenixClient.execute(session, format("DROP SCHEMA %s", getEscapedArgument(toMetadataCasing(session, schemaName))));
    }

    private String toMetadataCasing(ConnectorSession session, String schemaName)
    {
        try (Connection connection = phoenixClient.getConnection(JdbcIdentity.from(session))) {
            boolean uppercase = connection.getMetaData().storesUpperCaseIdentifiers();
            if (uppercase) {
                schemaName = schemaName.toUpperCase(ENGLISH);
            }
        }
        catch (SQLException e) {
            throw new PrestoException(PHOENIX_METADATA_ERROR, "Couldn't get casing for the schema name", e);
        }
        return schemaName;
    }

    @Override
    public void createTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, boolean ignoreExisting)
    {
        createTable(session, tableMetadata);
    }

    @Override
    public ConnectorOutputTableHandle beginCreateTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, Optional<ConnectorNewTableLayout> layout)
    {
        return createTable(session, tableMetadata);
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishCreateTable(ConnectorSession session, ConnectorOutputTableHandle tableHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
        return Optional.empty();
    }

    @Override
    public boolean supportsMissingColumnsOnInsert()
    {
        return true;
    }

    @Override
    public ConnectorInsertTableHandle beginInsert(ConnectorSession session, ConnectorTableHandle tableHandle, List<ColumnHandle> columns)
    {
        JdbcTableHandle handle = (JdbcTableHandle) tableHandle;
        Optional<String> rowkeyColumn = phoenixClient.getColumns(session, handle).stream()
                .map(JdbcColumnHandle::getColumnName)
                .filter(ROWKEY::equalsIgnoreCase)
                .findFirst();

        List<JdbcColumnHandle> columnHandles = columns.stream()
                .map(JdbcColumnHandle.class::cast)
                .collect(toImmutableList());

        return new PhoenixOutputTableHandle(
                Optional.ofNullable(handle.getSchemaName()),
                handle.getTableName(),
                columnHandles.stream().map(JdbcColumnHandle::getColumnName).collect(toImmutableList()),
                columnHandles.stream().map(JdbcColumnHandle::getColumnType).collect(toImmutableList()),
                Optional.of(columnHandles.stream().map(JdbcColumnHandle::getJdbcTypeHandle).collect(toImmutableList())),
                rowkeyColumn);
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishInsert(ConnectorSession session, ConnectorInsertTableHandle insertHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
        return Optional.empty();
    }

    @Override
    public void addColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnMetadata column)
    {
        JdbcTableHandle handle = (JdbcTableHandle) tableHandle;
        phoenixClient.execute(session, format(
                "ALTER TABLE %s ADD %s %s",
                getEscapedTableName(Optional.ofNullable(handle.getSchemaName()), handle.getTableName()),
                column.getName(),
                phoenixClient.toWriteMapping(session, column.getType()).getDataType()));
    }

    @Override
    public void dropColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle column)
    {
        JdbcTableHandle handle = (JdbcTableHandle) tableHandle;
        JdbcColumnHandle columnHandle = (JdbcColumnHandle) column;
        phoenixClient.execute(session, format(
                "ALTER TABLE %s DROP COLUMN %s",
                getEscapedTableName(Optional.ofNullable(handle.getSchemaName()), handle.getTableName()),
                columnHandle.getColumnName()));
    }

    @Override
    public void dropTable(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        // if we autogenerated a ROWKEY for this table, delete the associated sequence as well
        boolean hasRowkey = getColumnHandles(session, tableHandle).values().stream()
                .map(JdbcColumnHandle.class::cast)
                .map(JdbcColumnHandle::getColumnName)
                .anyMatch(ROWKEY::equals);
        if (hasRowkey) {
            JdbcTableHandle jdbcHandle = (JdbcTableHandle) tableHandle;
            phoenixClient.execute(session, format("DROP SEQUENCE %s", getEscapedTableName(Optional.ofNullable(jdbcHandle.getSchemaName()), jdbcHandle.getTableName() + "_sequence")));
        }
        phoenixClient.dropTable(JdbcIdentity.from(session), (JdbcTableHandle) tableHandle);
    }

    private JdbcOutputTableHandle createTable(ConnectorSession session, ConnectorTableMetadata tableMetadata)
    {
        SchemaTableName schemaTableName = tableMetadata.getTable();
        Optional<String> schema = Optional.of(schemaTableName.getSchemaName());
        String table = schemaTableName.getTableName();

        if (!phoenixClient.getSchemaNames(JdbcIdentity.from(session)).contains(schema.orElse(null))) {
            throw new SchemaNotFoundException(schema.orElse(null));
        }

        try (Connection connection = phoenixClient.getConnection(JdbcIdentity.from(session))) {
            boolean uppercase = connection.getMetaData().storesUpperCaseIdentifiers();
            if (uppercase) {
                schema = schema.map(schemaName -> schemaName.toUpperCase(ENGLISH));
                table = table.toUpperCase(ENGLISH);
            }
            schema = toPhoenixSchemaName(schema);
            LinkedList<ColumnMetadata> tableColumns = new LinkedList<>(tableMetadata.getColumns());
            Map<String, Object> tableProperties = tableMetadata.getProperties();
            Optional<Boolean> immutableRows = PhoenixTableProperties.getImmutableRows(tableProperties);
            String immutable = immutableRows.isPresent() && immutableRows.get() ? "IMMUTABLE" : "";

            ImmutableList.Builder<String> columnNames = ImmutableList.builder();
            ImmutableList.Builder<Type> columnTypes = ImmutableList.builder();
            ImmutableList.Builder<String> columnList = ImmutableList.builder();
            Set<ColumnMetadata> rowkeyColumns = tableColumns.stream().filter(col -> isPrimaryKey(col, tableProperties)).collect(toSet());
            ImmutableList.Builder<String> pkNames = ImmutableList.builder();
            Optional<String> rowkeyColumn = Optional.empty();
            if (rowkeyColumns.isEmpty()) {
                // Add a rowkey when not specified in DDL
                columnList.add(ROWKEY + " bigint not null");
                pkNames.add(ROWKEY);
                phoenixClient.execute(session, format("CREATE SEQUENCE %s", getEscapedTableName(schema, table + "_sequence")));
                rowkeyColumn = Optional.of(ROWKEY);
            }
            for (ColumnMetadata column : tableColumns) {
                String columnName = column.getName();
                if (uppercase) {
                    columnName = columnName.toUpperCase(ENGLISH);
                }
                columnNames.add(columnName);
                columnTypes.add(column.getType());
                String typeStatement = phoenixClient.toWriteMapping(session, column.getType()).getDataType();
                if (rowkeyColumns.contains(column)) {
                    typeStatement += " not null";
                    pkNames.add(columnName);
                }
                columnList.add(format("%s %s", columnName, typeStatement));
            }

            ImmutableList.Builder<String> tableOptions = ImmutableList.builder();
            PhoenixTableProperties.getSaltBuckets(tableProperties).ifPresent(value -> tableOptions.add(TableProperty.SALT_BUCKETS + "=" + value));
            PhoenixTableProperties.getSplitOn(tableProperties).ifPresent(value -> tableOptions.add("SPLIT ON (" + value.replace('"', '\'') + ")"));
            PhoenixTableProperties.getDisableWal(tableProperties).ifPresent(value -> tableOptions.add(TableProperty.DISABLE_WAL + "=" + value));
            PhoenixTableProperties.getDefaultColumnFamily(tableProperties).ifPresent(value -> tableOptions.add(TableProperty.DEFAULT_COLUMN_FAMILY + "=" + value));
            PhoenixTableProperties.getBloomfilter(tableProperties).ifPresent(value -> tableOptions.add(HColumnDescriptor.BLOOMFILTER + "='" + value + "'"));
            PhoenixTableProperties.getVersions(tableProperties).ifPresent(value -> tableOptions.add(HConstants.VERSIONS + "=" + value));
            PhoenixTableProperties.getMinVersions(tableProperties).ifPresent(value -> tableOptions.add(HColumnDescriptor.MIN_VERSIONS + "=" + value));
            PhoenixTableProperties.getCompression(tableProperties).ifPresent(value -> tableOptions.add(HColumnDescriptor.COMPRESSION + "='" + value + "'"));
            PhoenixTableProperties.getTimeToLive(tableProperties).ifPresent(value -> tableOptions.add(HColumnDescriptor.TTL + "=" + value));
            PhoenixTableProperties.getDataBlockEncoding(tableProperties).ifPresent(value -> tableOptions.add(HColumnDescriptor.DATA_BLOCK_ENCODING + "='" + value + "'"));

            String sql = format(
                    "CREATE %s TABLE %s (%s , CONSTRAINT PK PRIMARY KEY (%s)) %s",
                    immutable,
                    getEscapedTableName(schema, table),
                    join(", ", columnList.build()),
                    join(", ", pkNames.build()),
                    join(", ", tableOptions.build()));

            phoenixClient.execute(session, sql);

            return new PhoenixOutputTableHandle(
                    schema,
                    table,
                    columnNames.build(),
                    columnTypes.build(),
                    Optional.empty(),
                    rowkeyColumn);
        }
        catch (SQLException e) {
            if (e.getErrorCode() == SQLExceptionCode.TABLE_ALREADY_EXIST.getErrorCode()) {
                throw new PrestoException(ALREADY_EXISTS, "Phoenix table already exists", e);
            }
            throw new PrestoException(PHOENIX_METADATA_ERROR, "Error creating Phoenix table", e);
        }
    }

    @Override
    public Optional<AggregationApplicationResult<ConnectorTableHandle>> applyAggregation(
            ConnectorSession session,
            ConnectorTableHandle table,
            List<AggregateFunction> aggregates,
            Map<String, ColumnHandle> assignments,
            List<List<ColumnHandle>> groupingSets)
    {
        // TODO support aggregation pushdown
        return Optional.empty();
    }
}
