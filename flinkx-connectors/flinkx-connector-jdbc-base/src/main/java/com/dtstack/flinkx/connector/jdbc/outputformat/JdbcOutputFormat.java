/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dtstack.flinkx.connector.jdbc.outputformat;

import org.apache.flink.connector.jdbc.internal.converter.JdbcRowConverter;
import org.apache.flink.connector.jdbc.internal.options.JdbcOptions;
import org.apache.flink.connector.jdbc.statement.FieldNamedPreparedStatement;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.types.Row;

import com.dtstack.flinkx.connector.jdbc.util.DbUtil;
import com.dtstack.flinkx.enums.ColumnType;
import com.dtstack.flinkx.enums.EWriteMode;
import com.dtstack.flinkx.exception.WriteRecordException;
import com.dtstack.flinkx.outputformat.BaseRichOutputFormat;
import com.dtstack.flinkx.restore.FormatState;
import com.dtstack.flinkx.util.ClassUtil;
import com.dtstack.flinkx.util.DateUtil;
import com.dtstack.flinkx.util.ExceptionUtil;
import com.dtstack.flinkx.util.GsonUtil;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * OutputFormat for writing data to relational database.
 * <p>
 * Company: www.dtstack.com
 *
 * @author huyifan.zju@163.com
 */
public class JdbcOutputFormat extends BaseRichOutputFormat {

    protected static final Logger LOG = LoggerFactory.getLogger(JdbcOutputFormat.class);

    protected static final long serialVersionUID = 1L;

    /** Options for the JDBC connector. */
    protected JdbcOptions jdbcOptions;

    protected Connection dbConn;

    protected PreparedStatement preparedStatement;

    protected List<String> preSql;

    protected List<String> postSql;

    /** insert mode (append , upsert ) */
    protected String mode = EWriteMode.INSERT.name();

    /** just for postgresql,use copy replace insert */
    protected String insertSqlMode;

    /** table fields name */
    protected String[] column;

    protected String[] updateKey;

    protected String[] fullColumn;

    protected List<String> fullColumnType;

    protected List<String> columnType = new ArrayList<>();

    // protected TypeConverterInterface typeConverter;

    protected RowData lastRow = null;

    protected boolean readyCheckpoint;

    protected long rowsOfCurrentTransaction;

    public Properties properties;

    /**
     *
     */
    protected JdbcRowConverter jdbcRowConverter;

    /** table fields type */
    protected RowType rowType;

    protected FieldNamedPreparedStatement fieldNamedPreparedStatement;

    /**
     * schema名
     */
    public String schema;

    protected final static String GET_INDEX_SQL = "SELECT " +
            "t.INDEX_NAME," +
            "t.COLUMN_NAME " +
            "FROM " +
            "user_ind_columns t," +
            "user_indexes i " +
            "WHERE " +
            "t.index_name = i.index_name " +
            "AND i.uniqueness = 'UNIQUE' " +
            "AND t.table_name = '%s'";

    protected final static String CONN_CLOSE_ERROR_MSG = "No operations allowed";
    protected static List<String> STRING_TYPES = Arrays.asList(
            "CHAR",
            "VARCHAR",
            "VARCHAR2",
            "NVARCHAR2",
            "NVARCHAR",
            "TINYBLOB",
            "TINYTEXT",
            "BLOB",
            "TEXT",
            "MEDIUMBLOB",
            "MEDIUMTEXT",
            "LONGBLOB",
            "LONGTEXT");

    protected String prepareTemplates() throws SQLException {
        if (fullColumn == null || fullColumn.length == 0) {
            fullColumn = column;
        }

        String singleSql;
        if (EWriteMode.INSERT.name().equalsIgnoreCase(mode)) {
            singleSql = jdbcOptions
                    .getDialect()
                    .getInsertIntoStatement(jdbcOptions.getTableName(), column);
        } else if (EWriteMode.REPLACE.name().equalsIgnoreCase(mode)) {
            singleSql = jdbcOptions
                    .getDialect()
                    .getReplaceStatement(jdbcOptions.getTableName(), column)
                    .get();
        } else if (EWriteMode.UPDATE.name().equalsIgnoreCase(mode)) {
            singleSql = jdbcOptions
                    .getDialect()
                    .getUpsertStatement(jdbcOptions.getTableName(), column, updateKey, jdbcOptions.getAllReplace())
                    .get();
        } else {
            throw new IllegalArgumentException("Unknown write mode:" + mode);
        }

        LOG.info("write sql:{}", singleSql);

        return singleSql;
    }

