/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.treasuredata.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 *
 */
public class TDPartialDeleteJob
{
    private final String jobId;
    private final String database;
    private final String table;
    private final long from;
    private final long to;

    public TDPartialDeleteJob(
            @JsonProperty("job_id") String jobId,
            @JsonProperty("database") String database,
            @JsonProperty("table") String table,
            @JsonProperty("long") long from,
            @JsonProperty("to") long to)
    {
        this.jobId = jobId;
        this.database = database;
        this.table = table;
        this.from = from;
        this.to = to;
    }

    public String getJobId()
    {
        return jobId;
    }

    public String getDatabase()
    {
        return database;
    }

    public String getTable()
    {
        return table;
    }

    public long getFrom()
    {
        return from;
    }

    public long getTo()
    {
        return to;
    }

    @Override
    public String toString()
    {
        return "TDPartialDeleteJob{" +
                "jobId='" + jobId + '\'' +
                ", database='" + database + '\'' +
                ", table='" + table + '\'' +
                ", from=" + from +
                ", to=" + to +
                '}';
    }
}
