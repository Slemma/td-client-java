//
// Java Client Library for Treasure Data Cloud
//
// Copyright (C) 2011 - 2013 Muga Nishizawa
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//        http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//
package com.treasure_data.client;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.simple.JSONValue;
import org.msgpack.unpacker.Unpacker;

import com.treasure_data.auth.TreasureDataCredentials;
import com.treasure_data.model.AuthenticateRequest;
import com.treasure_data.model.AuthenticateResult;
import com.treasure_data.model.AuthenticationException;
import com.treasure_data.model.ConflictException;
import com.treasure_data.model.CreateDatabaseRequest;
import com.treasure_data.model.CreateDatabaseResult;
import com.treasure_data.model.CreateItemTableRequest;
import com.treasure_data.model.CreateTableRequest;
import com.treasure_data.model.CreateTableResult;
import com.treasure_data.model.Database;
import com.treasure_data.model.DatabaseSummary;
import com.treasure_data.model.DeleteDatabaseRequest;
import com.treasure_data.model.DeleteDatabaseResult;
import com.treasure_data.model.DeletePartialTableRequest;
import com.treasure_data.model.DeletePartialTableResult;
import com.treasure_data.model.DeleteTableRequest;
import com.treasure_data.model.DeleteTableResult;
import com.treasure_data.model.ExportRequest;
import com.treasure_data.model.ExportResult;
import com.treasure_data.model.ImportRequest;
import com.treasure_data.model.ImportResult;
import com.treasure_data.model.ItemTable;
import com.treasure_data.model.Job;
import com.treasure_data.model.JobResult;
import com.treasure_data.model.JobResult2;
import com.treasure_data.model.JobSummary;
import com.treasure_data.model.KillJobRequest;
import com.treasure_data.model.KillJobResult;
import com.treasure_data.model.ListDatabases;
import com.treasure_data.model.ListDatabasesRequest;
import com.treasure_data.model.ListDatabasesResult;
import com.treasure_data.model.ListJobs;
import com.treasure_data.model.ListJobsRequest;
import com.treasure_data.model.ListJobsResult;
import com.treasure_data.model.ListTables;
import com.treasure_data.model.ListTablesRequest;
import com.treasure_data.model.ListTablesResult;
import com.treasure_data.model.GetJobResultRequest;
import com.treasure_data.model.GetJobResultResult;
import com.treasure_data.model.LogTable;
import com.treasure_data.model.NotFoundException;
import com.treasure_data.model.RenameTableRequest;
import com.treasure_data.model.RenameTableResult;
import com.treasure_data.model.ServerStatus;
import com.treasure_data.model.GetServerStatusRequest;
import com.treasure_data.model.GetServerStatusResult;
import com.treasure_data.model.SetTableSchemaRequest;
import com.treasure_data.model.SetTableSchemaResult;
import com.treasure_data.model.ShowJobRequest;
import com.treasure_data.model.ShowJobResult;
import com.treasure_data.model.ShowJobStatusRequest;
import com.treasure_data.model.ShowJobStatusResult;
import com.treasure_data.model.SubmitJobRequest;
import com.treasure_data.model.SubmitJobResult;
import com.treasure_data.model.SwapTableRequest;
import com.treasure_data.model.SwapTableResult;
import com.treasure_data.model.Table;
import com.treasure_data.model.TableSchema;
import com.treasure_data.model.TableSummary;

