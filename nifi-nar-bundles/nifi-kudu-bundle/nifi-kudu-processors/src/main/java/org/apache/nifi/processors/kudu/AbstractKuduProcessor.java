/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.processors.kudu;

import org.apache.kudu.shaded.com.google.common.annotations.VisibleForTesting;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;
import org.apache.kudu.Type;
import org.apache.kudu.client.AsyncKuduClient;
import org.apache.kudu.client.KuduClient;
import org.apache.kudu.client.KuduTable;
import org.apache.kudu.client.KuduSession;
import org.apache.kudu.client.KuduException;
import org.apache.kudu.client.OperationResponse;
import org.apache.kudu.client.PartialRow;
import org.apache.kudu.client.RowError;
import org.apache.kudu.client.SessionConfiguration;
import org.apache.kudu.client.Delete;
import org.apache.kudu.client.Insert;
import org.apache.kudu.client.Upsert;
import org.apache.kudu.client.Update;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyDescriptor.Builder;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.kerberos.KerberosCredentialsService;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.security.krb.KerberosAction;
import org.apache.nifi.security.krb.KerberosKeytabUser;
import org.apache.nifi.security.krb.KerberosUser;
import org.apache.nifi.serialization.record.DataType;
import org.apache.nifi.serialization.record.Record;
import org.apache.nifi.serialization.record.util.DataTypeUtils;

import javax.security.auth.login.LoginException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.List;

public abstract class AbstractKuduProcessor extends AbstractProcessor {

    static final PropertyDescriptor KUDU_MASTERS = new Builder()
            .name("Kudu Masters")
            .description("Comma separated addresses of the Kudu masters to connect to.")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();

    static final PropertyDescriptor KERBEROS_CREDENTIALS_SERVICE = new Builder()
            .name("kerberos-credentials-service")
            .displayName("Kerberos Credentials Service")
            .description("Specifies the Kerberos Credentials to use for authentication")
            .required(false)
            .identifiesControllerService(KerberosCredentialsService.class)
            .build();

    static final PropertyDescriptor KUDU_OPERATION_TIMEOUT_MS = new Builder()
            .name("kudu-operations-timeout-ms")
            .displayName("Kudu Operation Timeout")
            .description("Default timeout used for user operations (using sessions and scanners)")
            .required(false)
            .defaultValue(String.valueOf(AsyncKuduClient.DEFAULT_OPERATION_TIMEOUT_MS) + "ms")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();

    static final PropertyDescriptor KUDU_KEEP_ALIVE_PERIOD_TIMEOUT_MS = new Builder()
            .name("kudu-keep-alive-period-timeout-ms")
            .displayName("Kudu Keep Alive Period Timeout")
            .description("Default timeout used for user operations")
            .required(false)
            .defaultValue(String.valueOf(AsyncKuduClient.DEFAULT_KEEP_ALIVE_PERIOD_MS) + "ms")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();

    protected KuduClient kuduClient;

    private volatile KerberosUser kerberosUser;

    public KerberosUser getKerberosUser() {
        return this.kerberosUser;
    }

    public KuduClient getKuduClient() {
        return this.kuduClient;
    }

    public void createKuduClient(ProcessContext context) throws LoginException {
        final String kuduMasters = context.getProperty(KUDU_MASTERS).evaluateAttributeExpressions().getValue();
        final KerberosCredentialsService credentialsService = context.getProperty(KERBEROS_CREDENTIALS_SERVICE).asControllerService(KerberosCredentialsService.class);

        if (credentialsService != null) {
            final String keytab = credentialsService.getKeytab();
            final String principal = credentialsService.getPrincipal();
            kerberosUser = loginKerberosUser(principal, keytab);

            final KerberosAction<KuduClient> kerberosAction = new KerberosAction<>(kerberosUser, () -> buildClient(kuduMasters, context), getLogger());
            this.kuduClient = kerberosAction.execute();
        } else {
            this.kuduClient = buildClient(kuduMasters, context);
        }
    }


    protected KuduClient buildClient(final String masters, final ProcessContext context) {
        final Integer operationTimeout = context.getProperty(KUDU_OPERATION_TIMEOUT_MS).evaluateAttributeExpressions().asTimePeriod(TimeUnit.MILLISECONDS).intValue();
        final Integer adminOperationTimeout = context.getProperty(KUDU_KEEP_ALIVE_PERIOD_TIMEOUT_MS).evaluateAttributeExpressions().asTimePeriod(TimeUnit.MILLISECONDS).intValue();

        return new KuduClient.KuduClientBuilder(masters)
                .defaultOperationTimeoutMs(operationTimeout)
                .defaultSocketReadTimeoutMs(adminOperationTimeout)
                .build();
    }

    protected void flushKuduSession(final KuduSession kuduSession, boolean close, final List<RowError> rowErrors) throws KuduException {
        final List<OperationResponse> responses = close ? kuduSession.close() : kuduSession.flush();

        if (kuduSession.getFlushMode() == SessionConfiguration.FlushMode.AUTO_FLUSH_BACKGROUND) {
            rowErrors.addAll(Arrays.asList(kuduSession.getPendingErrors().getRowErrors()));
        } else {
            responses.stream()
                    .filter(OperationResponse::hasRowError)
                    .map(OperationResponse::getRowError)
                    .forEach(rowErrors::add);
        }
    }

    protected KerberosUser loginKerberosUser(final String principal, final String keytab) throws LoginException {
        final KerberosUser kerberosUser = new KerberosKeytabUser(principal, keytab);
        kerberosUser.login();
        return kerberosUser;
    }