    @Override
    protected void openInternal(int taskNumber, int numTasks) {
        try {
            ClassUtil.forName(jdbcOptions.getDriverName(), getClass().getClassLoader());
            dbConn = DbUtil.getConnection(
                    jdbcOptions.getDbURL(),
                    jdbcOptions.getUsername().get(),
                    jdbcOptions.getPassword().get());

            //默认关闭事务自动提交，手动控制事务
            dbConn.setAutoCommit(false);

            if (fullColumn == null || fullColumn.length == 0) {
                fullColumn = probeFullColumns(getTable(), dbConn);
            }

            if (!EWriteMode.INSERT.name().equalsIgnoreCase(mode)) {
                if (updateKey == null || updateKey.length == 0) {
                    // todo
                    // updateKey = probePrimaryKeys(getTable(), dbConn);
                }
            }

            if (fullColumnType == null) {
                // fullColumnType = analyzeTable();
            }

//            for (String col : column) {
//                for (int i = 0; i < fullColumn.length; i++) {
//                    if (col.equalsIgnoreCase(fullColumn[i])) {
//                        columnType.add(fullColumnType.get(i));
//                        break;
//                    }
//                }
//            }

            fieldNamedPreparedStatement = FieldNamedPreparedStatement.prepareStatement(
                    dbConn,
                    prepareTemplates(),
                    column);

            readyCheckpoint = false;
            LOG.info("subTask[{}}] wait finished", taskNumber);
        } catch (SQLException sqe) {
            throw new IllegalArgumentException("open() failed.", sqe);
        } finally {
            DbUtil.commit(dbConn);
        }
    }