public class DefaultClientAdaptorImpl extends AbstractClientAdaptor implements
        DefaultClientAdaptor {

    private static Logger LOG = Logger.getLogger(DefaultClientAdaptorImpl.class
            .getName());

    private Validator validator;

    DefaultClientAdaptorImpl(Config conf) {
        super(conf);
        validator = new Validator();
    }

    @Override
    public AuthenticateResult authenticate(AuthenticateRequest request)
            throws ClientException {
        int count = 0;
        AuthenticateResult ret;
        while (true) {
            try {
                ret = doAuthenticate(request);
                if (count > 0) {
                    LOG.warning("Retry succeeded.");
                }
                break;
            } catch (ClientException e) {
                if (e instanceof HttpClientException) {
                    HttpClientException ex = (HttpClientException) e;
                    int statusCode = ex.getResponseCode();
                    if (statusCode == 401 || statusCode == 404 || statusCode == 407) {
                        // If authentication failed 401, 404 or 407 (Proxy Authentication Required), it doesn't retry.
                        throw new AuthenticationException("Authentication failed", e.getMessage(), statusCode);
                    }
                }

                // TODO FIXME
                if (count >= getRetryCount()) {
                    LOG.warning("Retry count exceeded limit: " + e.getMessage());
                    throw new ClientException("Retry error", e);
                } else {
                    count++;
                    LOG.warning("It failed. but will be retried: " + e.getMessage());
                    waitRetry(getRetryWaitTime(), count);
                }
            }
        }
        return ret;
    }

    private AuthenticateResult doAuthenticate(AuthenticateRequest request)
            throws ClientException {
        request.setCredentials(getConfig().getCredentials());

        String jsonData = null;
        String message = null;
        int code = 0;
        try {
            conn = createConnection();

            // send request
            String path = HttpURL.V3_USER_AUTHENTICATE;
            Map<String, String> header = new HashMap<String, String>();
            setUserAgentHeader(header);
            Map<String, String> params = new HashMap<String, String>();
            params.put("user", HttpConnectionImpl.e(request.getEmail()));
            params.put("password", HttpConnectionImpl.e(request.getPassword()));
            conn.doPostRequest(request, path, header, params);

            // receive response code
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            if (code != HttpURLConnection.HTTP_OK) {
                String errMessage = conn.getErrorMessage();
                checkTrial(code, errMessage);
                LOG.severe(HttpClientException.toMessage(
                        "Authentication failed", message, code));
                LOG.severe(errMessage);
                throw new HttpClientException("Authentication failed",
                        message + ", detail = " + errMessage, code);
            }

            // receive response body
            jsonData = conn.getResponseBody();
            validator.validateJSONData(jsonData);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Authentication failed", e);
            if(conn != null) {
                try {
                    code = conn.getResponseCode();
                }
                catch (IOException ioe) {
                    LOG.log(Level.WARNING, "Failed to get response code", ioe);
                }
            }
            throw new HttpClientException("Authentication failed", message, code, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        // { "user":"myemailaddress","apikey":"myapikey" }
        // parse JSON data
        @SuppressWarnings("unchecked")
        Map<String, String> map = (Map<String, String>) JSONValue.parse(jsonData);
        validator.validateJavaObject(jsonData, map);
        //String user = map.get("user");
        String apiKey = map.get("apikey");
        TreasureDataCredentials credentails = new TreasureDataCredentials(apiKey);

        return new AuthenticateResult(credentails);
    }

    @Override
    public GetServerStatusResult getServerStatus(GetServerStatusRequest request)
            throws ClientException {
        int count = 0;
        GetServerStatusResult ret;
        while (true) {
            try {
                ret = doGetServerStatus(request);
                if (count > 0) {
                    LOG.warning("Retry succeeded.");
                }
                break;
            } catch (ClientException e) {
                // TODO FIXME
                if (count >= getRetryCount()) {
                    LOG.warning("Retry count exceeded limit: " + e.getMessage());
                    throw new ClientException("Retry error", e);
                } else {
                    count++;
                    LOG.warning("It failed. but will be retried: " + e.getMessage());
                    waitRetry(getRetryWaitTime(), count);
                }
            }
        }
        return ret;
    }

    private GetServerStatusResult doGetServerStatus(GetServerStatusRequest request)
            throws ClientException {
        request.setCredentials(getConfig().getCredentials());

        String jsonData = null;
        int code = 0;
        String message = null;
        try {
            conn = createConnection();

            // send request
            String path = HttpURL.V3_SYSTEM_SERVER_STATUS;
            Map<String, String> header = new HashMap<String, String>();
            setUserAgentHeader(header);
            Map<String, String> params = null;
            conn.doGetRequest(request, path, header, params);

            // receive response code and body
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            if (code != HttpURLConnection.HTTP_OK) {
                String errMessage = conn.getErrorMessage();
                LOG.severe(HttpClientException.toMessage(
                        "Server is down", message, code));
                LOG.severe(errMessage);
                throw new HttpClientException("Server is down",
                        message + ", detail = " + errMessage, code);
            }

            // receive response body
            jsonData = conn.getResponseBody();
            validator.validateJSONData(jsonData);
        } catch (IOException e) {
            LOG.throwing(getClass().getName(), "getServerStatus", e);
            LOG.severe(HttpClientException.toMessage(e.getMessage(), message, code));
            throw new HttpClientException("Server is down", message, code, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        // { "status": "ok" }
        @SuppressWarnings("rawtypes")
        Map map = (Map) JSONValue.parse(jsonData);
        validator.validateJavaObject(jsonData, map);
        String status = (String) map.get("status");

        return new GetServerStatusResult(new ServerStatus(status));
    }

    @Override
    public ListDatabasesResult listDatabases(ListDatabasesRequest request)
            throws ClientException {
        int count = 0;
        ListDatabasesResult ret;
        while (true) {
            try {
                ret = doListDatabases(request);
                if (count > 0) {
                    LOG.warning("Retry succeeded.");
                }
                break;
            } catch (ClientException e) {
                // TODO FIXME
                if (count >= getRetryCount()) {
                    LOG.warning("Retry count exceeded limit: " + e.getMessage());
                    throw new ClientException("Retry error", e);
                } else {
                    count++;
                    LOG.warning("It failed. but will be retried: " + e.getMessage());
                    waitRetry(getRetryWaitTime(), count);
                }
            }
        }
        return ret;
    }

    private ListDatabasesResult doListDatabases(ListDatabasesRequest request)
            throws ClientException {
        request.setCredentials(getConfig().getCredentials());
        validator.validateCredentials(this, request);

        String jsonData = null;
        String message = null;
        int code = 0;
        try {
            conn = createConnection();

            // send request
            String path = HttpURL.V3_DATABASE_LIST;
            Map<String, String> header = new HashMap<String, String>();
            setUserAgentHeader(header);
            Map<String, String> params = null;
            conn.doGetRequest(request, path, header, params);

            // receive response code and body
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            if (code != HttpURLConnection.HTTP_OK) {
                String errMessage = conn.getErrorMessage();
                LOG.severe(HttpClientException.toMessage(
                        "List databases failed", message, code));
                LOG.severe(errMessage);
                throw new HttpClientException("List databases failed",
                        message + ", detail = " + errMessage, code);
            }

            // receive response body
            jsonData = conn.getResponseBody();
            validator.validateJSONData(jsonData);
        } catch (IOException e) {
            LOG.throwing(getClass().getName(), "listDatabases", e);
            LOG.severe(HttpClientException.toMessage(e.getMessage(), message, code));
            throw new HttpClientException("List databases failed", message, code, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        // parse JSON data
        @SuppressWarnings("rawtypes")
        Map map = (Map) JSONValue.parse(jsonData);
        validator.validateJavaObject(jsonData, map);

        @SuppressWarnings("unchecked")
        Iterator<Map<String, Object>> dbMaps =
            ((List<Map<String, Object>>) map.get("databases")).iterator();
        List<DatabaseSummary> databases = new ArrayList<DatabaseSummary>();
        while (dbMaps.hasNext()) {
            Map<String, Object> dbMap = dbMaps.next();
            String name = (String) dbMap.get("name");
            long count = (Long) dbMap.get("count");
            String createdAt = (String) dbMap.get("created_at");
            String updatedAt = (String) dbMap.get("updated_at");
            databases.add(new DatabaseSummary(name, count, createdAt, updatedAt));
        }

        return new ListDatabasesResult(new ListDatabases<DatabaseSummary>(databases));
    }

    @Override
    public CreateDatabaseResult createDatabase(CreateDatabaseRequest request)
            throws ClientException {
        int count = 0;
        CreateDatabaseResult ret;
        while (true) {
            try {
                ret = doCreateDatabase(request);
                if (count > 0) {
                    LOG.warning("Retry succeeded.");
                }
                break;
            } catch (ClientException e) {
                // TODO FIXME
                if (count >= getRetryCount()) {
                    LOG.warning("Retry count exceeded limit: " + e.getMessage());
                    throw new ClientException("Retry error", e);
                } else {
                    count++;
                    LOG.warning("It failed. but will be retried: " + e.getMessage());
                    waitRetry(getRetryWaitTime(), count);
                }
            }
        }
        return ret;
    }

    private CreateDatabaseResult doCreateDatabase(CreateDatabaseRequest request)
            throws ClientException {
        validator.validateDatabaseName(request.getDatabaseName());
        request.setCredentials(getConfig().getCredentials());
        validator.validateCredentials(this, request);

        String jsonData = null;
        int code = 0;
        String message = null;
        try {
            conn = createConnection();

            // send request
            String path = String.format(HttpURL.V3_DATABASE_CREATE,
                    HttpConnectionImpl.e(request.getDatabaseName()));
            Map<String, String> header = new HashMap<String, String>();
            setUserAgentHeader(header);
            Map<String, String> params = null;
            conn.doPostRequest(request, path, header, params);

            // receive response code
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            if (code != HttpURLConnection.HTTP_OK) {
                String errMessage = conn.getErrorMessage();
                LOG.severe(HttpClientException.toMessage(
                        "Create database failed", message, code));
                LOG.severe(errMessage);
                throw new HttpClientException("Create database failed",
                        message + ", detail = " + errMessage, code);
            }

            // receive response body
            jsonData = conn.getResponseBody();
            validator.validateJSONData(jsonData);
        } catch (IOException e) {
            LOG.throwing(getClass().getName(), "createDatabase", e);
            LOG.severe(HttpClientException.toMessage(e.getMessage(), message, code));
            throw new HttpClientException("Create database failed", message, code, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        // parse JSON data
        @SuppressWarnings("unchecked")
        Map<String, String> dbMap = (Map<String, String>) JSONValue.parse(jsonData);
        validator.validateJavaObject(jsonData, dbMap);
        String dbName = dbMap.get("database");

        return new CreateDatabaseResult(new Database(dbName));
    }

    @Override
    public DeleteDatabaseResult deleteDatabase(DeleteDatabaseRequest request)
            throws ClientException {
        int count = 0;
        DeleteDatabaseResult ret;
        while (true) {
            try {
                ret = doDeleteDatabase(request);
                if (count > 0) {
                    LOG.warning("Retry succeeded.");
                }
                break;
            } catch (ClientException e) {
                // TODO FIXME
                if (count >= getRetryCount()) {
                    LOG.warning("Retry count exceeded limit: " + e.getMessage());
                    throw new ClientException("Retry error", e);
                } else {
                    count++;
                    LOG.warning("It failed. but will be retried: " + e.getMessage());
                    waitRetry(getRetryWaitTime(), count);
                }
            }
        }
        return ret;
    }

    private DeleteDatabaseResult doDeleteDatabase(DeleteDatabaseRequest request)
            throws ClientException {
        request.setCredentials(getConfig().getCredentials());
        validator.validateCredentials(this, request);

        String jsonData = null;
        int code = 0;
        String message = null;
        try {
            conn = createConnection();

            // send request
            String path = String.format(HttpURL.V3_DATABASE_DELETE,
                    HttpConnectionImpl.e(request.getDatabaseName()));
            Map<String, String> header = new HashMap<String, String>();
            setUserAgentHeader(header);
            Map<String, String> params = null;
            conn.doPostRequest(request, path, header, params);

            // receive response code
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            if (code != HttpURLConnection.HTTP_OK) {
                String errMessage = conn.getErrorMessage();
                LOG.severe(HttpClientException.toMessage(
                        "Delete database failed", message, code));
                LOG.severe(errMessage);
                throw new HttpClientException("Delete database failed",
                        message + ", detail = " + errMessage, code);
            }

            // receive response body
            jsonData = conn.getResponseBody();
            validator.validateJSONData(jsonData);
        } catch (IOException e) {
            LOG.throwing(getClass().getName(), "deleteDatabase", e);
            LOG.severe(HttpClientException.toMessage(e.getMessage(), message, code));
            throw new HttpClientException("Delete database failed", message, code, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        // parse JSON data
        @SuppressWarnings("unchecked")
        Map<String, String> dbMap = (Map<String, String>) JSONValue.parse(jsonData);
        validator.validateJavaObject(jsonData, dbMap);

        return new DeleteDatabaseResult(request.getDatabase());
    }

    @Override
    public ListTablesResult listTables(ListTablesRequest request)
            throws ClientException {
        int count = 0;
        ListTablesResult ret;
        while (true) {
            try {
                ret = doListTables(request);
                if (count > 0) {
                    LOG.warning("Retry succeeded.");
                }
                break;
            } catch (ClientException e) {
                // TODO FIXME
                if (count >= getRetryCount()) {
                    LOG.warning("Retry count exceeded limit: " + e.getMessage());
                    throw new ClientException("Retry error", e);
                } else {
                    count++;
                    LOG.warning("It failed. but will be retried: " + e.getMessage());
                    waitRetry(getRetryWaitTime(), count);
                }
            }
        }
        return ret;
    }

    private ListTablesResult doListTables(ListTablesRequest request)
            throws ClientException {
        // validate request
        if (request.getDatabase() == null) {
            throw new ClientException("database is not specified");
        }

        request.setCredentials(getConfig().getCredentials());
        validator.validateCredentials(this, request);

        String jsonData = null;
        int code = 0;
        String message = null;
        try {
            conn = createConnection();

            // send request
            String path = String.format(HttpURL.V3_TABLE_LIST,
                    HttpConnectionImpl.e(request.getDatabase().getName()));
            Map<String, String> header = new HashMap<String, String>();
            setUserAgentHeader(header);
            Map<String, String> params = null;
            conn.doGetRequest(request, path, header, params);

            // receive response code
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            if (code != HttpURLConnection.HTTP_OK) {
                String errMessage = conn.getErrorMessage();
                LOG.severe(HttpClientException.toMessage(
                        "List tables failed", message, code));
                LOG.severe(errMessage);
                throw new HttpClientException("List tables failed",
                        message + ", detail = " + errMessage, code);
            }

            // receive response body
            jsonData = conn.getResponseBody();
            validator.validateJSONData(jsonData);
        } catch (IOException e) {
            LOG.throwing(getClass().getName(), "listTables", e);
            LOG.severe(HttpClientException.toMessage(e.getMessage(), message, code));
            throw new HttpClientException("List tables failed", message, code, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        @SuppressWarnings("rawtypes")
        Map map = (Map) JSONValue.parse(jsonData);
        validator.validateJavaObject(jsonData, map);
        @SuppressWarnings("unchecked")
        Iterator<Map<String, Object>> tableMapIter =
            ((List<Map<String, Object>>) map.get("tables")).iterator();
        List<TableSummary> tableList = new ArrayList<TableSummary>();
        while (tableMapIter.hasNext()) {
            Map<String, Object> tableMap = tableMapIter.next();
            String name = (String) tableMap.get("name");
            String typeName = (String) tableMap.get("type");
            Long count = (Long) tableMap.get("count");
            String schema = (String) tableMap.get("schema");
            String createdAt = (String) tableMap.get("created_at");
            String updatedAt = (String) tableMap.get("updated_at");
            TableSummary tbl = new TableSummary(request.getDatabase(), name, Table.Type.fromString(typeName),
                    count, schema, createdAt, updatedAt);
            tableList.add(tbl);
        }

        ListTables<TableSummary> tables = new ListTables<TableSummary>(tableList);
        return new ListTablesResult(request.getDatabase(), tables);
    }

    @Override
    public CreateTableResult createTable(CreateTableRequest request)
            throws ClientException {
        int count = 0;
        CreateTableResult ret;
        while (true) {
            try {
                ret = doCreateTable(request);
                if (count > 0) {
                    LOG.warning("Retry succeeded.");
                }
                break;
            } catch (ClientException e) {
                if (e instanceof HttpClientException) {
                    LOG.log(Level.WARNING, e.getMessage(), e);
                    HttpClientException ex = (HttpClientException) e;
                    int statusCode = ex.getResponseCode();
                    if (statusCode == 401) {
                        throw new AuthenticationException("Authentication failed", e.getMessage());
                    }
                    if (statusCode == 404) {
                        String name = request.getTableName();
                        throw new NotFoundException("Table not found: " + name, e.getMessage());
                    }
                    if (statusCode == 409) {
                        String name = request.getTableName();
                        throw new ConflictException("Table alraedy exists: " + name, e.getMessage());
                    }
                }

                if (count >= getRetryCount()) {
                    LOG.warning("Retry count exceeded limit: " + e.getMessage());
                    throw new ClientException("Retry error", e);
                } else {
                    count++;
                    LOG.warning("It failed. but will be retried: " + e.getMessage());
                    waitRetry(getRetryWaitTime(), count);
                }
            }
        }
        return ret;
    }

    private CreateTableResult doCreateTable(CreateTableRequest request)
            throws ClientException {
        validator.validateTableName(request.getTableName());
        request.setCredentials(getConfig().getCredentials());
        validator.validateCredentials(this, request);
        final boolean isItemTable = request instanceof CreateItemTableRequest;

        String jsonData = null;
        int code = 0;
        String message = null;
        String tableType = request.getTable().getType().type();
        try {
            conn = createConnection();

            // send request
            String path = String.format(HttpURL.V3_TABLE_CREATE,
                    HttpConnectionImpl.e(request.getDatabase().getName()),
                    HttpConnectionImpl.e(request.getTableName()),
                    HttpConnectionImpl.e(request.getTable().getType().type()));
            Map<String, String> header = new HashMap<String, String>();
            setUserAgentHeader(header);
            Map<String, String> params = null;
            if (isItemTable) {
                ItemTable it = (ItemTable) request.getTable();
                params = new HashMap<String, String>();
                //'primary_key' => primary_key
                params.put("primary_key", it.getPrimaryKey());
                // 'primary_key_type' => primary_key_type
                params.put("primary_key_type", it.getPrimaryKeyType().type());
            }
            conn.doPostRequest(request, path, header, params);

            // receive response code
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            if (code != HttpURLConnection.HTTP_OK) {
                String errMessage = conn.getErrorMessage();
                LOG.severe(HttpClientException.toMessage(
                        String.format("Create %s table failed", tableType), message, code));
                LOG.severe(errMessage);
                throw new HttpClientException(
                        String.format("Create %s table failed", tableType), message + ", detail = " + errMessage, code);
            }

            // receive response body
            jsonData = conn.getResponseBody();
            validator.validateJSONData(jsonData);
        } catch (IOException e) {
            LOG.throwing(getClass().getName(), isItemTable ? "createItemTable" : "createLogTable", e);
            LOG.severe(HttpClientException.toMessage(e.getMessage(), message, code));
            throw new HttpClientException(
                    String.format("Create %s table failed", tableType), message, code, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        // parse JSON data {"table":"test01","type":"item","database":"mugadb"}
        @SuppressWarnings("unchecked")
        Map<String, String> tableMap = (Map<String, String>) JSONValue.parse(jsonData);
        validator.validateJavaObject(jsonData, tableMap);
        String tableName = tableMap.get("table");
        Table table;
        if (!isItemTable) {
            table = new LogTable(request.getDatabase(), tableName);
        } else {
            ItemTable tbl = (ItemTable) request.getTable();
            table = new ItemTable(request.getDatabase(), tableName,
                    tbl.getPrimaryKey(), tbl.getPrimaryKeyType());
        }

        return new CreateTableResult(table);
    }

    @Override
    public RenameTableResult renameTable(RenameTableRequest request)
            throws ClientException {
        int count = 0;
        RenameTableResult ret;
        while (true) {
            try {
                ret = doRenameTable(request);
                if (count > 0) {
                    LOG.warning("Retry succeeded.");
                }
                break;
            } catch (ClientException e) {
                // TODO FIXME
                if (count >= getRetryCount()) {
                    LOG.warning("Retry count exceeded limit: " + e.getMessage());
                    throw new ClientException("Retry error", e);
                } else {
                    count++;
                    LOG.warning("It failed. but will be retried: " + e.getMessage());
                    waitRetry(getRetryWaitTime(), count);
                }
            }
        }
        return ret;
    }

    private RenameTableResult doRenameTable(RenameTableRequest request)
            throws ClientException {
        request.setCredentials(getConfig().getCredentials());
        validator.validateCredentials(this, request);

        String jsonData = null;
        int code = 0;
        String message = null;
        try {
            conn = createConnection();

            // send request
            String path = String.format(HttpURL.V3_TABLE_RENAME,
                    HttpConnectionImpl.e(request.getDatabaseName()),
                    HttpConnectionImpl.e(request.getOrigTableName()),
                    HttpConnectionImpl.e(request.getNewTableName()));
            Map<String, String> header = new HashMap<String, String>();
            setUserAgentHeader(header);
            Map<String, String> params = new HashMap<String, String>();
            params.put("overwrite", "" + request.getOverwrite());
            conn.doPostRequest(request, path, header, params);

            // receive response code
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            if (code != HttpURLConnection.HTTP_OK) {
                String errMessage = conn.getErrorMessage();
                checkTrial(code, errMessage);
                LOG.severe(HttpClientException.toMessage(
                        "Rename table failed", message, code));
                LOG.severe(errMessage);
                throw new HttpClientException("Rename table failed",
                        message + ", detail = " + errMessage, code);
            }

            // receive response body
            jsonData = conn.getResponseBody();
            validator.validateJSONData(jsonData);
        } catch (IOException e) {
            LOG.throwing(getClass().getName(), "renameTable", e);
            LOG.severe(HttpClientException.toMessage(e.getMessage(), message, code));
            throw new HttpClientException("Rename table failed", message, code, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        // {"database":"mugadb","table":"test04","type":"log"}
        Map map = (Map<String, String>) JSONValue.parse(jsonData);
        validator.validateJavaObject(jsonData, map);

        return new RenameTableResult(request.getDatabaseName(),
                request.getOrigTableName(), request.getNewTableName());
    }

    @Override
    public SwapTableResult swapTable(SwapTableRequest request)
            throws ClientException {
        int count = 0;
        SwapTableResult ret;
        while (true) {
            try {
                ret = doSwapTable(request);
                if (count > 0) {
                    LOG.warning("Retry succeeded.");
                }
                break;
            } catch (ClientException e) {
                // TODO FIXME
                if (count >= getRetryCount()) {
                    LOG.warning("Retry count exceeded limit: " + e.getMessage());
                    throw new ClientException("Retry error", e);
                } else {
                    count++;
                    LOG.warning("It failed. but will be retried: " + e.getMessage());
                    waitRetry(getRetryWaitTime(), count);
                }
            }
        }
        return ret;
    }

    private SwapTableResult doSwapTable(SwapTableRequest request)
            throws ClientException {
        request.setCredentials(getConfig().getCredentials());
        validator.validateCredentials(this, request);

        String jsonData = null;
        int code = 0;
        String message = null;
        try {
            conn = createConnection();

            // send request
            String path = String.format(HttpURL.V3_TABLE_SWAP,
                    HttpConnectionImpl.e(request.getDatabaseName()),
                    HttpConnectionImpl.e(request.getTableName1()),
                    HttpConnectionImpl.e(request.getTableName2()));
            Map<String, String> header = new HashMap<String, String>();
            setUserAgentHeader(header);
            Map<String, String> params = null;
            conn.doPostRequest(request, path, header, params);

            // receive response code
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            if (code != HttpURLConnection.HTTP_OK) {
                String errMessage = conn.getErrorMessage();
                checkTrial(code, errMessage);
                LOG.severe(HttpClientException.toMessage(
                        "Swap table failed", message, code));
                LOG.severe(errMessage);
                throw new HttpClientException("Swap table failed",
                        message + ", detail = " + errMessage, code);
            }

            // receive response body
            jsonData = conn.getResponseBody();
            validator.validateJSONData(jsonData);
        } catch (IOException e) {
            LOG.throwing(getClass().getName(), "swapTable", e);
            LOG.severe(HttpClientException.toMessage(e.getMessage(), message, code));
            throw new HttpClientException("Swap table failed", message, code, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        // {"database":"mugadb","table1":"test04","table2":"test04_1342157288"}
        Map map = (Map<String, String>) JSONValue.parse(jsonData);
        validator.validateJavaObject(jsonData, map);

        return new SwapTableResult(request.getDatabaseName(),
                request.getTableName1(), request.getTableName2());
    }

    @Override
    public DeleteTableResult deleteTable(DeleteTableRequest request)
            throws ClientException {
        int count = 0;
        DeleteTableResult ret;
        while (true) {
            try {
                ret = doDeleteTable(request);
                if (count > 0) {
                    LOG.warning("Retry succeeded.");
                }
                break;
            } catch (ClientException e) {
                // TODO FIXME
                if (count >= getRetryCount()) {
                    LOG.warning("Retry count exceeded limit: " + e.getMessage());
                    throw new ClientException("Retry error", e);
                } else {
                    count++;
                    LOG.warning("It failed. but will be retried: " + e.getMessage());
                    waitRetry(getRetryWaitTime(), count);
                }
            }
        }
        return ret;
    }

    private DeleteTableResult doDeleteTable(DeleteTableRequest request)
            throws ClientException {
        request.setCredentials(getConfig().getCredentials());
        validator.validateCredentials(this, request);

        String jsonData = null;
        int code = 0;
        String message = null;
        try {
            conn = createConnection();

            // send request
            String path = String.format(HttpURL.V3_TABLE_DELETE,
                    HttpConnectionImpl.e(request.getDatabase().getName()),
                    HttpConnectionImpl.e(request.getTable().getName()));
            Map<String, String> header = new HashMap<String, String>();
            setUserAgentHeader(header);
            Map<String, String> params = null;
            conn.doPostRequest(request, path, header, params);

            // receive response code
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            if (code != HttpURLConnection.HTTP_OK) {
                String errMessage = conn.getErrorMessage();
                checkTrial(code, errMessage);
                LOG.severe(HttpClientException.toMessage(
                        "Delete table failed", message, code));
                LOG.severe(errMessage);
                throw new HttpClientException("Delete table failed",
                        message + ", detail = " + errMessage, code);
            }

            // receive response body
            jsonData = conn.getResponseBody();
            validator.validateJSONData(jsonData);
        } catch (IOException e) {
            LOG.throwing(getClass().getName(), "deleteTable", e);
            LOG.severe(HttpClientException.toMessage(e.getMessage(), message, code));
            throw new HttpClientException("Delete table failed", message, code, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        // parse JSON data
        @SuppressWarnings("unchecked")
        Map<String, String> tableMap = (Map<String, String>) JSONValue.parse(jsonData);
        validator.validateJavaObject(jsonData, tableMap);
        String tableName = tableMap.get("table");

        return new DeleteTableResult(request.getDatabase(), tableName);
    }

    @Override
    public DeletePartialTableResult deletePartialTable(DeletePartialTableRequest request)
            throws ClientException {
        int count = 0;
        DeletePartialTableResult ret;
        while (true) {
            try {
                ret = doDeletePartialTable(request);
                if (count > 0) {
                    LOG.warning("Retry succeeded.");
                }
                break;
            } catch (ClientException e) {
                // TODO FIXME
                if (count >= getRetryCount()) {
                    LOG.warning("Retry count exceeded limit: " + e.getMessage());
                    throw new ClientException("Retry error", e);
                } else {
                    count++;
                    LOG.warning("It failed. but will be retried: " + e.getMessage());
                    waitRetry(getRetryWaitTime(), count);
                }
            }
        }
        return ret;
    }

    private DeletePartialTableResult doDeletePartialTable(DeletePartialTableRequest request)
            throws ClientException {
        request.setCredentials(getConfig().getCredentials());
        validator.validateCredentials(this, request);

        String jsonData = null;
        int code = 0;
        String message = null;
        try {
            conn = createConnection();

            // send request
            String path = String.format(HttpURL.V3_TABLE_DELETE_PARTIAL,
                    HttpConnectionImpl.e(request.getDatabase().getName()),
                    HttpConnectionImpl.e(request.getTable().getName()));
            Map<String, String> header = new HashMap<String, String>();
            setUserAgentHeader(header);
            Map<String, String> params = new HashMap<String, String>();
            params.put("from", "" + request.getFrom());
            params.put("to", "" + request.getTo());
            conn.doPostRequest(request, path, header, params);

            // receive response code
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            if (code != HttpURLConnection.HTTP_OK) {
                String errMessage = conn.getErrorMessage();
                checkTrial(code, errMessage);
                LOG.severe(HttpClientException.toMessage(
                        "Delete partial table failed", message, code));
                LOG.severe(errMessage);
                throw new HttpClientException("Delete partial table failed",
                        message + ", detail = " + errMessage, code);
            }

            // receive response body
            jsonData = conn.getResponseBody();
            validator.validateJSONData(jsonData);
        } catch (IOException e) {
            LOG.throwing(getClass().getName(), "deletePartialTable", e);
            LOG.severe(HttpClientException.toMessage(e.getMessage(), message, code));
            throw new HttpClientException("Delete partial table failed", message, code, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        // parse JSON data
        @SuppressWarnings("unchecked")
        Map<String, Object> jobMap = (Map<String, Object>) JSONValue.parse(jsonData);
        validator.validateJavaObject(jsonData, jobMap);
        String jobID = getJobID(jobMap);
        Job job = new Job(jobID, Job.Type.MAPRED, request.getDatabase(), null, null);

        return new DeletePartialTableResult(job);
    }

    @Override
    public TableSchema showTableSchema(String database, String table)
            throws ClientException {
        List<TableSummary> summaries = listTables(
                new ListTablesRequest(new Database(database))).getTables();

        TableSummary summary = null;
        for (TableSummary t : summaries) {
            if (t.getName().equals(table)) {
                summary = t;
            }
        }

        if (summary == null) {
            throw new ClientException("Not such table " + table);
        }

        String schemaString = summary.getSchema();
        List schema = (List) JSONValue.parse(schemaString);
        if (schema == null || schema.isEmpty()) {
            return new TableSchema(new Table(new Database(database), table), null);
        } else {
            List<String> pairs = new ArrayList<String>();
            for (int i = 0; i < schema.size(); i++) {
                List<String> pair = (List<String>) schema.get(i);
                pairs.add(pair.get(0) + ":" + pair.get(1));
            }
            return new TableSchema(new Table(new Database(database), table), pairs);
        }
    }

    @Override
    public SetTableSchemaResult setTableSchema(SetTableSchemaRequest request)
            throws ClientException {
        int count = 0;
        SetTableSchemaResult ret;
        while (true) {
            try {
                ret = doSetTableSchema(request);
                if (count > 0) {
                    LOG.warning("Retry succeeded.");
                }
                break;
            } catch (ClientException e) {
                // TODO FIXME
                if (count >= getRetryCount()) {
                    LOG.warning("Retry count exceeded limit: " + e.getMessage());
                    throw new ClientException("Retry error", e);
                } else {
                    count++;
                    LOG.warning("It failed. but will be retried: " + e.getMessage());
                    waitRetry(getRetryWaitTime(), count);
                }
            }
        }
        return ret;
    }

    private SetTableSchemaResult doSetTableSchema(SetTableSchemaRequest request)
            throws ClientException {
        request.setCredentials(getConfig().getCredentials());

        String jsonData = null;
        int code = 0;
        String message = null;
        try {
            conn = createConnection();

            // send request
            String path = String.format(HttpURL.V3_SCHEMA_UPDATE,
                    HttpConnectionImpl.e(request.getDatabaseName()),
                    HttpConnectionImpl.e(request.getTableName()));
            Map<String, String> header = new HashMap<String, String>();
            setUserAgentHeader(header);
            Map<String, String> params = new HashMap<String, String>();
            params.put("schema", HttpConnectionImpl.e(request.getJSONString()));
            conn.doPostRequest(request, path, header, params);

            // receive response code
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            if (code != HttpURLConnection.HTTP_OK) {
                String errMessage = conn.getErrorMessage();
                checkTrial(code, errMessage);
                LOG.severe(HttpClientException.toMessage(
                        "Set table schema failed", message, code));
                LOG.severe(errMessage);
                throw new HttpClientException("Set table schema failed",
                        message + ", detail = " + errMessage, code);
            }

            // receive response body
            jsonData = conn.getResponseBody();
            validator.validateJSONData(jsonData);
        } catch (IOException e) {
            LOG.throwing(getClass().getName(), "setTableSchema", e);
            LOG.severe(HttpClientException.toMessage(e.getMessage(), message, code));
            throw new HttpClientException("Set table schema failed", message, code, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        //json data: {"table":"sesstest","type":"log","database":"mugadb"}
        // parse JSON data
        @SuppressWarnings("unchecked")
        Map<String, String> tblMap = (Map<String, String>) JSONValue.parse(jsonData);
        validator.validateJavaObject(jsonData, tblMap);
        String dbName = tblMap.get("database");
        String tblName = tblMap.get("table");

        return new SetTableSchemaResult(request.getTableSchema());
    }

    @Override
    public TableSchema addTableSchema(String database, String table, List<String> addedPairList)
            throws ClientException {
        TableSchema current = showTableSchema(database, table);
        List<TableSchema.Pair> currentPairs = current.getPairsOfColsAndTypes();

        // if addedPairs is null or empty, return currentPairs.
        if (addedPairList == null || addedPairList.isEmpty()) {
            return current;
        }

        List<TableSchema.Pair> addedPairs = TableSchema.parsePairs(addedPairList);

        // if currentPairs is null or empty, addedPairs are set to currentPairs directly.
        if (currentPairs == null || currentPairs.isEmpty()) {
            current.setPairs(addedPairs);
        } else {
            for (TableSchema.Pair addedPair : addedPairs) {
                String addedCol = addedPair.getColumnName();
                for (int i = 0; i < currentPairs.size(); i++) {
                    if (currentPairs.get(i).getColumnName().equals(addedCol)) {
                        currentPairs.remove(i);
                        break;
                    }
                }
            }
            currentPairs.addAll(addedPairs);
        }

        SetTableSchemaResult result = setTableSchema(new SetTableSchemaRequest(current));
        return result.getTableSchema();
    }

    @Override
    public TableSchema removeTableSchema(String database, String table, List<String> removedColList)
            throws ClientException {
        TableSchema current = showTableSchema(database, table);
        List<TableSchema.Pair> currentPairs = current.getPairsOfColsAndTypes();

        // if currentPairs or addedPairs is null or empty, return currentPairs.
        if (removedColList == null || removedColList.isEmpty() ||
                currentPairs == null || currentPairs.isEmpty()) {
            return current;
        }

        for (String removedCol : removedColList) {
            for (int i = 0; i < currentPairs.size(); i++) {
                if (currentPairs.get(i).getColumnName().equals(removedCol)) {
                    currentPairs.remove(i);
                    break;
                }
            }
        }

        SetTableSchemaResult result = setTableSchema(new SetTableSchemaRequest(current));
        return result.getTableSchema();
    }

    @Override
    public ImportResult importData(ImportRequest request) throws ClientException {
        int count = 0;
        ImportResult ret;
        while (true) {
            try {
                ret = doImportData(request);
                if (count > 0) {
                    LOG.warning("Retry succeeded.");
                }
                break;
            } catch (ClientException e) {
                if (e instanceof HttpClientException) {
                    HttpClientException ex = (HttpClientException) e;
                    int statusCode = ex.getResponseCode();
                    if (statusCode == 401) {
                     // If authentication failed 401, it doesn't retry.
                        LOG.log(Level.WARNING, e.getMessage(), e);
                        throw new AuthenticationException("Authentication failed", e.getMessage());
                    }
                }

                // TODO FIXME
                if (count >= getRetryCount()) {
                    LOG.warning("Retry count exceeded limit: " + e.getMessage());
                    throw new ClientException("Retry error", e);
                } else {
                    count++;
                    LOG.warning("It failed. but will be retried: " + e.getMessage());
                    waitRetry(getRetryWaitTime(), count);
                }
            }
        }
        return ret;
    }

    private ImportResult doImportData(ImportRequest request) throws ClientException {
        request.setCredentials(getConfig().getCredentials());
        validator.validateCredentials(this, request);

        String jsonData = null;
        int code = 0;
        String message = null;
        try {
            conn = createConnection();

            // send request
            String path = String.format(HttpURL.V3_TABLE_IMPORT,
                    HttpConnectionImpl.e(request.getTable().getDatabase().getName()),
                    HttpConnectionImpl.e(request.getTable().getName()));
            Map<String, String> header = new HashMap<String, String>();
            setUserAgentHeader(header);
            conn.doPutRequest(request, path, header, request.getBytes());

            // receive response code
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            if (code != HttpURLConnection.HTTP_OK) {
                String errMessage = conn.getErrorMessage();
                checkTrial(code, errMessage);
                LOG.severe(HttpClientException.toMessage(
                        "Import data failed", message, code));
                LOG.severe(errMessage);
                throw new HttpClientException("Import data failed",
                        message + ", detail = " + errMessage, code);
            }

            // receive response body
            jsonData = conn.getResponseBody();
            validator.validateJSONData(jsonData);
        } catch (IOException e) {
            LOG.throwing(getClass().getName(), "importData", e);
            LOG.severe(HttpClientException.toMessage(e.getMessage(), message, code));
            throw new HttpClientException("Import data failed", message, code, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        // parse JSON data
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) JSONValue.parse(jsonData);
        validator.validateJavaObject(jsonData, map);

        double elapsedTime = getTime(map, "elapsed_time");

        return new ImportResult(request.getTable(), elapsedTime);
    }

    @Override
    public ExportResult exportData(ExportRequest request) throws ClientException {
        int count = 0;
        ExportResult ret;
        while (true) {
            try {
                ret = doExportData(request);
                if (count > 0) {
                    LOG.warning("Retry succeeded.");
                }
                break;
            } catch (ClientException e) {
                // TODO FIXME
                if (count >= getRetryCount()) {
                    LOG.warning("Retry count exceeded limit: " + e.getMessage());
                    throw new ClientException("Retry error", e);
                } else {
                    count++;
                    LOG.warning("It failed. but will be retried: " + e.getMessage());
                    waitRetry(getRetryWaitTime(), count);
                }
            }
        }
        return ret;
    }

    private ExportResult doExportData(ExportRequest request) throws ClientException {
        request.setCredentials(getConfig().getCredentials());
        validator.validateCredentials(this, request);

        String jsonData = null;
        int code = 0;
        String message = null;
        try {
            conn = createConnection();

            // send request
            String path = String.format(HttpURL.V3_EXPORTJOB_SUBMIT,
                    HttpConnectionImpl.e(request.getDatabase().getName()), HttpConnectionImpl.e(request.getTable().getName()));
            Map<String, String> header = new HashMap<String, String>();
            setUserAgentHeader(header);
            Map<String, String> params = new HashMap<String, String>();
            if (request.getAccessKeyID() != null) {
                params.put("access_key_id", HttpConnectionImpl.e(request.getAccessKeyID()));
            } else {
                throw new IllegalArgumentException("access_key_id is null");
            }
            if (request.getSecretAccessKey() != null) {
                params.put("secret_access_key", HttpConnectionImpl.e(request.getSecretAccessKey()));
            } else {
                throw new IllegalArgumentException("secret_access_key is null");
            }
            if (request.getStorageType() != null) {
                params.put("storage_type", request.getStorageType());
            } else {
                throw new IllegalArgumentException("storage_type is null");
            }
            if (request.getBucketName() != null) {
                params.put("bucket", request.getBucketName());
            } else {
                throw new IllegalArgumentException("bucket is null");
            }
            if (request.getFileFormat() != null) {
                params.put("file_format", request.getFileFormat());
            } else {
                throw new IllegalArgumentException("file_format is null");
            }
            if (request.getFrom() != null) {
                params.put("from", request.getFrom());
            }
            if (request.getTo() != null) {
                params.put("to", request.getTo());
            }
            conn.doPostRequest(request, path, header, params);

            // receive response code
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            if (code != HttpURLConnection.HTTP_OK) {
                String errMessage = conn.getErrorMessage();
                checkTrial(code, errMessage);
                LOG.severe(HttpClientException.toMessage(
                        "Export failed", message, code));
                LOG.severe(errMessage);
                throw new HttpClientException("Export failed",
                        message + ", detail = " + errMessage, code);
            }

            // receive response body
            jsonData = conn.getResponseBody();
            validator.validateJSONData(jsonData);
        } catch (IOException e) {
            LOG.throwing(getClass().getName(), "exportData", e);
            LOG.severe(HttpClientException.toMessage(e.getMessage(), message, code));
            throw new HttpClientException("Export failed", message, code, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        // parse JSON data
        @SuppressWarnings("unchecked")
        Map<String, Object> jobMap = (Map<String, Object>) JSONValue.parse(jsonData);
        validator.validateJavaObject(jsonData, jobMap);
        String jobID = getJobID(jobMap);
        Job job = new Job(jobID, Job.Type.MAPRED, request.getDatabase(), null, null);

        return new ExportResult(job);
    }

    @Override
    public SubmitJobResult submitJob(SubmitJobRequest request)
            throws ClientException {
        int count = 0;
        SubmitJobResult ret;
        while (true) {
            try {
                ret = doSubmitJob(request);
                if (count > 0) {
                    LOG.warning("Retry succeeded.");
                }
                break;
            } catch (TrialException e) {
                throw e;
            } catch (ClientException e) {
                // TODO FIXME
                if (count >= getRetryCount()) {
                    LOG.warning("Retry count exceeded limit: " + e.getMessage());
                    throw new ClientException("Retry error", e);
                } else {
                    count++;
                    LOG.warning("It failed. but will be retried: " + e.getMessage());
                    waitRetry(getRetryWaitTime(), count);
                }
            }
        }
        return ret;
    }

    private SubmitJobResult doSubmitJob(SubmitJobRequest request)
            throws ClientException {
        request.setCredentials(getConfig().getCredentials());
        validator.validateCredentials(this, request);
        request.validate();

        String jsonData = null;
        int code = 0;
        String message = null;
        try {
            conn = createConnection();

            // send request
            String path = String.format(HttpURL.V3_JOB_SUBMIT,
                    HttpConnectionImpl.e(request.getJob().getType().type()),
                    HttpConnectionImpl.e(request.getDatabase().getName()));
            Map<String, String> header = new HashMap<String, String>();
            setUserAgentHeader(header);
            Map<String, String> params = new HashMap<String, String>();
            params.put("query", HttpConnectionImpl.e(request.getJob().getQuery()));
            params.put("version", "0.7");
            if (request.getJob().getResultTable() != null) {
                // result table is not required
                params.put("result", HttpConnectionImpl.e(request.getJob().getResultTable()));
            }
            params.put("priority", "" + request.getJob().getPriority().getPriority());
            params.put("retry_limit", "" + request.getJob().getRetryLimit());
            if (request.getJob().getPoolName() != null) {
                params.put("pool_name", "" + request.getJob().getPoolName());
            }
            conn.doPostRequest(request, path, header, params);

            // receive response code
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            if (code != HttpURLConnection.HTTP_OK) {
                String errMessage = conn.getErrorMessage();
                checkTrial(code, errMessage);
                LOG.severe(HttpClientException.toMessage(
                        "Submit job failed", message, code));
                LOG.severe(errMessage);
                throw new HttpClientException("Submit job failed",
                        message + ", detail = " + errMessage, code);
            }

            // receive response body
            jsonData = conn.getResponseBody();
            validator.validateJSONData(jsonData);
        } catch (IOException e) {
            LOG.throwing(getClass().getName(), "submitJob", e);
            // Update responce code
            if(conn != null) {
                try {
                    code = conn.getResponseCode();
                    message = conn.getResponseMessage();
                }
                catch(IOException e2) {
                    // failed to get responce code, etc.
                }
            }
            LOG.severe(HttpClientException.toMessage(e.getMessage(), message, code));
            throw new HttpClientException("Submit job failed", message, code, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        // parse JSON data
        @SuppressWarnings("unchecked")
        Map<String, Object> jobMap = (Map<String, Object>) JSONValue.parse(jsonData);
        validator.validateJavaObject(jsonData, jobMap);
        String jobID = getJobID(jobMap);
        Job job = request.getJob();
        job.setJobID(jobID);

        return new SubmitJobResult(job);
    }

    private void checkTrial(int code, String message) throws ClientException
    {
        if (code == 422 && message.contains("Your Presto trial has expired!"))
            throw new TrialException("Your Presto trial has expired! Contact support to add Presto to your plan.");
    }

    @Override
    public ListJobsResult listJobs(ListJobsRequest request) throws ClientException {
        int count = 0;
        ListJobsResult ret;
        while (true) {
            try {
                ret = doListJobs(request);
                if (count > 0) {
                    LOG.warning("Retry succeeded.");
                }
                break;
            } catch (ClientException e) {
                // TODO FIXME
                if (count >= getRetryCount()) {
                    LOG.warning("Retry count exceeded limit: " + e.getMessage());
                    throw new ClientException("Retry error", e);
                } else {
                    count++;
                    LOG.warning("It failed. but will be retried: " + e.getMessage());
                    waitRetry(getRetryWaitTime(), count);
                }
            }
        }
        return ret;
    }

    private ListJobsResult doListJobs(ListJobsRequest request) throws ClientException {
        request.setCredentials(getConfig().getCredentials());
        validator.validateCredentials(this, request);

        String jsonData = null;
        int code = 0;
        String message = null;
        try {
            conn = createConnection();

            // send request
            String path = String.format(HttpURL.V3_JOB_LIST);
            Map<String, String> header = new HashMap<String, String>();
            setUserAgentHeader(header);
            Map<String, String> params = new HashMap<String, String>();
            long from = request.getFrom();
            if (from < 0) {
                throw new IllegalArgumentException();
            } else {
                params.put("from", "" + from);
            }
            long to = request.getTo();
            if (to < 0) {
                throw new IllegalArgumentException();
            } else if (to > 0) {
                params.put("to", "" + to);
            }
            conn.doGetRequest(request, path, header, params);

            // receive response code
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            if (code != HttpURLConnection.HTTP_OK) {
                String errMessage = conn.getErrorMessage();
                checkTrial(code, errMessage);
                LOG.severe(HttpClientException.toMessage(
                        "List jobs failed", message, code));
                LOG.severe(errMessage);
                throw new HttpClientException("List jobs failed",
                        message + ", detail = " + errMessage, code);
            }

            // receive response body
            jsonData = conn.getResponseBody();
            validator.validateJSONData(jsonData);
        } catch (IOException e) {
            LOG.throwing(getClass().getName(), "listJobs", e);
            LOG.severe(HttpClientException.toMessage(e.getMessage(), message, code));
            throw new HttpClientException("List jobs failed", message, code, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        @SuppressWarnings("rawtypes")
        Map map = (Map) JSONValue.parse(jsonData);
        validator.validateJavaObject(jsonData, map);

        long count = (Long) map.get("count");
        long from = (Long) map.get("from");
        long to = (Long) map.get("to");
        @SuppressWarnings("unchecked")
        Iterator<Map<String, Object>> jobMapIter =
            ((List<Map<String, Object>>) map.get("jobs")).iterator();
        List<JobSummary> jobs = new ArrayList<JobSummary>();
        while (jobMapIter.hasNext()) {
            Map<String, Object> jobMap = jobMapIter.next();
            Job.Type type = Job.toType((String) jobMap.get("type"));
            String jobID = getJobID(jobMap);
            JobSummary.Status status = JobSummary.toStatus((String) jobMap.get("status"));
            String startAt = (String) jobMap.get("start_at");
            String endAt = (String) jobMap.get("end_at");
            String query = (String) jobMap.get("query");
            String result = (String) jobMap.get("result");
            JobSummary job = new JobSummary(jobID, type, null, null, result,
                    status, startAt, endAt, query, null);
            jobs.add(job);
        }

        return new ListJobsResult(new ListJobs<JobSummary>(count, from, to, jobs));
    }

    @Override
    public KillJobResult killJob(KillJobRequest request)
            throws ClientException {
        int count = 0;
        KillJobResult ret;
        while (true) {
            try {
                ret = doKillJob(request);
                if (count > 0) {
                    LOG.warning("Retry succeeded.");
                }
                break;
            } catch (ClientException e) {
                // TODO FIXME
                if (count >= getRetryCount()) {
                    LOG.warning("Retry count exceeded limit: " + e.getMessage());
                    throw new ClientException("Retry error", e);
                } else {
                    count++;
                    LOG.warning("It failed. but will be retried: " + e.getMessage());
                    waitRetry(getRetryWaitTime(), count);
                }
            }
        }
        return ret;
    }

    private KillJobResult doKillJob(KillJobRequest request) throws ClientException {
        request.setCredentials(getConfig().getCredentials());
        validator.validateCredentials(this, request);

        String jsonData = null;
        int code = 0;
        String message = null;
        try {
            conn = createConnection();

            // send request
            String path = String.format(HttpURL.V3_JOB_KILL,
                    HttpConnectionImpl.e(request.getJob().getJobID()));
            Map<String, String> header = new HashMap<String, String>();
            setUserAgentHeader(header);
            Map<String, String> params = null;
            conn.doPostRequest(request, path, header, params);

            // receive response code
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            if (code != HttpURLConnection.HTTP_OK) {
                String errMessage = conn.getErrorMessage();
                checkTrial(code, errMessage);
                LOG.severe(HttpClientException.toMessage(
                        "Kill job failed", message, code));
                LOG.severe(errMessage);
                throw new HttpClientException("Kill job failed",
                        message + ", detail = " + errMessage, code);
            }

            // receive response body
            jsonData = conn.getResponseBody();
            validator.validateJSONData(jsonData);
        } catch (IOException e) {
            LOG.throwing(getClass().getName(), "killJob", e);
            LOG.severe(HttpClientException.toMessage(e.getMessage(), message, code));
            throw new HttpClientException("Kill job failed", message, code, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        // parse JSON data
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) JSONValue.parse(jsonData);
        validator.validateJavaObject(jsonData, map);
        JobSummary.Status status = JobSummary.toStatus((String) map.get("former_status"));
        String jobID = getJobID(map);

        return new KillJobResult(jobID, status);
    }

    @Override
    public ShowJobResult showJob(ShowJobRequest request)
            throws ClientException {
        int count = 0;
        ShowJobResult ret;
        while (true) {
            try {
                ret = doShowJob(request);
                if (count > 0) {
                    LOG.warning("Retry succeeded.");
                }
                break;
            } catch (ClientException e) {
                // TODO FIXME
                if (count >= getRetryCount()) {
                    LOG.warning("Retry count exceeded limit: " + e.getMessage());
                    throw new ClientException("Retry error", e);
                } else {
                    count++;
                    LOG.warning("It failed. but will be retried: " + e.getMessage());
                    waitRetry(getRetryWaitTime(), count);
                }
            }
        }
        return ret;
    }

    private ShowJobResult doShowJob(ShowJobRequest request)
            throws ClientException {
        request.setCredentials(getConfig().getCredentials());
        validator.validateCredentials(this, request);

        String jsonData = null;
        int code = 0;
        String message = null;
        try {
            conn = createConnection();

            // send request
            String path = String.format(HttpURL.V3_JOB_SHOW,
                    HttpConnectionImpl.e(request.getJob().getJobID()));
            Map<String, String> header = new HashMap<String, String>();
            setUserAgentHeader(header);
            Map<String, String> params = null;
            conn.doGetRequest(request, path, header, params);

            // receive response code
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            if (code != HttpURLConnection.HTTP_OK) {
                String errMessage = conn.getErrorMessage();
                checkTrial(code, errMessage);
                LOG.severe(HttpClientException.toMessage(
                        "Show jobs failed", message, code));
                LOG.severe(errMessage);
                throw new HttpClientException("Show jobs failed",
                        message + ", detail = " + errMessage, code);
            }

            // receive response body
            jsonData = conn.getResponseBody();
            validator.validateJSONData(jsonData);
        } catch (IOException e) {
            LOG.throwing(getClass().getName(), "showJob", e);
            LOG.severe(HttpClientException.toMessage(e.getMessage(), message, code));
            throw new HttpClientException("Show jobs failed", message, code, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> jobMap =
            (Map<String, Object>) JSONValue.parse(jsonData);
        validator.validateJavaObject(jsonData, jobMap);

        String jobID = getJobID(jobMap);
        Job.Type type = Job.toType((String) jobMap.get("type"));
        Database database = new Database((String) jobMap.get("database"));
        String url = (String) jobMap.get("url");
        JobSummary.Status status = JobSummary.toStatus((String) jobMap.get("status"));
        String start_at = (String) jobMap.get("start_at");
        String end_at = (String) jobMap.get("end_at");
        String query = (String) jobMap.get("query");
        String result = (String) jobMap.get("result");
        String resultSchema = (String) jobMap.get("hive_result_schema");
        Map debugMap = (Map) jobMap.get("debug");
        JobSummary.Debug debug = new JobSummary.Debug(
                (String) debugMap.get("cmdout"),
                (String) debugMap.get("stderr"));
        // TODO different object from request's one
        JobSummary job = new JobSummary(jobID, type, database, url, result,
                status, start_at, end_at, query, resultSchema, debug);

        return new ShowJobResult(job);
    }

    @Override
    public ShowJobStatusResult showJobStatus(ShowJobStatusRequest request)
            throws ClientException {
        int count = 0;
        ShowJobStatusResult ret;
        while (true) {
            try {
                ret = doShowJobStatus(request);
                if (count > 0) {
                    LOG.warning("Retry succeeded.");
                }
                break;
            } catch (ClientException e) {
                // TODO FIXME
                if (count >= getRetryCount()) {
                    LOG.warning("Retry count exceeded limit: " + e.getMessage());
                    throw new ClientException("Retry error", e);
                } else {
                    count++;
                    LOG.warning("It failed. but will be retried: " + e.getMessage());
                    waitRetry(getRetryWaitTime(), count);
                }
            }
        }
        return ret;
    }

    private ShowJobStatusResult doShowJobStatus(ShowJobStatusRequest request)
            throws ClientException {
        request.setCredentials(getConfig().getCredentials());
        validator.validateCredentials(this, request);

        String jsonData = null;
        int code = 0;
        String message = null;
        try {
            conn = createConnection();

            // send request
            String path = String.format(HttpURL.V3_JOBSTATUS_SHOW,
                    HttpConnectionImpl.e(request.getJob().getJobID()));
            Map<String, String> header = new HashMap<String, String>();
            setUserAgentHeader(header);
            Map<String, String> params = null;
            conn.doGetRequest(request, path, header, params);

            // receive response code
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            if (code != HttpURLConnection.HTTP_OK) {
                String errMessage = conn.getErrorMessage();
                checkTrial(code, errMessage);
                LOG.severe(HttpClientException.toMessage(
                        "Show job status failed", message, code));
                LOG.severe(errMessage);
                throw new HttpClientException("Show job status failed",
                        message + ", detail = " + errMessage, code);
            }

            // receive response body
            jsonData = conn.getResponseBody();
            validator.validateJSONData(jsonData);
        } catch (IOException e) {
            LOG.throwing(getClass().getName(), "showJobStatus", e);
            LOG.severe(HttpClientException.toMessage(e.getMessage(), message, code));
            throw new HttpClientException("Show job status failed", message, code, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        // {"status":"success",
        //  "job_id":"4702432",
        //  "created_at":"2013-09-12 03:30:44 UTC",
        //  "updated_at":"2013-09-12 03:30:55 UTC",
        //  "start_at":"2013-09-12 03:30:46 UTC",
        //  "end_at":"2013-09-12 03:30:55 UTC"}
        @SuppressWarnings("unchecked")
        Map<String, Object> jobMap =
            (Map<String, Object>) JSONValue.parse(jsonData);
        validator.validateJavaObject(jsonData, jobMap);

        JobSummary.Status status = JobSummary.toStatus((String) jobMap.get("status"));
        return new ShowJobStatusResult(status);
    }

    @Override
    public GetJobResultResult getJobResult(GetJobResultRequest request)
            throws ClientException {
        int count = 0;
        GetJobResultResult ret;
        while (true) {
            try {
                ret = doGetJobResult(request);
                if (count > 0) {
                    LOG.warning("Retry succeeded.");
                }
                break;
            } catch (ClientException e) {
                // TODO FIXME
                if (count >= getRetryCount()) {
                    LOG.warning("Retry count exceeded limit: " + e.getMessage());
                    throw new ClientException("Retry error", e);
                } else {
                    count++;
                    LOG.warning("It failed. but will be retried: " + e.getMessage());
                    waitRetry(getRetryWaitTime(), count);
                }
            }
        }
        return ret;
    }

    private GetJobResultResult doGetJobResult(GetJobResultRequest request)
            throws ClientException {
        request.setCredentials(getConfig().getCredentials());
        validator.validateCredentials(this, request);

        Unpacker unpacker = null;
        int code = 0;
        String message = null;
        try {
            conn = createConnection();

            // send request
            String path = String.format(HttpURL.V3_JOB_RESULT,
                    HttpConnectionImpl.e(request.getJobResult().getJob().getJobID()));
            Map<String, String> header = new HashMap<String, String>();
            setUserAgentHeader(header);
            Map<String, String> params = new HashMap<String, String>();
            if (request.getJobResult().getFormat() != JobResult.Format.MSGPACKGZ) {
                String msg = String.format("Doesn't support format",
                        request.getJobResult().getFormat());
                throw new UnsupportedOperationException(msg);
            } else {
                params.put("format", JobResult.toFormatName(JobResult.Format.MSGPACKGZ));
            }
            conn.doGetRequest(request, path, header, params);

            // receive response code
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            if (code != HttpURLConnection.HTTP_OK) {
                String errMessage = conn.getErrorMessage();
                checkTrial(code, errMessage);
                LOG.severe(HttpClientException.toMessage(
                        "Get job result failed", message, code));
                LOG.severe(errMessage);
                throw new HttpClientException("Get job result failed",
                        message + ", detail = " + errMessage, code);
            }

            request.getJobResult().setResultSize((long) conn.getContentLength());

            // receive response body
            if (!(request.getJobResult() instanceof JobResult2)) {
                unpacker = conn.getResponseBodyBinaryWithGZip();
                request.getJobResult().setResult(unpacker);
            } else {
                ((JobResult2) request.getJobResult()).setResultInputStream(conn.getInputStream());
            }
        } catch (IOException e) {
            LOG.throwing(getClass().getName(), "getJobResult", e);
            LOG.severe(HttpClientException.toMessage(e.getMessage(), message, code));
            throw new HttpClientException("Get job result failed", message, code, e);
        } finally {
            if (conn != null && !(request.getJobResult() instanceof JobResult2)) {
                conn.disconnect();
            }
        }

        return new GetJobResultResult(request.getJobResult());
    }

    static interface HttpURL {
        String V3_USER_AUTHENTICATE = "/v3/user/authenticate";

        String V3_SYSTEM_SERVER_STATUS = "/v3/system/server_status";

        String V3_DATABASE_LIST = "/v3/database/list";

        String V3_DATABASE_CREATE = "/v3/database/create/%s";

        String V3_DATABASE_DELETE = "/v3/database/delete/%s";

        String V3_TABLE_LIST = "/v3/table/list/%s";

        String V3_TABLE_CREATE = "/v3/table/create/%s/%s/%s";

        String V3_TABLE_RENAME = "/v3/table/rename/%s/%s/%s";

        String V3_TABLE_SWAP = "/v3/table/swap/%s/%s/%s";

        String V3_TABLE_DELETE = "/v3/table/delete/%s/%s";

        String V3_TABLE_DELETE_PARTIAL = "/v3/table/partialdelete/%s/%s";

        String V3_SCHEMA_UPDATE = "/v3/table/update-schema/%s/%s";

        String V3_TABLE_IMPORT = "/v3/table/import/%s/%s/msgpack.gz";

        String V3_EXPORTJOB_SUBMIT = "/v3/export/run/%s/%s";

        String V3_JOB_SUBMIT = "/v3/job/issue/%s/%s";

        String V3_JOB_LIST = "/v3/job/list";

        String V3_JOB_KILL = "/v3/job/kill/%s";

        String V3_JOB_SHOW = "/v3/job/show/%s";

        String V3_JOBSTATUS_SHOW = "/v3/job/status/%s";

        String V3_JOB_RESULT = "/v3/job/result/%s";
    }
}