    @OnStopped
    public void shutdown() throws Exception {
        try {
            if (this.kuduClient != null) {
                getLogger().debug("Closing KuduClient");
                this.kuduClient.close();
                this.kuduClient  = null;
            }
        } finally {
            if (kerberosUser != null) {
                kerberosUser.logout();
                kerberosUser = null;
            }
        }
    }

    @VisibleForTesting
    protected void buildPartialRow(Schema schema, PartialRow row, Record record, List<String> fieldNames, Boolean ignoreNull, Boolean lowercaseFields) {
        for (String recordFieldName : fieldNames) {
            String colName = recordFieldName;
            if (lowercaseFields) {
                colName = colName.toLowerCase();
            }
            int colIdx = this.getColumnIndex(schema, colName);
            if (colIdx != -1) {
                ColumnSchema colSchema = schema.getColumnByIndex(colIdx);
                Type colType = colSchema.getType();

                if (record.getValue(recordFieldName) == null) {
                    if (schema.getColumnByIndex(colIdx).isKey()) {
                        throw new IllegalArgumentException(String.format("Can't set primary key column %s to null ", colName));
                    } else if(!schema.getColumnByIndex(colIdx).isNullable()) {
                        throw new IllegalArgumentException(String.format("Can't set column %s to null ", colName));
                    }

                    if (!ignoreNull) {
                        row.setNull(colName);
                    }
                } else {
                    Object value = record.getValue(recordFieldName);
                    switch (colType) {
                        case BOOL:
                            row.addBoolean(colIdx, DataTypeUtils.toBoolean(value, recordFieldName));
                            break;
                        case INT8:
                            row.addByte(colIdx, DataTypeUtils.toByte(value, recordFieldName));
                            break;
                        case INT16:
                            row.addShort(colIdx,  DataTypeUtils.toShort(value, recordFieldName));
                            break;
                        case INT32:
                            row.addInt(colIdx,  DataTypeUtils.toInteger(value, recordFieldName));
                            break;
                        case INT64:
                            row.addLong(colIdx,  DataTypeUtils.toLong(value, recordFieldName));
                            break;
                        case UNIXTIME_MICROS:
                            DataType fieldType = record.getSchema().getDataType(recordFieldName).get();
                            Timestamp timestamp = DataTypeUtils.toTimestamp(record.getValue(recordFieldName),
                                    () -> DataTypeUtils.getDateFormat(fieldType.getFormat()), recordFieldName);
                            row.addTimestamp(colIdx, timestamp);
                            break;
                        case STRING:
                            row.addString(colIdx, DataTypeUtils.toString(value, recordFieldName));
                            break;
                        case BINARY:
                            row.addBinary(colIdx, DataTypeUtils.toString(value, recordFieldName).getBytes());
                            break;
                        case FLOAT:
                            row.addFloat(colIdx, DataTypeUtils.toFloat(value, recordFieldName));
                            break;
                        case DOUBLE:
                            row.addDouble(colIdx, DataTypeUtils.toDouble(value, recordFieldName));
                            break;
                        case DECIMAL:
                            row.addDecimal(colIdx, new BigDecimal(DataTypeUtils.toString(value, recordFieldName)));
                            break;
                        default:
                            throw new IllegalStateException(String.format("unknown column type %s", colType));
                    }
                }
            }
        }
    }

    /**
     * Converts a NiFi DataType to it's equivalent Kudu Type.
     */
    protected Type toKuduType(DataType nifiType) {
        switch (nifiType.getFieldType()) {
            case BOOLEAN:
                return Type.BOOL;
            case BYTE:
                return Type.INT8;
            case SHORT:
                return Type.INT16;
            case INT:
                return Type.INT32;
            case LONG:
                return Type.INT64;
            case FLOAT:
                return Type.FLOAT;
            case DOUBLE:
                return Type.DOUBLE;
            case TIMESTAMP:
                return Type.UNIXTIME_MICROS;
            case CHAR:
            case STRING:
                return Type.STRING;
            default:
                throw new IllegalArgumentException(String.format("unsupported type %s", nifiType));
        }
    }

    private int getColumnIndex(Schema columns, String colName) {
        try {
            return columns.getColumnIndex(colName);
        } catch (Exception ex) {
            return -1;
        }
    }

    protected Upsert upsertRecordToKudu(KuduTable kuduTable, Record record, List<String> fieldNames, Boolean ignoreNull, Boolean lowercaseFields) {
        Upsert upsert = kuduTable.newUpsert();
        buildPartialRow(kuduTable.getSchema(), upsert.getRow(), record, fieldNames, ignoreNull, lowercaseFields);
        return upsert;
    }

    protected Insert insertRecordToKudu(KuduTable kuduTable, Record record, List<String> fieldNames, Boolean ignoreNull, Boolean lowercaseFields) {
        Insert insert = kuduTable.newInsert();
        buildPartialRow(kuduTable.getSchema(), insert.getRow(), record, fieldNames, ignoreNull, lowercaseFields);
        return insert;
    }

    protected Delete deleteRecordFromKudu(KuduTable kuduTable, Record record, List<String> fieldNames, Boolean ignoreNull, Boolean lowercaseFields) {
        Delete delete = kuduTable.newDelete();
        buildPartialRow(kuduTable.getSchema(), delete.getRow(), record, fieldNames, ignoreNull, lowercaseFields);
        return delete;
    }

    protected Update updateRecordToKudu(KuduTable kuduTable, Record record, List<String> fieldNames, Boolean ignoreNull, Boolean lowercaseFields) {
        Update update = kuduTable.newUpdate();
        buildPartialRow(kuduTable.getSchema(), update.getRow(), record, fieldNames, ignoreNull, lowercaseFields);
        return update;
    }

}