    protected List<String> analyzeTable() {
        List<String> ret = new ArrayList<>();
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = dbConn.createStatement();
            rs = stmt.executeQuery(jdbcOptions
                    .getDialect()
                    .getSqlQueryFields(jdbcOptions.getDialect().quoteTable(
                            jdbcOptions.getTableName())));
            ResultSetMetaData rd = rs.getMetaData();
            for (int i = 0; i < rd.getColumnCount(); ++i) {
                ret.add(rd.getColumnTypeName(i + 1));
            }

            if (fullColumn == null || fullColumn.length == 0) {
                for (int i = 0; i < rd.getColumnCount(); ++i) {
                    fullColumn[i] = rd.getColumnName(i + 1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            DbUtil.closeDbResources(rs, stmt, null, false);
        }

        return ret;
    }

    @Override
    protected void writeSingleRecordInternal(RowData row) throws WriteRecordException {
        int index = 0;
        try {
            FieldNamedPreparedStatement fieldNamedPreparedStatement = jdbcRowConverter.toExternal(
                    row,
                    this.fieldNamedPreparedStatement);

            fieldNamedPreparedStatement.execute();
            DbUtil.commit(dbConn);
        } catch (Exception e) {
            DbUtil.rollBack(dbConn);
            processWriteException(e, index, row);
        }
    }

    protected void processWriteException(
            Exception e,
            int index,
            RowData row) throws WriteRecordException {
        if (e instanceof SQLException) {
            if (e.getMessage().contains(CONN_CLOSE_ERROR_MSG)) {
                throw new RuntimeException("Connection maybe closed", e);
            }
        }

        if (index < row.getArity()) {
            String message = recordConvertDetailErrorMessage(index, row);
            LOG.error(message, e);
            throw new WriteRecordException(message, e, index, row);
        }
        throw new WriteRecordException(e.getMessage(), e);
    }

    @Override
    protected String recordConvertDetailErrorMessage(int pos, RowData row) {
        return "\nJdbcOutputFormat [" + jobName + "] writeRecord error: when converting field["
                + pos + "] in Row(" + row + ")";
    }

    @Override
    protected void writeMultipleRecordsInternal() throws Exception {
        try {
            for (RowData row : rows) {
                FieldNamedPreparedStatement fieldNamedPreparedStatement = jdbcRowConverter.toExternal(
                        row,
                        this.fieldNamedPreparedStatement);

                fieldNamedPreparedStatement.executeBatch();

//                if (restoreConfig.isRestore()) {
//                    if (lastRow != null) {
//                        readyCheckpoint = !ObjectUtils.equals(
//                                lastRow.getField(restoreConfig.getRestoreColumnIndex()),
//                                row.getField(restoreConfig.getRestoreColumnIndex()));
//                    }
//
//                    lastRow = row;
//                }
            }


//            if (restoreConfig.isRestore()) {
//                rowsOfCurrentTransaction += rows.size();
//            } else {
//                //手动提交事务
//                DbUtil.commit(dbConn);
//            }
            DbUtil.commit(dbConn);
            preparedStatement.clearBatch();
        } catch (Exception e) {
            LOG.warn(
                    "write Multiple Records error, row size = {}, first row = {},  e = {}",
                    rows.size(),
                    rows.size() > 0 ? GsonUtil.GSON.toJson(rows.get(0)) : "null",
                    ExceptionUtil.getErrorMessage(e));
            LOG.warn(
                    "error to writeMultipleRecords, start to rollback connection, e = {}",
                    ExceptionUtil.getErrorMessage(e));
            DbUtil.rollBack(dbConn);
            throw e;
        } finally {
            //执行完后清空batch
            preparedStatement.clearBatch();
        }
    }

    @Override
    public FormatState getFormatState() {
//        if (!restoreConfig.isRestore() || lastRow == null) {
//            LOG.info("return null for formatState");
//            return null;
//        }
//
//        try {
//            LOG.info(
//                    "readyCheckpoint: {}, rowsOfCurrentTransaction: {}",
//                    readyCheckpoint,
//                    rowsOfCurrentTransaction);
//
//            if (readyCheckpoint
//                    || rowsOfCurrentTransaction > restoreConfig.getMaxRowNumForCheckpoint()) {
//
//                LOG.info("getFormatState:Start commit connection");
//                if (rows != null && rows.size() > 0) {
//                    super.writeRecordInternal();
//                } else {
//                    preparedStatement.executeBatch();
//                }
//                //若事务提交失败，抛出异常
//                dbConn.commit();
//                preparedStatement.clearBatch();
//                LOG.info("getFormatState:Commit connection success");
//
//                snapshotWriteCounter.add(rowsOfCurrentTransaction);
//                numWriteCounter.add(rowsOfCurrentTransaction);
//                rowsOfCurrentTransaction = 0;
//
//                formatState.setState(lastRow.getField(restoreConfig.getRestoreColumnIndex()));
//                formatState.setNumberWrite(snapshotWriteCounter.getLocalValue());
//                LOG.info("format state:{}", formatState.getState());
//
//                super.getFormatState();
//                return formatState;
//            }
//
//            return null;
//        } catch (Exception e) {
//            try {
//                //执行完后清空batch
//                preparedStatement.clearBatch();
//                LOG.warn("getFormatState:Start rollback");
//                //若事务回滚失败，抛出异常
//                dbConn.rollback();
//                LOG.warn("getFormatState:Rollback success");
//            } catch (SQLException sqlE) {
//                throw new RuntimeException("Rollback error:", e);
//            }
//
//            throw new RuntimeException("Return format state error:", e);
//        }
        return null;
    }

    @Override
    public void notifyCheckpointComplete(long checkpointId) throws Exception {

    }

    @Override
    public void notifyCheckpointAborted(long checkpointId) throws Exception {

    }

    /**
     * 获取转换后的字段value
     *
     * @param row
     * @param index
     *
     * @return
     */
    protected Object getField(Row row, int index) {
        Object field = row.getField(index);
        String type = columnType.get(index);

        //field为空字符串，且写入目标类型不为字符串类型的字段，则将object设置为null
        if (field instanceof String
                && StringUtils.isBlank((String) field)
                && !STRING_TYPES.contains(type.toUpperCase(Locale.ENGLISH))) {
            return null;
        }

        if (type.matches(DateUtil.DATE_REGEX)) {
            field = DateUtil.columnToDate(field, null);
        } else if (type.matches(DateUtil.DATETIME_REGEX)
                || type.matches(DateUtil.TIMESTAMP_REGEX)) {
            field = DateUtil.columnToTimestamp(field, null);
        }

        if (type.equalsIgnoreCase(ColumnType.BIGINT.name()) && field instanceof java.util.Date) {
            field = ((java.util.Date) field).getTime();
        }

        return field;
    }

    protected String[] probeFullColumns(String table, Connection dbConn) throws SQLException {
        List<String> ret = new ArrayList<>();
        ResultSet rs = dbConn.getMetaData().getColumns(null, null, table, null);
        while (rs.next()) {
            ret.add(rs.getString("COLUMN_NAME"));
        }
        return ret.toArray(new String[0]);
    }

    protected Map<String, List<String>> probePrimaryKeys(
            String table,
            Connection dbConn) throws SQLException {
        Map<String, List<String>> map = new HashMap<>(16);
        ResultSet rs = dbConn.getMetaData().getIndexInfo(null, null, table, true, false);
        while (rs.next()) {
            String indexName = rs.getString("INDEX_NAME");
            if (!map.containsKey(indexName)) {
                map.put(indexName, new ArrayList<>());
            }
            map.get(indexName).add(rs.getString("COLUMN_NAME"));
        }
        Map<String, List<String>> retMap = new HashMap<>((map.size() << 2) / 3);
        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
            String k = entry.getKey();
            List<String> v = entry.getValue();
            if (v != null && v.size() != 0 && v.get(0) != null) {
                retMap.put(k, v);
            }
        }
        return retMap;
    }

    @Override
    public void closeInternal() {
        readyCheckpoint = false;
        boolean commit = true;
        try {
            numWriteCounter.add(rowsOfCurrentTransaction);
            String state = getTaskState();
            // Do not commit a transaction when the task is canceled or failed
//            if (!RUNNING_STATE.equals(state) && restoreConfig.isRestore()) {
//                commit = false;
//            }
        } catch (Exception e) {
            LOG.error("Get task status error:{}", e.getMessage());
        }

        DbUtil.closeDbResources(null, preparedStatement, dbConn, commit);
        dbConn = null;
    }

    /**
     * 获取table名称，如果table是schema.table格式，可重写此方法 只返回table
     *
     * @return
     */
    protected String getTable() {
        return jdbcOptions.getTableName();
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }
}
